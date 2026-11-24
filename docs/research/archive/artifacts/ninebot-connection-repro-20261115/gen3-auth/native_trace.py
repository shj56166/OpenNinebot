from pathlib import Path
import bisect, hashlib, json, re, struct, sys
from elftools.elf.elffile import ELFFile
from elftools.dwarf.callframe import FDE
from elftools.elf.relocation import RelocationSection
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM

ROOT = Path(__file__).resolve().parent
LIB = ROOT.parents[2] / 'artifacts/ninebot-ble-cast-20261115/raw/native/librust_lib.so'
elf_file = LIB.open('rb')
elf = ELFFile(elf_file)
sections = [s for s in elf.iter_sections() if int(s['sh_flags']) & 2 and s['sh_type'] != 'SHT_NOBITS']
section_bytes = [(int(s['sh_addr']), bytearray(s.data())) for s in sections]
text = elf.get_section_by_name('.text')
text_base, text_raw = int(text['sh_addr']), text.data()
fdes = sorted((int(e['initial_location']), int(e['address_range'])) for e in elf.get_dwarf_info().EH_CFI_entries() if isinstance(e, FDE))
starts = [x[0] for x in fdes]
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

def read(addr, size):
    for base, data in section_bytes:
        if base <= addr and addr + size <= base + len(data):
            off = addr - base
            return bytes(data[off:off+size])
    return b''

def function(addr):
    i = bisect.bisect_right(starts, addr)-1
    return fdes[i] if i >= 0 and addr < sum(fdes[i]) else (addr, 64)

relocations = {}
for sec in elf.iter_sections():
    if isinstance(sec, RelocationSection):
        symbols = elf.get_section(sec['sh_link'])
        for rel in sec.iter_relocations():
            symbol = symbols.get_symbol(rel['r_info_sym']).name if rel['r_info_sym'] else ''
            if symbol:
                relocations[int(rel['r_offset'])] = symbol
            if rel['r_info_type'] == 1027 and rel.is_RELA():
                addr, val = int(rel['r_offset']), int(rel['r_addend'])
                for base, data in section_bytes:
                    if base <= addr and addr + 8 <= base + len(data):
                        struct.pack_into('<Q', data, addr-base, val)
                        break
plt = {}
for sec in sections:
    if sec.name == '.plt':
        regs = {}
        for ins in md.disasm(sec.data(), int(sec['sh_addr'])):
            if ins.mnemonic == 'adrp':
                m = re.fullmatch(r'(x\d+), #0x([0-9a-f]+)', ins.op_str)
                if m: regs[m[1]] = int(m[2],16)
            if ins.mnemonic == 'ldr':
                m = re.fullmatch(r'x\d+, \[(x\d+), #0x([0-9a-f]+)\]', ins.op_str)
                if m and m[1] in regs:
                    got = regs[m[1]] + int(m[2],16)
                    if got in relocations:
                        plt[ins.address-4] = relocations[got]

def dump(addr):
    start, size = function(addr)
    lines, calls, refs, regs = [], [], [], {}
    for ins in md.disasm(read(start, size), start):
        m, op, annotation = ins.mnemonic, ins.op_str, ''
        if m in ('adrp','adr'):
            match = re.fullmatch(r'(x\d+), #0x([0-9a-f]+)',op)
            if match: regs[match[1]]=int(match[2],16)
        elif m=='add':
            match=re.fullmatch(r'(x\d+), (x\d+), #0x([0-9a-f]+)',op)
            if match and match[2] in regs:
                target=regs[match[2]]+int(match[3],16); regs[match[1]]=target
                refs.append({'instruction':hex(ins.address),'address':hex(target)})
                value=read(target,128).split(b'\0')[0]
                if len(value)>2 and all(32<=x<127 for x in value): annotation=' ; '+repr(value.decode())
        elif m=='ldr':
            match=re.fullmatch(r'(x\d+), \[(x\d+)(?:, #0x([0-9a-f]+))?\]',op)
            if match and match[2] in regs:
                pos=regs[match[2]]+int(match[3] or '0',16)
                data=read(pos,8)
                if pos in relocations: annotation=' ; GOT '+relocations[pos]
                if data:
                    regs[match[1]]=struct.unpack('<Q',data)[0]
                    target=regs[match[1]]
                    if text_base <= target < text_base+len(text_raw): annotation+=' ; fn '+hex(target)
        elif m in ('bl','b') and op.startswith('#'):
            target=int(op[1:],16)
            if m=='bl': calls.append({'instruction':hex(ins.address),'target':hex(target),'symbol':plt.get(target)})
            if target in plt: annotation=' ; '+plt[target]
            if m=='bl': regs={k:v for k,v in regs.items() if int(k[1:])>=19}
        lines.append(f'{ins.address:#x} {m} {op}{annotation}')
    (ROOT/f'fn-{start:x}.asm.txt').write_text('\n'.join(lines),encoding='utf-8')
    return {'function':hex(start),'size':hex(size),'calls':calls,'references':refs}

def callers(targets):
    results=[]
    # Fixed-width decoding avoids a full-section Capstone scan stopping at a
    # literal/invalid instruction. Keep only locations inside known FDE ranges.
    for off in range(0,len(text_raw)-3,4):
        word=struct.unpack_from('<I',text_raw,off)[0]
        if word & 0xFC000000 in (0x14000000,0x94000000):
            immediate=word&0x3FFFFFF
            if immediate&0x2000000: immediate-=0x4000000
            addr=text_base+off;target=addr+immediate*4
            ix=bisect.bisect_right(starts,addr)-1
            if target in targets and ix>=0 and addr<sum(fdes[ix]):
                results.append({'instruction':hex(addr),'function':hex(fdes[ix][0]),'target':hex(target),'symbol':plt.get(target)})
    return results

if __name__=='__main__':
    mode=sys.argv[1]
    if mode=='dump':
        result=[dump(int(x,16)) for x in sys.argv[2:]]
    elif mode=='crypto':
        targets={k for k,v in plt.items() if v.startswith('crypto_')}
        result={'plt':{hex(k):v for k,v in plt.items() if k in targets},'callers':callers(targets)}
        for addr in {int(c['function'],16) for c in result['callers']}: dump(addr)
    elif mode=='callers': result=callers({int(x,16) for x in sys.argv[2:]})
    else: raise SystemExit('dump|crypto|callers')
    (ROOT/(mode+'-summary.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
    if mode=='dump':
        print(json.dumps([{'function':r['function'],'size':r['size'],'calls':len(r['calls']),'refs':len(r['references'])} for r in result]))
    else:print(json.dumps(result,indent=2))
