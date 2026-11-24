"""Inventory selected native bridge exports and RTP strings; no code execution."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parent
LIB = ROOT.parents[2] / "tmp/native/lib/arm64-v8a/librust_lib.so"
data = LIB.read_bytes()
with LIB.open("rb") as handle:
    elf = ELFFile(handle)
    symbols = elf.get_section_by_name(".dynsym")
    exports = [{"address": hex(int(symbol["st_value"])), "name": symbol.name}
               for symbol in symbols.iter_symbols()
               if re.search(r"Rtp|Rtcp|Mtu|BleWrite|Sdk|Config", symbol.name)]
    matches = [{"offset": hex(match.start()), "text": match.group().decode("ascii")}
               for match in re.finditer(rb"[ -~]{6,}", data)
               if re.search(rb"rtp|Rtp|rtcp|Rtcp|RTP", match.group())]
    hits = [{"offset": hex(match.start()),
             "context": data[max(0, match.start()-70):match.end()+110].decode("ascii", errors="backslashreplace")}
            for match in re.finditer(rb"RtpHandler|rtp_header_size|rtp_specific_header_size|payload_type|rtp_standard|write_rtp|write_rtcp|RTP_PAYLOAD|RTP_HEADER|rtp_payload", data)]
    report = {"library_sha256": hashlib.sha256(data).hexdigest(), "exports": exports,
              "strings": matches, "rtp_hits": hits,
              "scope": "String presence does not prove call-path reachability"}
(ROOT / "bridge-index.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
for export in exports:
    print(export["address"], export["name"])
for hit in hits:
    print(hit["offset"], hit["context"])
