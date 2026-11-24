from probe import Native, ROOT
import json,struct,sys

native=Native()
field=int(sys.argv[1],0)
hits={}
for offset in range(0,len(native.code)-4,4):
    word=struct.unpack_from('<I',native.code,offset)[0]
    # LDR/STR 64-bit unsigned immediate only; this is an address shortlist, not type proof.
    if word & 0xffc00000 not in (0xf9400000,0xf9000000):
        continue
    if ((word>>10)&0xfff)*8 != field:
        continue
    address=native.base+offset
    start,size=native.owner(address)
    record=hits.setdefault(start,{'function':hex(start),'size':hex(size),'hits':[]})
    record['hits'].append({'instruction':hex(address),'register_base':word>>5&31,
                           'register_value':word&31,'load':bool(word&0x400000)})
rows=list(hits.values())
(ROOT/f'field-{field:x}-refs.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
print(json.dumps({'field':hex(field),'functions':len(rows),'records':rows},indent=2))
