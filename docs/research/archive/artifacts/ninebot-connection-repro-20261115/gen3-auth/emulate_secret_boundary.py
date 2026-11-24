"""Execute original ARM64 SecretInfo/hex validation with synthetic inputs.

Storage, Rust allocation/formatting and errors are hooks. This verifies original
branch decisions and arguments at the storage boundary; no vehicle/cloud IO.
"""
from pathlib import Path
import hashlib, io, json, struct, sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[1] / 'ninebot-ble-cast-20261115/raw/tools/python'))
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *

LIB = ROOT.parents[1] / 'ninebot-ble-cast-20261115/raw/native/librust_lib.so'
EXPECTED = '2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188'
MEM, STACK, STOP = 0x10000000, 0x20000000, 0x30000000
SN, KEY, FORMATTED, SECRET, RESULT = [MEM + v for v in (0x100, 0x400, 0x800, 0x1000, 0x2000)]
SN_TEXT = b'SYNTHETIC_SN_001'
MASK = (1 << 64) - 1

def q(m, a): return struct.unpack('<Q', m.mem_read(a, 8))[0]
def putq(m, a, v): m.mem_write(a, struct.pack('<Q', v & MASK))
def reg(m, i): return m.reg_read(UC_ARM64_REG_X0 + i)
def ret(m, value=None):
    if value is not None: m.reg_write(UC_ARM64_REG_X0, value)
    m.reg_write(UC_ARM64_REG_PC, m.reg_read(UC_ARM64_REG_X30))

def machine(raw, payload):
    elf = ELFFile(io.BytesIO(raw)); m = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    loads = [s for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
    limit = max(int(s['p_vaddr']) + int(s['p_memsz']) for s in loads)
    m.mem_map(0, (limit + 4095) & ~4095)
    for s in loads: m.mem_write(int(s['p_vaddr']), s.data())
    for s in elf.iter_sections():
        if isinstance(s, RelocationSection):
            for r in s.iter_relocations():
                if r['r_info_type'] == 1027 and r.is_RELA(): putq(m, int(r['r_offset']), int(r['r_addend']))
    for a, size in ((MEM, 0x10000), (STACK, 0x20000), (STOP, 0x1000)): m.mem_map(a, size)
    m.mem_write(SN, SN_TEXT); m.mem_write(KEY, payload or b'\0')
    m.reg_write(UC_ARM64_REG_SP, STACK + 0x1f000)
    m.reg_write(UC_ARM64_REG_X30, STOP)
    return m

def execute(raw, payload, temp=0, disabled=0, secret=False, version=3, sn_present=True, key_present=True):
    m = machine(raw, payload); seen = {'stores': [], 'deletes': [], 'errors': [], 'formats': []}
    m.mem_write(0x684de8, bytes([disabled]))
    def hook(m, a, size, user):
        if a == 0x37d4bc:
            sn = bytes(m.mem_read(reg(m, 0), reg(m, 1)))
            key = bytes(m.mem_read(reg(m, 2), reg(m, 3)))
            seen['stores'].append({'namespace': 'temporary' if sn.endswith(b'.__next__') else 'primary', 'sn_matches': sn in (SN_TEXT, SN_TEXT+b'.__next__'), 'key_length': len(key), 'original_key_bytes': key == payload})
            putq(m, reg(m, 8), -1); ret(m)
        elif a == 0x383d20:
            seen['deletes'].append({'flag': reg(m, 2), 'sn_matches': bytes(m.mem_read(reg(m, 0), reg(m, 1))) == SN_TEXT}); ret(m, 0)
        elif a == 0x6373c0:
            if reg(m, 0) == 0xd1788:
                ptr = q(m, reg(m, 1)); sn = bytes(m.mem_read(q(m, ptr), q(m, ptr+8)))
                assert sn == SN_TEXT
                value = sn + b'.__next__'; kind = 'temporary_namespace'
            else:
                assert reg(m, 0) == 0xc83b5, hex(reg(m, 0))
                value = b'synthetic error log'; kind = 'error_log'
            m.mem_write(FORMATTED, value)
            out = reg(m, 8)
            for offset, v in ((0, len(value)), (8, FORMATTED), (16, len(value))): putq(m, out+offset, v)
            seen['formats'].append({'format_address': hex(reg(m, 0)), 'kind': kind})
            ret(m)
        elif a in (0x374040, 0x5a606c):
            seen['errors'].append({'error_helper': hex(a), 'message_address': hex(reg(m, 0))}); ret(m, MEM+0x3000)
        elif a in (0x288f2c, 0x5a6fac, 0x359310): ret(m)
        elif a == STOP: m.emu_stop()
    m.hook_add(UC_HOOK_CODE, hook)
    if secret:
        for off, v in ((0, 0 if sn_present else -1), (8, SN), (16, len(SN_TEXT)), (24, 0 if key_present else -1), (32, KEY), (40, len(payload))): putq(m, SECRET+off, v)
        args = [version, SECRET]; entry = 0x37e1e4
    else: args = [SN, len(SN_TEXT), KEY, len(payload), temp]; entry = 0x383b58
    for i, v in enumerate(args): m.reg_write(UC_ARM64_REG_X0+i, v)
    m.emu_start(entry, STOP+4, count=20000)
    seen['success'] = reg(m, 0) == 0
    return seen

def choose_key(raw, primary, temporary):
    """Segmented execution of 31c524's selection, stopping before hex decoder."""
    m = machine(raw, b'a'*32); state, pair, shared = SECRET, MEM+0x4000, MEM+0x5000
    temp_text = MEM+0x6000; m.mem_write(temp_text, b'b'*32)
    putq(m, state+8, pair); putq(m, pair+8, shared)
    for off, present, ptr in ((0x28, primary, KEY), (0x50, temporary, temp_text)):
        putq(m, state+off, 32 if present else -1)
        putq(m, state+off+8, ptr); putq(m, state+off+16, 32)
    m.reg_write(UC_ARM64_REG_X19, state); m.reg_write(UC_ARM64_REG_X20, state+8)
    m.reg_write(UC_ARM64_REG_X24, STACK+0x1f000+0x180)
    observed = {}
    def hook(m, a, size, user):
        if a == 0x288f2c: ret(m)
        elif a == 0x2bdd60:
            value = bytes(m.mem_read(q(m, reg(m, 0)+8), q(m, reg(m, 0)+16)))
            observed['selected'] = 'temporary' if value == b'b'*32 else 'primary' if value == b'a'*32 else 'unexpected'
            m.emu_stop()
        elif a == 0x31cce4: observed['selected'] = 'missing'; m.emu_stop()
    m.hook_add(UC_HOOK_CODE, hook)
    m.emu_start(0x31ca58, STOP+4, count=2000)
    observed['used_key_type'], observed['cur_tried'], observed['next_tried'] = list(m.mem_read(shared+0x50, 3))
    expected = 'temporary' if temporary else 'primary' if primary else 'missing'
    assert observed['selected'] == expected
    if expected != 'missing':
        assert observed['used_key_type'] == (2 if temporary else 1)
        assert observed['cur_tried'] == int(primary and not temporary)
        assert observed['next_tried'] == int(temporary)
    return {'case': 'original_key_selection_segment', 'primary_available': primary, 'temporary_available': temporary, 'observed': observed}

def main():
    raw = LIB.read_bytes(); assert hashlib.sha256(raw).hexdigest() == EXPECTED
    cases = []
    for position in (0, 31):
        for byte in range(128):
            payload = bytearray(b'0' * 32); payload[position] = byte
            result = execute(raw, bytes(payload))
            expected = byte in b'0123456789ABCDEFabcdef'
            assert result['success'] == expected
            assert len(result['stores']) == int(expected)
            if expected: assert result['stores'][0]['original_key_bytes'] and result['stores'][0]['key_length'] == 32
            cases.append({'case': 'ascii_char', 'position': position, 'ascii_value': byte, 'accepted': expected})
    for n in (0, 1, 16, 31, 33, 64):
        result = execute(raw, b'a'*n); assert not result['success'] and not result['stores']
        cases.append({'case': 'length', 'bytes': n, 'accepted': False})
    for payload in (b'0123456789abcdefABCDEF0123456789', ('\u00e9' * 16).encode('utf-8')):
        result = execute(raw, payload, temp=1); expected = payload.isascii()
        assert result['success'] == expected
        if expected: assert result['stores'][0]['namespace'] == 'temporary'
        cases.append({'case': 'temporary_namespace', 'ascii_input': payload.isascii(), 'observed': result})
    for version, sn_present, key_present, payload, expected in (
        (2, True, True, b'a'*32, False), (3, False, True, b'a'*32, False),
        (3, True, False, b'a'*32, False), (3, True, True, b'a'*32, True),
        (3, True, True, b'', True), (3, True, True, b'g'*32, False)):
        result = execute(raw, payload, secret=True, version=version, sn_present=sn_present, key_present=key_present)
        assert result['success'] == expected
        if not payload: assert [x['flag'] for x in result['deletes']] == [0, 1]
        if expected and payload: assert result['stores'][0]['namespace'] == 'temporary'
        cases.append({'case': 'SecretInfo', 'version': version, 'sn_present': sn_present, 'key_present': key_present, 'key_bytes': len(payload), 'observed': result})
    result = execute(raw, b'invalid', disabled=1)
    assert result['success'] and not result['stores'] and not result['errors']
    cases.append({'case': 'global_bypass_684de8', 'observed': result})
    cases.extend(choose_key(raw, primary, temporary) for primary in (False, True) for temporary in (False, True))
    report = {'status': 'passed', 'ELF_sha256': EXPECTED, 'engine': 'Unicorn 2.1.4; original ARM64 instructions', 'cases_passed': len(cases), 'scope': 'SecretInfo dispatcher and validation only. Formatting/error/storage are hooks; no cryptographic handshake or vehicle interoperability is established.', 'cases': cases}
    (ROOT/'secret-boundary-validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='cases'}))

if __name__ == '__main__': main()
