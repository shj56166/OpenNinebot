"""Offline synthetic fixture generator. Never imported by Swift tests/production.

Uses Python hashlib and cryptography's real AES implementation, independently
of the Swift/Java codec. No AES rounds or SHA compression functions here.
Run with --check to verify the committed literals without rewriting them.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

import cryptography
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


FW = bytes.fromhex("97cfb802844143de56002b3b34780a5d")
NAME = "NINEBOT-E2-TEST".encode("utf-8")
AUTH = bytes.fromhex("1032547698badcfe0123456789abcdef")
PASSWORD = bytes.fromhex("00112233445566778899aabbccddeeff")
SERIAL = b"N2TEST12345678"  # 14 bytes


def aes(key: bytes, block: bytes) -> bytes:
    encryptor = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
    return encryptor.update(block) + encryptor.finalize()


def xor(left: bytes, right: bytes) -> bytes:
    assert len(left) == len(right)
    return bytes(a ^ b for a, b in zip(left, right))


def pad16(value: bytes) -> bytes:
    return value[:16].ljust(16, b"\0")


def plain(command: int, data: bytes, *, response: bool = False, index: int = 0) -> bytes:
    return bytes([0x5A, 0xA5, len(data), 0x20 if response else 0x3E,
                  0x3E if response else 0x20, command, index]) + data


def ninebot(identifier: str, key1: bytes, key2: bytes, auth: bytes,
            counter: int, plaintext: bytes, device_name: bytes = NAME) -> dict:
    key = hashlib.sha1(pad16(key1) + pad16(key2)).digest()[:16]
    header, body = plaintext[:3], plaintext[3:]
    result = dict(id=identifier, deviceName=device_name.hex(), key1=key1.hex(), key2=key2.hex(),
                  auth=auth.hex(), counter=counter, plain=plaintext.hex(), derivedKey=key.hex())
    if counter == 0:
        stream = aes(key, FW)
        ciphertext = bytes(value ^ stream[i % 16] for i, value in enumerate(body))
        checksum = (~sum(body)) & 0xFFFF
        tail = b"\0\0" + checksum.to_bytes(2, "little") + b"\0\0"
    else:
        nonce = counter.to_bytes(4, "big") + auth[:8] + b"\0"
        a = lambda index: b"\x01" + nonce + bytes([0, index])
        b0 = b"\x59" + nonce + bytes([0, len(body) & 0xFF])
        state = aes(key, b0)
        state = aes(key, xor(state, header + bytes(13)))
        for offset in range(0, len(body), 16):
            state = aes(key, xor(state, body[offset:offset + 16].ljust(16, b"\0")))
        raw_tag = state[:4]
        a0 = aes(key, a(0))
        ciphertext = b"".join(xor(body[offset:offset + 16], aes(key, a(offset // 16 + 1))[:len(body[offset:offset + 16])])
                              for offset in range(0, len(body), 16))
        tail = xor(raw_tag, a0[:4]) + counter.to_bytes(2, "big")
        result.update(nonce=nonce.hex(), b0=b0.hex(), a0=a(0).hex(), a1=a(1).hex(), rawTag=raw_tag.hex())
    result["wire"] = (header + ciphertext + tail).hex()
    assert len(bytes.fromhex(result["wire"])) == len(plaintext) + 6
    return result

