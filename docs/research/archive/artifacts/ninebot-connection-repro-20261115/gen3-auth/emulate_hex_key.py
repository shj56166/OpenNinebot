"""Run official hex decoder ARM64 chain; only allocation/drop are stubs."""
from pathlib import Path
import hashlib, json, struct
import emulate_secret_boundary as e
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *

ROOT = Path(__file__).resolve().parent
HEAP = e.MEM + 0x7000

def decode(raw, payload):
    m = e.machine(raw, payload); calls = []; allocations = []; reserves = []
    for off, v in ((0, len(payload)), (8, e.KEY), (16, len(payload))): e.putq(m, e.SECRET+off, v)
    def hook(m, a, size, user):
        if a == 0x636c2c:
            n = e.reg(m, 0); out = e.reg(m, 8)
            assert 0 <= n <= 1024
            for off, value in ((0, 0), (8, n), (16, HEAP)): e.putq(m, out+off, value)
            allocations.append(n); e.ret(m)
        elif a == 0x635fe0:
            m.reg_write(UC_ARM64_REG_X0, 0); m.reg_write(UC_ARM64_REG_X1, 1); e.ret(m)
        elif a == 0x288f2c: e.ret(m)
        elif a == 0x636d74:
            vec = e.reg(m,0); cap, ptr, length = [e.q(m, vec+i*8) for i in range(3)]
            new_cap = max(cap*2, length+e.reg(m,1)); assert ptr == HEAP and new_cap <= 4096
            e.putq(m,vec,new_cap); reserves.append({'from':cap,'to':new_cap}); e.ret(m)
        elif a in (0x2bdd60, 0x2a6420, 0x384da0, 0x2bcf54, 0x27e288): calls.append(hex(a))
        elif a == e.STOP: m.emu_stop()
    m.hook_add(UC_HOOK_CODE, hook)
    m.reg_write(UC_ARM64_REG_X0, e.SECRET); m.reg_write(UC_ARM64_REG_X8, e.RESULT)
    m.emu_start(0x2bdd60, e.STOP+4, count=20000)
    error = e.q(m, e.RESULT) == e.MASK
    result = None if error else bytes(m.mem_read(e.q(m,e.RESULT+8),e.q(m,e.RESULT+16)))
    try: expected = bytes.fromhex(payload.decode('ascii'))
    except (UnicodeDecodeError, ValueError): expected = None
    # Python accepts whitespace; native intentionally does not. These test inputs
    # contain no whitespace in valid vectors.
    if any(c in b' \t\n\r\v\f' for c in payload): expected = None
    assert result == expected, (len(payload), error)
    return {'input_bytes': len(payload), 'accepted': not error, 'output_bytes': len(result) if result is not None else None, 'reference_matches': True, 'original_chain': sorted(set(calls)), 'allocator_sizes': allocations, 'reserve_hooks':reserves}

def main():
    raw=e.LIB.read_bytes(); assert hashlib.sha256(raw).hexdigest()==e.EXPECTED
    vectors = [
        ('empty', b''), ('one_byte', b'00'), ('upper_case', b'ABCDEF01'),
        ('lower_case_16', bytes(range(16)).hex().encode('ascii')),
        ('mixed_case_16', b'0123456789aBcDeFaBcDeF0123456789'),
        ('all_16_byte_values', bytes(range(256)).hex().encode('ascii')),
        ('odd_1', b'0'), ('odd_31', b'a'*31), ('invalid_first', b'g'+b'0'*31),
        ('invalid_last', b'0'*31+b'G'), ('space', b'00 00 '), ('non_ascii', ('\u00e9'*16).encode('utf-8')),
    ]
    cases=[dict(case=name, **decode(raw,payload)) for name,payload in vectors]
    report={'status':'passed','ELF_sha256':e.EXPECTED,'engine':'Unicorn 2.1.4; unchanged ARM64 instructions','cases_passed':len(cases),'scope':'Original 2bdd60 -> 2a6420 -> 384da0 -> 2bcf54/27e288 decoder. Allocation and drop hooked; no cipher, radio, or cloud execution. Synthetic inputs only.','cases':cases}
    (ROOT/'hex-key-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='cases'}))

if __name__=='__main__':main()
