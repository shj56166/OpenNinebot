"""Independent synthetic counterexamples for evidence attribution after capture gaps.

This imports the real HCI decoder; it neither edits it nor accesses a device.
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path
import struct

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCE = ROOT / "tools/ninebot-ble-test/scripts/analyze-hci.py"
spec = importlib.util.spec_from_file_location("review_actual_hci", SOURCE)
hci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hci)


def acl(att: bytes, boundary=2):
    payload = struct.pack("<HH", len(att), 4) + att
    return b"\x02" + struct.pack("<HH", 1 | (boundary << 12), len(payload)) + payload


def pending_discovery_gap():
    d = hci.Decoder()
    d.feed(1, 0, 0, False, 0, acl(bytes.fromhex("080100ffff0328")))
    d.feed(2, 0, 1, False, 0, acl(bytes.fromhex("09070200040300002a")))
    d.feed(3, 0, 0, True, 0, b"\x02")  # next ReadByType request was not fully captured
    d.feed(4, 0, 1, False, 0, acl(bytes.fromhex("090706000409003412")))
    link = d.history[0]
    return {"id": "discovery-request-context-must-not-survive-gap",
            "expected": "no characteristic mapping at handle9 from unmatched response",
            "passed": 9 not in link["characteristics"],
            "observed_characteristics": link["characteristics"]}


def pending_mtu_gap():
    d = hci.Decoder()
    d.feed(1, 0, 0, False, 0, acl(bytes.fromhex("02f700")))
    d.feed(2, 0, 1, False, 0, acl(bytes.fromhex("03f700")))
    d.feed(3, 0, 0, True, 0, b"\x02")
    d.feed(4, 0, 1, False, 0, acl(bytes.fromhex("03b900")))
    event = d.events[-1]
    return {"id": "consumed-mtu-request-must-not-match-unpaired-response",
            "expected": "no actualAttMtu annotation on unpaired response after missing request",
            "passed": "actualAttMtu" not in event,
            "last_event": event}


def malformed_acl_gap():
    d = hci.Decoder()
    d.feed(1, 0, 0, False, 0, bytes.fromhex("02012005000300040002"))
    # Declares four data bytes but contains only one. The HCI record itself is
    # full length; only its ACL header is inconsistent.
    d.feed(2, 0, 0, False, 0, bytes.fromhex("0201100400aa"))
    d.feed(3, 0, 0, False, 0, bytes.fromhex("0201100200f700"))
    return {"id": "invalid-acl-record-must-invalidate-partial-l2cap",
            "expected": "no reconstructed ATT across rejected ACL record on same handle/direction",
            "passed": d.counts["att_pdus"] == 0,
            "counts": dict(d.counts)}


def main():
    cases = [pending_discovery_gap(), pending_mtu_gap(), malformed_acl_gap()]
    report = {"scope": "synthetic independent review; no actual HCI or vehicle",
              "source": str(SOURCE.relative_to(ROOT)).replace("\\", "/"),
              "source_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
              "cases": cases, "failures": sum(not case["passed"] for case in cases)}
    (HERE / "hci-review-validation.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report['failures']:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
