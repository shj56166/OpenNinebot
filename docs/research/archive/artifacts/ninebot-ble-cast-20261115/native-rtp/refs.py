from probe import Native, ROOT
import sys, struct, json, re

native = Native()
targets = {int(a,0) for a in sys.argv[1:]}
pages = {a & ~0xfff for a in targets}
refs = []
for offset in range(0, len(native.code)-4, 4):
    word = struct.unpack_from('<I',native.code,offset)[0]
    if word & 0x9f000000 != 0x90000000:
        continue
    immediate = ((word>>5)&0x7ffff)<<2 | ((word>>29)&3)
    if immediate & 0x100000:
        immediate -= 0x200000
    address = native.base + offset
    page = (address & ~0xfff) + (immediate << 12)
    if page not in pages:
        continue
    register = 'x' + str(word & 31)
    for ins in native.md.disasm(native.code[offset+4:offset+164],address+4):
        match = re.fullmatch(r'(x\d+), (x\d+), #(0x[0-9a-f]+|\d+)',ins.op_str)
        if ins.mnemonic == 'add' and match and match[2] == register:
            target = page + int(match[3],0)
            if target in targets:
                start,size = native.owner(address)
                refs.append({'adrp':hex(address),'instruction':hex(ins.address),'target':hex(target),
                             'function':hex(start),'size':hex(size),'kind':'nearby-adrp-add'})
        if ins.mnemonic in ('b','ret'):
            break
result = {'targets':[hex(a) for a in targets], 'references': refs,
          'relocations': [{'slot':hex(k),'target':hex(v)} for k,v in native.relocs.items() if v in targets]}
(ROOT / ('refs-'+'-'.join(f'{a:x}' for a in sorted(targets))+'.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
for ref in refs:
    native.dump(int(ref['function'],16))
print(json.dumps(result,indent=2))
