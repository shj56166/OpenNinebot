"""Segmented original-ARM64 checks at the RTP writer/JNI environment boundary.

This executes frozen ELF instructions. The allocator and actual JVM operations
are explicit stubs, not Android/radio emulation. The three segments are not an
end-to-end SDK test and must not be counted as vehicle interoperability.
"""
from pathlib import Path
import hashlib, io, json, struct, sys
ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent/'raw/tools/python'))
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE
from unicorn.arm64_const import *

LIB = ROOT.parent/'raw/native/librust_lib.so'
EXPECTED = '2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188'
MEM, IN, STACK, HOOK = 0x10000000, 0x11000000, 0x20000000, 0x30000000
STOP, HEAP = HOOK, MEM+0x1000
CLIENT, CHAR, CONTEXT, OUT = MEM+0x2000, MEM+0x3000, MEM+0x4000, MEM+0x5000
ENVWRAP, ENV, TABLE = MEM+0x6000, MEM+0x7000, MEM+0x8000
CACHE, UUIDWRAP, JUUID, JARRAY, PERIPHERAL, METHOD = [MEM+x for x in (0x9000,0xA000,0xB000,0xC000,0xD000,0xE000)]
NEWARRAY, EXCHECK, SETREGION = HOOK+0x100, HOOK+0x200, HOOK+0x300

def q(uc, a): return struct.unpack('<Q',uc.mem_read(a,8))[0]
def putq(uc,a,v): uc.mem_write(a,struct.pack('<Q',v))
def ret(uc): uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_X30))
def reg(uc,i): return uc.reg_read(UC_ARM64_REG_X0+i)

def machine(data,payload):
    elf=ELFFile(io.BytesIO(data));uc=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    loads=[s for s in elf.iter_segments() if s['p_type']=='PT_LOAD']
    end=max(int(s['p_vaddr'])+int(s['p_memsz']) for s in loads)
    uc.mem_map(0,(end+4095)&~4095)
    for s in loads: uc.mem_write(int(s['p_vaddr']),s.data())
    for s in elf.iter_sections():
        if isinstance(s,RelocationSection):
            for r in s.iter_relocations():
                if r['r_info_type']==1027 and r.is_RELA():putq(uc,int(r['r_offset']),int(r['r_addend']))
    for a,n in [(MEM,0x20000),(IN,0x20000),(STACK,0x10000),(HOOK,0x1000)]:uc.mem_map(a,n)
    uc.mem_write(IN,payload or b'\0')
    writes=[]
    uc.hook_add(UC_HOOK_MEM_WRITE,lambda m,t,a,s,v,u:writes.append((hex(a),s)),begin=IN,end=IN+max(len(payload),1)-1)
    return uc,writes

def invoke(uc,entry,args,out=OUT):
    for i,v in enumerate(args):uc.reg_write(UC_ARM64_REG_X0+i,v)
    uc.reg_write(UC_ARM64_REG_X8,out);uc.reg_write(UC_ARM64_REG_X30,STOP)
    uc.reg_write(UC_ARM64_REG_SP,STACK+0xF000)
    uc.emu_start(entry,STOP+4,count=2000)

def constructor_poll(data,payload,kind):
    uc,writes=machine(data,payload);observed={};trace=[]
    def hook(m,a,s,u):
        trace.append(a)
        if a==0x39AA94:ret(m)
        elif a==0x39AA84:
            assert reg(m,0)==0x40 and reg(m,1)==8
            m.reg_write(UC_ARM64_REG_X0,HEAP);ret(m)
        elif a==0x5B2450:
            t=reg(m,1);values=[q(m,t+i*8) for i in range(4)]
            observed.update(client=reg(m,0),char=values[0],pointer=values[1],length=values[2],type_byte=m.mem_read(values[3],1)[0])
            m.emu_stop()
        elif a==STOP:m.emu_stop()
    uc.hook_add(UC_HOOK_CODE,hook)
    invoke(uc,0x5CDAF0,[CLIENT,CHAR,IN,len(payload),kind])
    assert reg(uc,0)==HEAP and reg(uc,1)==0x679010
    poll=q(uc,0x679028);assert poll==0x5BFBC8
    invoke(uc,poll,[HEAP,CONTEXT])
    assert observed==dict(client=CLIENT,char=CHAR,pointer=IN,length=len(payload),type_byte=kind)
    assert bytes(uc.mem_read(IN,len(payload)))==payload and not writes
    return {'segment':'5cdaf0 constructor -> relocated vtable -> 5bfbc8 poll -> 5b2450 boundary','bytes':len(payload),'kind':kind,'observed':observed,'input_writes':writes,'instructions':len(trace)}

def bytearray_copy(data,payload):
    uc,writes=machine(data,payload);seen=[]
    putq(uc,ENVWRAP,ENV);putq(uc,ENV,TABLE)
    for off,fn in [(0x580,NEWARRAY),(0x720,EXCHECK),(0x680,SETREGION)]:putq(uc,TABLE+off,fn)
    def hook(m,a,s,u):
        if a==NEWARRAY:
            assert reg(m,0)==ENV and reg(m,1)==len(payload)
            seen.append({'operation':'NewByteArray','length':reg(m,1)})
            m.reg_write(UC_ARM64_REG_X0,JARRAY);ret(m)
        elif a==EXCHECK:m.reg_write(UC_ARM64_REG_X0,0);ret(m)
        elif a==SETREGION:
            e,array,start,n,ptr=[reg(m,i) for i in range(5)]
            assert (e,array,start,n,ptr)==(ENV,JARRAY,0,len(payload),IN)
            b=bytes(m.mem_read(ptr,n));assert b==payload
            seen.append({'operation':'SetByteArrayRegion','start':start,'length':n,'pointer_unchanged':ptr==IN,'data_unchanged':b==payload})
            ret(m)
        elif a==STOP:m.emu_stop()
    uc.hook_add(UC_HOOK_CODE,hook)
    invoke(uc,0x5D6390,[ENVWRAP,IN,len(payload)])
    assert uc.mem_read(OUT,1)==b'\xff' and q(uc,OUT+8)==JARRAY
    assert len(seen)==2 and not writes
    return {'segment':'5d6390 -> original NewByteArray and SetByteArrayRegion wrappers -> JVM table hooks','bytes':len(payload),'calls':seen,'input_writes':writes}

def java_write_args(data,kind):
    uc,writes=machine(data,b'');observed={}
    putq(uc,CACHE,ENVWRAP);putq(uc,CACHE+8,PERIPHERAL);putq(uc,CACHE+0x40,METHOD)
    putq(uc,UUIDWRAP+8,JUUID)
    def hook(m,a,s,u):
        if a==0x637704:
            ptr,n=reg(m,0),reg(m,1)
            assert bytes(m.mem_read(ptr,n))==b'Lio/github/gedgygedgy/rust/future/Future;'
            m.mem_write(reg(m,8),bytes(24));ret(m)
        elif a==0x5D9F60:
            assert [reg(m,i) for i in range(3)]==[ENVWRAP,PERIPHERAL,METHOD]
            ptr,n=reg(m,4),reg(m,5);assert n==3
            args=[]
            for i in range(3):
                a=ptr+i*16; tag=m.mem_read(a,1)[0]
                value=struct.unpack('<i',m.mem_read(a+4,4))[0] if tag==4 else q(m,a+8)
                args.append((tag,value))
            assert args==[(0,JUUID),(0,JARRAY),(4,kind)],args
            observed.update(args=args,count=n,method_cache_offset='0x40');m.emu_stop()
        elif a==STOP:m.emu_stop()
    uc.hook_add(UC_HOOK_CODE,hook)
    invoke(uc,0x5C1684,[CACHE,UUIDWRAP,JARRAY,kind])
    assert observed
    return {'segment':'5c1684 -> original argument builder -> generic JNI call boundary 5d9f60','android_write_type':kind,'observed':observed}

def main():
    data=LIB.read_bytes();assert hashlib.sha256(data).hexdigest()==EXPECTED
    lengths=[0,1,20,21,64,128,220,244,512,1024,4096]
    cases=[]
    for n in lengths:
        b=bytes((i*31+0x90)&255 for i in range(n))
        cases.append(constructor_poll(data,b,1))
        cases.append(bytearray_copy(data,b))
    cases.extend(java_write_args(data,t) for t in [1,2])
    report={'status':'passed','ELF_sha256':EXPECTED,'engine':'Unicorn 2.1.4; unchanged ARM64 instructions','cases_passed':len(cases),'scope':'24 segmented checks; JVM allocation/copy implementations are hooks; generic JNI invocation and Android radio not executed','cases':cases}
    (ROOT/'gatt-boundary-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='cases'}))
if __name__=='__main__':main()
