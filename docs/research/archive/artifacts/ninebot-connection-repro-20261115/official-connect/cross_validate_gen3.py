"""Independent ARM64 segment review of Gen3 naming and key lifecycle.

Executes unchanged branch instructions from the frozen official ELF. String
rendering, storage, logging and drops are explicit hooks. This validates call
ordering and arguments, not storage durability or a wireless handshake.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

HERE = Path(__file__).resolve().parent
GEN3 = HERE.parent / "gen3-auth"
sys.path.insert(0, str(GEN3))
spec = importlib.util.spec_from_file_location("secret_boundary_review_input", GEN3 / "emulate_secret_boundary.py")
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X8, UC_ARM64_REG_X19, UC_ARM64_REG_X29


def format_protocol(raw, discriminant):
    m = e.machine(raw, b"a" * 32)
    e.putq(m, e.SECRET, discriminant)
    m.reg_write(e.UC_ARM64_REG_X0, e.SECRET)
    m.reg_write(e.UC_ARM64_REG_X0 + 1, e.RESULT)
    observed = {}

    def hook(m, addr, size, user):
        if addr == 0x63b65c:
            observed["debug_name"] = bytes(m.mem_read(e.reg(m, 1), e.reg(m, 2))).decode("ascii")
            m.emu_stop()

    m.hook_add(UC_HOOK_CODE, hook)
    m.emu_start(0x38cbc0, e.STOP + 4, count=1000)
    assert observed["debug_name"] == ("V1_0", "V2_2", "V2_0", "V2_3", "V3_2")[discriminant]
    return {"case": "native_protocol_formatter", "discriminant": discriminant, **observed}


def promotion(raw, used_key_type, write_fails, delete_fails):
    m = e.machine(raw, b"a" * 32)
    state, pair, shared = e.SECRET, e.MEM + 0x4000, e.MEM + 0x5000
    frame = e.STACK + 0x1e000
    e.putq(m, state + 0xd8, pair)
    e.putq(m, pair + 8, shared)
    e.putq(m, state + 0x170, e.SN)
    e.putq(m, state + 0x178, len(e.SN_TEXT))
    m.mem_write(shared + 0x50, bytes([used_key_type]))
    m.reg_write(UC_ARM64_REG_X19, state)
    m.reg_write(UC_ARM64_REG_X29, frame)
    calls, logs = [], []
    stopped = False

    def hook(m, addr, size, user):
        nonlocal stopped
        if addr == 0x2bd884:
            # Text rendering is deliberately outside this branch test.
            out = e.reg(m, 8)
            for off, val in ((0, 32), (8, e.KEY), (16, 32)):
                e.putq(m, out + off, val)
            e.ret(m)
        elif addr == 0x383b58:
            assert bytes(m.mem_read(e.reg(m, 0), e.reg(m, 1))) == e.SN_TEXT
            calls.append({"operation": "store", "flag": e.reg(m, 4), "synthetic_text_length": e.reg(m, 3)})
            e.ret(m, e.MEM + 0x6800 if write_fails else 0)
        elif addr == 0x383d20:
            assert bytes(m.mem_read(e.reg(m, 0), e.reg(m, 1))) == e.SN_TEXT
            calls.append({"operation": "delete", "flag": e.reg(m, 2)})
            e.ret(m, e.MEM + 0x6900 if delete_fails else 0)
        elif addr == 0x6373c0:
            out = e.reg(m, 8)
            for off, val in ((0, 0), (8, e.KEY), (16, 0)):
                e.putq(m, out + off, val)
            e.ret(m)
        elif addr == 0x3537b0:
            logs.append(bytes(m.mem_read(e.reg(m, 2), e.reg(m, 3))).decode("utf-8", errors="replace"))
            e.ret(m)
        elif addr in (0x288f2c, 0x5a6fac):
            e.ret(m)
        elif addr == 0x2e5d1c:
            stopped = True
            m.emu_stop()

    m.hook_add(UC_HOOK_CODE, hook)
    m.emu_start(0x2e5c00, e.STOP + 4, count=3000)
    assert stopped
    expected = [] if used_key_type != 2 else [{"operation": "store", "flag": 0, "synthetic_text_length": 32}]
    if used_key_type == 2 and not write_fails:
        expected.append({"operation": "delete", "flag": 1})
    assert calls == expected, (used_key_type, write_fails, delete_fails, calls)
    removed_log = "next key saved as cur and next removed" in logs
    assert removed_log == (used_key_type == 2 and not write_fails)
    return {"case": "next_promotion", "used_key_type": used_key_type, "store_error_hook": write_fails,
            "delete_error_hook": delete_fails, "observed_calls": calls, "printed_next_removed": removed_log}


def fallback_gate(raw, status, used, cur_tried, next_tried):
    m = e.machine(raw, b"a" * 32)
    state, pair, shared = e.SECRET, e.MEM + 0x4000, e.MEM + 0x5000
    e.putq(m, state + 0xd8, pair)
    e.putq(m, pair + 8, shared)
    m.mem_write(shared + 0x50, bytes([used, cur_tried, next_tried]))
    m.reg_write(UC_ARM64_REG_X19, state)
    m.reg_write(UC_ARM64_REG_X8, status)
    observed = {}

    def hook(m, addr, size, user):
        if addr == 0x3537b0:
            e.ret(m)
        elif addr in (0x2e5d90, 0x2e6048, 0x2e612c):
            observed["boundary"] = {0x2e5d90: "try_read_cur", 0x2e6048: "both_tried_need_authentication", 0x2e612c: "key_invalid_other_branch"}[addr]
            m.emu_stop()

    m.hook_add(UC_HOOK_CODE, hook)
    m.emu_start(0x2e5d4c, e.STOP + 4, count=1000)
    expected = "try_read_cur" if used == 2 else "both_tried_need_authentication" if cur_tried and next_tried else "key_invalid_other_branch"
    assert observed["boundary"] == expected
    return {"case": "expired_invalid_fallback_gate", "status": status, "used_key_type": used,
            "cur_tried": cur_tried, "next_tried": next_tried, **observed}


def main():
    raw = e.LIB.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == e.EXPECTED
    cases = [format_protocol(raw, d) for d in range(5)]
    cases += [promotion(raw, used, bool(w), bool(d)) for used in (0, 1, 2) for w in (0, 1) for d in (0, 1)]
    cases += [fallback_gate(raw, status, used, cur, nxt) for status in (2, 4) for used in (1, 2) for cur in (0, 1) for nxt in (0, 1)]
    report = {"status": "passed", "ELF_sha256": e.EXPECTED, "cases_passed": len(cases),
              "scope": "Unchanged ARM64 branch segments. Rendering, storage return values, logging and drops are hooks. No cloud, GATT, cipher, actual keyring durability or whole authentication state machine executed.",
              "cases": cases}
    (HERE / "gen3-cross-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "cases"}))


if __name__ == "__main__":
    main()
