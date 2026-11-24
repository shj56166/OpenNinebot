"""Run original official ARM64 Encryption2, with only libc/TLS supplied.

This does not run JNI, Android Bluetooth or vehicle firmware. AES, SHA1,
KDF, nonce/tag construction and framing execute the unchanged APK code.
All inputs are synthetic. No actual vehicle keys or accounts are accessed.
"""
from __future__ import annotations

import hashlib
import io
import json
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "artifacts/ninebot-ble-cast-20261115/raw/tools/python"))
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
                                UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X30,
                                UC_ARM64_REG_PC, UC_ARM64_REG_SP, UC_ARM64_REG_TPIDR_EL0)

LIB = ROOT / "artifacts/ninebot-ble-cast-20261115/raw/native/libnbcrypto.so"
EXPECTED_ELF_SHA256 = "21e594e8d8dedeb5cc04942a763cab7b88f4686980bcc543be9cf3cf344b9ca2"
REGS = [UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4]
STATE, STACK, TLS, STUB, STOP = 0x10000000, 0x20000000, 0x30000000, 0x40000000, 0x50000000


class Machine:
    def __init__(self, blob):
        elf = ELFFile(io.BytesIO(blob))
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        loads = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
        end = max(s["p_vaddr"] + s["p_memsz"] for s in loads)
        self.uc.mem_map(0, (end + 0xfff) & ~0xfff)
        for segment in loads:
            self.uc.mem_write(segment["p_vaddr"], segment.data())
        for base, size in ((STATE, 0x10000), (STACK, 0x20000), (TLS, 0x1000), (STUB, 0x10000), (STOP, 0x1000)):
            self.uc.mem_map(base, size)
        self.uc.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
        self.symbols = {s.name: int(s["st_value"]) for s in elf.get_section_by_name(".dynsym").iter_symbols()}
        self.stubs = {}
        for section in elf.iter_sections():
            if section["sh_type"] != "SHT_RELA":
                continue
            symbols = elf.get_section(section["sh_link"])
            for reloc in section.iter_relocations():
                kind, offset = reloc["r_info_type"], reloc["r_offset"]
                if kind == 1027:
                    address = reloc["r_addend"]
                elif kind in (1025, 1026):
                    sym = symbols.get_symbol(reloc["r_info_sym"])
                    address = sym["st_value"]
                    if not address:
                        address = STUB + len(self.stubs) * 16
                        self.stubs[address] = sym.name
                else:
                    continue
                self.uc.mem_write(offset, struct.pack("<Q", address))
        self.calls = []
        self.uc.hook_add(UC_HOOK_CODE, self.hook)

    def hook(self, uc, pc, size, _):
        if pc == STOP:
            uc.emu_stop()
            return
        name = self.stubs.get(pc)
        if name is None:
            return
        args = [uc.reg_read(reg) for reg in REGS]
        self.calls.append(name)
        if name in ("memcpy", "memmove", "__memcpy_chk", "__memmove_chk"):
            uc.mem_write(args[0], bytes(uc.mem_read(args[1], args[2])))
            value = args[0]
        elif name in ("memset", "__memset_chk"):
            uc.mem_write(args[0], bytes([args[1] & 255]) * args[2])
            value = args[0]
        elif name in ("memcmp", "bcmp"):
            left, right = bytes(uc.mem_read(args[0], args[2])), bytes(uc.mem_read(args[1], args[2]))
            value = 0 if left == right else 1
        else:
            raise AssertionError(f"unimplemented external import {name}")
        uc.reg_write(UC_ARM64_REG_X0, value)
        uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_X30))

    def encrypt(self, row, null_key2=False):
        key1 = bytes.fromhex(row["key1"])[:16].ljust(16, b"\0")
        key2 = bytes.fromhex(row["key2"])[:16].ljust(16, b"\0")
        auth = bytes.fromhex(row["auth"])[:16].ljust(16, b"\0")
        plain = bytes.fromhex(row["plain"])
        self.uc.mem_write(STATE + 0x100, key1)
        self.uc.mem_write(STATE + 0x200, key2)
        self.uc.mem_write(STATE, struct.pack("<QQ", STATE + 0x100, 0 if null_key2 else STATE + 0x200)
                          + auth + struct.pack("<I", max(0, row["counter"] - 1)) + bytes(4))
        self.uc.mem_write(STATE + 0x1000, plain)
        args = [STATE, STATE + 0x1000, len(plain), STATE + 0x2000]
        for reg, value in zip(REGS, args):
            self.uc.reg_write(reg, value)
        self.uc.reg_write(UC_ARM64_REG_SP, STACK + 0x1f000)
        self.uc.reg_write(UC_ARM64_REG_X30, STOP)
        self.uc.emu_start(self.symbols["_Z10nb_encryptP14crypto_param_tPhhS1_"], STOP, count=1000000)
        length = self.uc.reg_read(UC_ARM64_REG_X0)
        assert length == len(plain) + 6, (row["id"], length)
        actual = bytes(self.uc.mem_read(STATE + 0x2000, length))
        assert actual.hex() == row["wire"], (row["id"], actual.hex(), row["wire"])
        return {"fixture": row["id"], "null_key2": null_key2, "wire_bytes": length,
                "wire_sha256": hashlib.sha256(actual).hexdigest(), "libc_imports": sorted(set(self.calls))}


def main():
    blob = LIB.read_bytes()
    assert hashlib.sha256(blob).hexdigest() == EXPECTED_ELF_SHA256, "original ELF input changed"
    vectors = json.loads((HERE / "probe-vectors.json").read_text(encoding="utf-8"))["rows"]
    cases = [Machine(blob).encrypt(row) for row in vectors]
    bootstrap = [row for row in vectors if row["counter"] == 0 and row["id"].startswith("precomm-")]
    cases += [Machine(blob).encrypt(row, null_key2=True) for row in bootstrap]
    report = {"status": "passed", "ELF": str(LIB.relative_to(ROOT)).replace("\\", "/"),
              "ELF_sha256": hashlib.sha256(blob).hexdigest(),
              "entry": "_Z10nb_encryptP14crypto_param_tPhhS1_ at 0x69f80",
              "scope": "original ARM64 Encryption2 encryption vs independent Python AES literals; libc/TLS hooks only; not JNI/radio/vehicle",
              "cases_passed": len(cases), "cases": cases}
    (HERE / "original-crypto-validation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"passed {len(cases)} original ARM64 Encryption2 cases; no device connection")


if __name__ == "__main__":
    main()
