from probe import Native,ROOT
import json,re,struct,sys
n=Native();functions={}
fields={int(a,0) for a in sys.argv[1:]} or {0x21}
for offset in range(0,len(n.code)-4,4):
    word=struct.unpack_from('<I',n.code,offset)[0]
    if word&0xffc00000 not in (0x39400000,0x39000000) or ((word>>10)&0xfff) not in fields or ((word>>5)&31) in (29,31):
        continue
    address=n.base+offset
    if address>0x390000:
        continue
    start,size=n.owner(address)
    functions.setdefault(start,{'function':hex(start),'size':hex(size),'hits':[]})['hits'].append(hex(address))
for start,row in functions.items():
    n.dump(start)
    row['byte_memory_ops']=[f'{ins.address:#x} {ins.mnemonic} {ins.op_str}'
                            for ins in n.md.disasm(n.read(start,int(row['size'],16)),start)
                            if ins.mnemonic in ('ldrb','strb','ldrh','strh') and '#0x' in ins.op_str]
(ROOT/('layout-candidates-'+ '-'.join(hex(a)[2:] for a in sorted(fields))+'.json')).write_text(json.dumps(list(functions.values()),indent=2),encoding='utf-8')
print(json.dumps([{k:v for k,v in row.items() if k!='byte_memory_ops'} for row in functions.values()],indent=2))
