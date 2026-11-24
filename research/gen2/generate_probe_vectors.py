"""Independent synthetic wire fixtures using the existing Python AES oracle.

No actual vehicle credentials are used. The production/session Java codec is
never imported. --check verifies frozen literals without rewriting them.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ORACLE = HERE / "crypto_oracle.py"


def build() -> dict:
    spec = importlib.util.spec_from_file_location("independent_crypto_vectors", ORACLE)
    oracle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(oracle)
    name, auth, password = b"NB-PROBE-SYNTH", bytes(range(1, 17)), bytes(range(33, 49))
    serial = b"SN123456789012"
    rows = []

    def add(id, command, data=b"", *, source=4, target=4, response=False, index=0, counter=0, session=False):
        plain = bytes([0x5a, 0xa5, len(data), source if response else 0x3e,
                       0x3e if response else target, command, index]) + data
        rows.append(oracle.ninebot(id, password if session else name,
                                  auth if session else oracle.FW,
                                  auth, counter, plain, device_name=name))

    for target in (4, 9):
        add(f"precomm-request-{target:02x}", 0x5b, target=target)
        add(f"precomm-response-{target:02x}", 0x5b, auth + serial,
            response=True, source=target, index=1)
        add(f"auth-request-{target:02x}", 0x5d, serial, target=target, counter=2, session=True)
        add(f"auth-response-{target:02x}", 0x5d, response=True, source=target,
            index=1, counter=2, session=True)
    add("precomm-unpaired", 0x5b, auth + serial, response=True, index=0)
    add("auth-rejected", 0x5d, response=True, index=0, counter=2, session=True)
    add("read-request", 1, bytes([2]), target=1, index=0x26, counter=3, session=True)
    add("read-response", 4, bytes([123, 0]), response=True, source=1,
        index=0x26, counter=3, session=True)
    add("read-wrong-register", 4, bytes([123, 0]), response=True, source=1,
        index=0x27, counter=3, session=True)
    add("read-replay", 4, bytes([123, 0]), response=True, source=1,
        index=0x26, counter=2, session=True)
    add("read-bootstrap-downgrade", 4, bytes([123, 0]), response=True, source=1,
        index=0x26, counter=0, session=True)
    for key, content in (("small", b'{"channel":0,"codecType":0}\0'),
                         ("long", b'{"padding":"' + b'x' * 1100 + b'"}\0'),
                         ("variable", b'{"padding":"' + b'x' * 114 + b'"}\0')):
        add(f"config-{key}-begin-request", 0x82, bytes([1, 8, 0]), target=0x23,
            index=1, counter=3, session=True)
        add(f"config-{key}-begin-response", 0x82, len(content).to_bytes(4, "little"),
            response=True, source=0x23, index=1, counter=3, session=True)
        tx_counter, rx_counter = 4, 4
        for group, group_start in enumerate(range(0, len(content), 8 * 128)):
            add(f"config-{key}-group-{group}-request", 0x83, target=0x23,
                index=group, counter=tx_counter, session=True)
            tx_counter += 1
            chunks = ([content[:64], content[64:]] if key == "variable" else
                      [content[start:start + 128] for start in range(group_start, min(group_start + 1024, len(content)), 128)])
            for small_index, chunk in enumerate(chunks):
                add(f"config-{key}-group-{group}-part-{small_index}", 0x83, chunk,
                    response=True, source=0x23, index=small_index, counter=rx_counter, session=True)
                rx_counter += 1
        add(f"config-{key}-verify-request", 0x84, target=0x23, counter=tx_counter, session=True)
        add(f"config-{key}-verify-response", 0x84, hashlib.md5(content).digest(),
            response=True, source=0x23, counter=rx_counter, session=True)
        add(f"config-{key}-wrong-md5", 0x84, bytes(16), response=True, source=0x23,
            counter=rx_counter, session=True)
    add("config-variable-short-final", 0x83, bytes(64), response=True, source=0x23,
        index=1, counter=5, session=True)
    add("config-variable-padded-final", 0x83, bytes(128), response=True, source=0x23,
        index=1, counter=5, session=True)
    add("config-oversize", 0x82, (262145).to_bytes(4, "little"), response=True,
        source=0x23, index=1, counter=3, session=True)
    add("config-wrong-first-index", 0x83, bytes(128), response=True,
        source=0x23, index=1, counter=4, session=True)
    add("mode-map-request", 0x71, bytes([2]), target=0x23, counter=3, session=True)
    add("mode-map-response", 0x71, response=True, source=0x23, counter=3, session=True)
    add("mode-wrong-target", 0x71, response=True, source=0x24, counter=3, session=True)
    return {"scope": "synthetic no-radio no-real-credential Python cryptography AES oracle",
            "oracle_source": str(ORACLE.relative_to(ROOT)).replace("\\", "/"),
            "name": name.decode(), "password": password.hex(), "rows": rows}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = build()
    path = HERE / "probe-vectors.json"
    resource = ROOT / "probe/src/test/resources/probe-wire.properties"
    properties = "# Synthetic Python AES wire fixtures. No actual vehicle credentials.\n" + "\n".join(
        row["id"] + "=" + row["wire"] for row in expected["rows"]) + "\n"
    if args.check:
        assert json.loads(path.read_text(encoding="utf-8")) == expected
        assert resource.read_text(encoding="utf-8") == properties
        print(f"verified {len(expected['rows'])} independent synthetic wire fixtures")
    else:
        path.write_text(json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        resource.parent.mkdir(parents=True, exist_ok=True)
        resource.write_text(properties, encoding="utf-8")
        print(f"wrote {len(expected['rows'])} synthetic fixtures")


if __name__ == "__main__":
    main()
