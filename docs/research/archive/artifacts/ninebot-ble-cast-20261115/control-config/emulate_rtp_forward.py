"""Execute the original ARM64 RTP future up to its async GATT writer boundary.

Characteristic lookup and config cloning are controlled environment stubs. The
tested function's instructions are read from the frozen official ELF unchanged.
This is not an Android, authentication, radio, or vehicle test.
"""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent / "raw/tools/python"))
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_WRITE
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X8, UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC

LIB = ROOT.parent / "raw/native/librust_lib.so"
EXPECTED = "2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188"
ENTRY, LOOKUP, CLONE, WRITER = 0x2ECCD8, 0x2ED394, 0x325A60, 0x5CDAF0
STATE, INPUT, STACK, STOP = 0x10000000, 0x11000000, 0x20000000, 0x30000000
SELF, CLIENT, OUT, CHARACTER, CONTEXT = STATE+0x1000, STATE+0x3000, STATE+0x4000, STATE+0x5000, STATE+0x6000
MASK64 = (1 << 64) - 1


def qword(uc: Uc, address: int) -> int:
    return struct.unpack("<Q", uc.mem_read(address, 8))[0]


def new_machine(data: bytes) -> Uc:
    elf = ELFFile(io.BytesIO(data))
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    loads = [segment for segment in elf.iter_segments() if segment["p_type"] == "PT_LOAD"]
    high = max(int(segment["p_vaddr"]) + int(segment["p_memsz"]) for segment in loads)
    uc.mem_map(0, (high+0xFFF) & ~0xFFF)
    for segment in loads:
        uc.mem_write(int(segment["p_vaddr"]), segment.data())
    for address, size in ((STATE,0x10000),(INPUT,0x20000),(STACK,0x10000),(STOP,0x1000)):
        uc.mem_map(address, size)
    return uc


def validate(data: bytes, payload: bytes, pending_first: bool = False) -> dict:
    uc = new_machine(data)
    uc.mem_write(SELF, struct.pack("<QQQ", CLIENT, INPUT, len(payload)))
    uc.mem_write(INPUT, payload or b"\x00")
    calls, observed, trace, input_writes = [], {}, [], []
    lookup_count = 0

    def code_hook(machine, address, size, user):
        nonlocal lookup_count
        trace.append(hex(address))
        if address == LOOKUP:
            lookup_count += 1
            lookup_self = machine.reg_read(UC_ARM64_REG_X0)
            service_ptr, service_len = qword(machine, lookup_self+8), qword(machine, lookup_self+16)
            char_ptr, char_len = qword(machine, lookup_self+24), qword(machine, lookup_self+32)
            calls.append({"service": machine.mem_read(service_ptr, service_len).decode(),
                          "characteristic": machine.mem_read(char_ptr, char_len).decode()})
            is_pending = pending_first and lookup_count == 1
            result = struct.pack("<qQ", -2 if is_pending else -1, CHARACTER) + bytes(48)
            machine.mem_write(machine.reg_read(UC_ARM64_REG_X8), result)
            machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))
        elif address == CLONE:
            machine.mem_write(machine.reg_read(UC_ARM64_REG_X8), bytes(16))
            machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))
        elif address == WRITER:
            pointer, length = machine.reg_read(UC_ARM64_REG_X2), machine.reg_read(UC_ARM64_REG_X3)
            observed.update({"pointer_unchanged": pointer == INPUT, "length": length,
                             "payload_unchanged": bytes(machine.mem_read(pointer, length)) == payload,
                             "characteristic_handle": machine.reg_read(UC_ARM64_REG_X1),
                             "write_type_discriminant": machine.reg_read(UC_ARM64_REG_X4)})
            machine.emu_stop()
        elif address == STOP:
            machine.emu_stop()

    def memory_write(machine, access, address, size, value, user):
        input_writes.append({"address": hex(address), "size": size})

    uc.hook_add(UC_HOOK_CODE, code_hook)
    uc.hook_add(UC_HOOK_MEM_WRITE, memory_write, begin=INPUT, end=INPUT+max(1,len(payload))-1)

    def run():
        uc.reg_write(UC_ARM64_REG_X0, SELF)
        uc.reg_write(UC_ARM64_REG_X1, CONTEXT)
        uc.reg_write(UC_ARM64_REG_X8, OUT)
        uc.reg_write(UC_ARM64_REG_SP, STACK+0xF000)
        uc.reg_write(UC_ARM64_REG_X30, STOP)
        uc.emu_start(ENTRY, STOP+4, count=2000)

    run()
    pending_result = None
    if pending_first:
        pending_result = {"result_is_pending": qword(uc,OUT) == MASK64-1,
                          "state_is_3": uc.mem_read(SELF+0x30,1) == b"\x03",
                          "writer_not_called": not observed}
        assert all(pending_result.values()), pending_result
        run()
    expected_service = "6e400001-0000-0000-006e-696e65626f74"
    expected_char = "6e400002-0000-0000-006e-696e65626f74"
    assert observed and observed["pointer_unchanged"] and observed["length"] == len(payload)
    assert observed["payload_unchanged"] and observed["characteristic_handle"] == CHARACTER
    assert observed["write_type_discriminant"] == 1 and not input_writes
    assert all(call == {"service": expected_service, "characteristic": expected_char} for call in calls)
    return {"input_bytes": len(payload), "input_sha256": hashlib.sha256(payload).hexdigest(),
            "writer": observed, "lookup": calls, "input_writes": input_writes,
            "pending_resume": pending_result, "executed_instructions": len(trace),
            "executed_address_trace_sha256": hashlib.sha256("\n".join(trace).encode()).hexdigest()}


def validate_handler_defaults(data: bytes, parameter: int) -> dict:
    """Run only the straight-line initialization block, not the NBBle constructor."""
    uc = new_machine(data)
    allocation, stack = STATE+0x7000, STACK+0xF000
    uc.mem_write(stack+0x88, struct.pack("<Q", parameter))
    uc.reg_write(UC_ARM64_REG_X0, allocation)
    uc.reg_write(UC_ARM64_REG_SP, stack)
    uc.emu_start(0x328B70, 0x328BA8, count=32)
    value = bytes(uc.mem_read(allocation+0x20, 0x22))
    payload_size, rtp_header_size, specific_size, counters = struct.unpack("<QQQQ", value[:0x20])
    fields = {"payload_size": payload_size, "rtp_header_size": rtp_header_size,
              "rtp_specific_header_size": specific_size, "index": counters & 0xFFFFFFFF,
              "ssrc": counters >> 32, "payload_type": value[0x20], "rtp_standard": value[0x21]}
    assert fields == {"payload_size": parameter & 0xFFFF, "rtp_header_size": 12,
                      "rtp_specific_header_size": 8, "index": 0, "ssrc": 0,
                      "payload_type": 32, "rtp_standard": 1}, fields
    return {"constructor_parameter": parameter, "fields": fields}


def main() -> None:
    data = LIB.read_bytes()
    assert hashlib.sha256(data).hexdigest() == EXPECTED
    lengths = [0,1,19,20,21,64,128,220,240,244,512,1024,4096]
    cases = [validate(data, bytes((index*37+0x90) & 0xFF for index in range(length))) for length in lengths]
    cases.append(validate(data, bytes.fromhex("9080123401020304112233440000000100010000000001b3"), True))
    initializers = [validate_handler_defaults(data, value) for value in (0,244,247,512,65535,65536,65537)]
    report = {"status": "passed", "library_sha256": EXPECTED,
              "entry": hex(ENTRY), "captured_writer": hex(WRITER), "cases_passed": len(cases),
              "engine": "Unicorn 2.1.4 executes unchanged ARM64 instructions",
              "hooks": {hex(LOOKUP): "GATT characteristic lookup returns selected Ready or Pending state",
                        hex(CLONE): "Environment config clone, no media access",
                        hex(WRITER): "Capture original arguments, stop before downstream execution"},
              "scope": "Original 0x2eccd8 forwarding only; downstream JNI/GATT and radio are not executed",
              "cases": cases, "initializer_cases_passed": len(initializers),
              "handler_initializer": {"begin": "0x328b70", "end_exclusive": "0x328ba8",
                                      "scope": "Original basic block only; does not prove handler use in Java RTP route",
                                      "cases": initializers}}
    (ROOT / "native-forward-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(json.dumps({key: value for key,value in report.items() if key not in ("cases", "handler_initializer")}))


if __name__ == "__main__":
    main()
