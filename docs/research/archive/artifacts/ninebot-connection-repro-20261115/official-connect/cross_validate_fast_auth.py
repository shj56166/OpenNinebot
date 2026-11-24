"""Cross-review TLV parser using original HashMap insertion, without that hook.

The owner's fast-auth suite is rerun separately and not counted here. This
supplement runs the original map implementation to test final duplicate-tag
behavior. Only hash seed and the owner's low-level memory/runtime are hooks.
"""
from pathlib import Path
import hashlib
import json
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "gen3-auth"))
import emulate_fast_auth as f
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X8

e = f.e


def parse_map(raw, payload, type_width, length_width):
    m, state = f.runtime(raw)
    m.mem_write(e.MEM + 0x4000, payload or b"\0")
    insert_calls = []

    def hook(m, addr, size, user):
        if addr == 0x5e4de4:
            m.reg_write(UC_ARM64_REG_X0, 0x12345678)
            m.reg_write(UC_ARM64_REG_X1, 0x87654321)
            e.ret(m)
        elif addr == 0x355fb8:
            # Observe, then execute the original insertion instructions.
            insert_calls.append(e.reg(m, 1))

    m.hook_add(UC_HOOK_CODE, hook)
    for i, val in enumerate((e.MEM + 0x4000, len(payload), type_width, length_width)):
        m.reg_write(UC_ARM64_REG_X0 + i, val)
    m.reg_write(UC_ARM64_REG_X8, e.RESULT)
    m.emu_start(0x37ce98, e.STOP + 4, count=200000)
    assert state["stopped"]
    ctrl, mask, growth_left, items = (e.q(m, e.RESULT + i * 8) for i in range(4))
    actual = {}
    for i in range(mask + 1):
        if bytes(m.mem_read(ctrl + i, 1))[0] < 128:
            record = ctrl - (i + 1) * 32
            tag, capacity, ptr, size = (e.q(m, record + j * 8) for j in range(4))
            actual[tag] = bytes(m.mem_read(ptr, size))
    expected = dict(f.reference_tlv(payload, type_width, length_width))
    assert actual == expected
    assert items == len(actual)
    return {"input_hex": payload.hex(), "type_width": type_width, "length_width": length_width,
            "original_insert_tags": insert_calls, "final_map_items": items,
            "final_values": [{"tag": k, "value_hex": v.hex()} for k, v in sorted(actual.items())]}


def main():
    raw = e.LIB.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == e.EXPECTED
    inputs = [
        ("duplicate_last_value", b"\x0f\x01\x00\x0f\x01\x04", 1, 1),
        ("duplicate_last_empty", b"\x0f\x01\x00\x0f\x00", 1, 1),
        ("interleaved_duplicates", b"\x0f\x01\x00\x14\x01A\x0f\x01\x04", 1, 1),
        ("duplicate_then_short_value", b"\x0f\x01\x00\x0f\x01\x04\x0f\x02A", 1, 1),
        ("duplicate_header_without_value", b"\x0f\x01\x00\x0f\x01", 1, 1),
        ("long_tlv_duplicate", b"\x12\x34\x03\x00ABC\x12\x34\x02\x00DE", 2, 2),
        ("malformed_tail_no_resync", b"\x0f\x01\x00\x14\xffA\x0f\x01\x04", 1, 1),
    ]
    cases = [dict(case=name, **parse_map(raw, payload, tw, lw)) for name, payload, tw, lw in inputs]
    report = {"status": "passed", "ELF_sha256": e.EXPECTED, "cases_passed": len(cases),
              "scope": "Supplemental original 37ce98 parser and 355fb8 HashMap insertion. Hash seed, memory and runtime hooks only; not the owner's 47-case suite repeated as new coverage. No radio, cloud, credentials or cipher use.", "cases": cases}
    (HERE / "fast-auth-map-cross-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "cases"}))


if __name__ == "__main__":
    main()
