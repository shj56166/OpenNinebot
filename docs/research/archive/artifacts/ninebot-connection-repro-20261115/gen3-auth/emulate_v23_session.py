"""Execute original V2_3 constructor, framing and fast-auth state boundaries.

All material is synthetic. Only allocation, diagnostics, clocks, async delivery
and external persistence are eligible runtime hooks. No BLE/cloud requests.
The individual boundaries here are not a vehicle acceptance test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import struct

import emulate_v3_wire as w
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *

e = w.e
HERE = w.HERE


def invoke(m, state, address, values=(), result=e.RESULT):
    m.reg_write(UC_ARM64_REG_SP, e.STACK + 0x1f000)
    m.reg_write(UC_ARM64_REG_X30, e.STOP)
    for i, value in enumerate(values):
        m.reg_write(UC_ARM64_REG_X0 + i, value)
    m.reg_write(UC_ARM64_REG_X8, result)
    state['stopped'] = False
    m.emu_start(address, e.STOP + 4, count=6000000)
    assert state['stopped'], hex(m.reg_read(UC_ARM64_REG_PC))


def runtime(raw):
    m, state = w.machine(raw)
    state['errors'] = []

    def hook(m, address, _size, _user):
        if address in (0x3537b0, 0x35346c):  # Diagnostic sinks.
            e.ret(m)
        elif address in (0x276f58, 0x374040, 0x5a5ff4, 0x5a606c):
            # Error objects/diagnostics, after the real validation branch.
            state['errors'].append(hex(address))
            e.ret(m, e.MEM + 0xf000)
        elif address == 0x5a6fac:  # Error object drop.
            e.ret(m)
        elif address == 0x5e4de4:  # Deterministic HashMap seed.
            m.reg_write(UC_ARM64_REG_X0, 0x12345678)
            m.reg_write(UC_ARM64_REG_X1, 0x87654321)
            e.ret(m)
    m.hook_add(UC_HOOK_CODE, hook)
    return m, state


def constructor(raw, version):
    m, state = runtime(raw)
    invoke(m, state, 0x377188, (e.SN, len(e.SN_TEXT), version, e.KEY, 0, e.KEY, 0))
    return {'version_input': version, 'protocol_discriminant': e.q(m, e.RESULT),
            'flags_hex': bytes(m.mem_read(e.RESULT + 0x40, 3)).hex()}


def message_wire(raw, payload, board, group, index):
    """Real message constructor -> protocol dispatcher, no serializer hook."""
    m, state = runtime(raw)
    profile, message, data = e.MEM + 0x3000, e.MEM + 0x4000, e.MEM + 0x5000
    m.mem_write(data, payload or b'\0')
    e.putq(m, profile, 3)
    m.mem_write(profile + 0x40, b'\0')
    invoke(m, state, 0x5a3d44, (board, group, index, data, len(payload)), message)
    invoke(m, state, 0x389150, (profile, message))
    wire = bytes(m.mem_read(e.q(m, e.RESULT + 8), e.q(m, e.RESULT + 16)))
    expected = b'\x5a\xa5' + bytes((len(payload), 0x3e, board, group, index)) + payload
    expected += ((~sum(expected[2:])) & 65535).to_bytes(2, 'little')
    assert wire == expected, (wire.hex(), expected.hex())
    return {'target_board': board, 'command_group': group, 'command_index': index,
            'payload_hex': payload.hex(), 'wire_hex': wire.hex(),
            'independent_plain_frame_matches': True}


def receive(raw, wire):
    m, state = runtime(raw)
    profile, vec, data = e.MEM + 0x3000, e.MEM + 0x4000, e.MEM + 0x5000
    e.putq(m, profile, 3)
    m.mem_write(profile + 0x40, b'\0')
    m.mem_write(data, wire or b'\0')
    for off, value in ((0, len(wire)), (8, data), (16, len(wire))):
        e.putq(m, vec + off, value)
    invoke(m, state, 0x389a74, (profile, vec))
    rejected = e.q(m, e.RESULT) == e.MASK - 1
    result = {'wire_hex': wire.hex(), 'rejected': rejected}
    if not rejected:
        capacity = e.q(m, e.RESULT)
        result.update(target_board=bytes(m.mem_read(e.RESULT + 0x38, 1))[0],
                      command_group=bytes(m.mem_read(e.RESULT + 0x39, 1))[0],
                      command_index=struct.unpack('<H', m.mem_read(e.RESULT + 0x36, 2))[0],
                      payload_hex='' if capacity == e.MASK else bytes(m.mem_read(
                          e.q(m, e.RESULT + 8), e.q(m, e.RESULT + 16))).hex())
    return result


def enable_cipher(raw, discriminant, enabled=True):
    m, state = runtime(raw)
    future, connection, protocol_arc = e.MEM + 0x3000, e.MEM + 0x4000, e.MEM + 0x5000
    e.putq(m, future, connection)
    m.mem_write(future + 8, bytes((0, int(enabled))))
    e.putq(m, connection + 0x18, protocol_arc)
    e.putq(m, protocol_arc + 0x20, discriminant)
    invoke(m, state, 0x2ec9d8, (future,))
    flag = bytes(m.mem_read(protocol_arc + 0x60, 1))[0]
    assert flag == int(enabled and discriminant in (2, 3))
    return {'discriminant': discriminant, 'enable_argument': enabled, 'cipher_flag': flag}


def sequence_start(raw, tx, rx):
    m, state = runtime(raw)
    context = e.MEM + 0x3000
    e.putq(m, context + 0x68, tx)
    e.putq(m, context + 0xc0, rx)
    invoke(m, state, 0x425b08, (context,))
    actual = [struct.unpack('<I', m.mem_read(context + off, 4))[0] for off in (0x68, 0xc0)]
    assert actual == [1, 1]
    return {'before': [tx, rx], 'after': actual}


def fast_reply_parse(raw, payload, board=4, group=0x60, index=6):
    """Original listener from metadata dispatch to the key-state branch.

    The listener's async input has already been delivered; stack/future fields
    are supplied at that boundary. Original TLV, HashMap and UTF8 run unchanged.
    Stops before success/error side effects; this is not complete authentication.
    """
    m, state = runtime(raw)
    future, pair, connection, fsm = [e.MEM + x for x in (0x3000, 0x4000, 0x5000, 0x6000)]
    for off, value in ((0, len(payload)), (8, e.MEM + 0x9000), (16, len(payload)),
                       (24, e.MASK), (0x70, pair)):
        e.putq(m, future + off, value)
    m.mem_write(e.MEM + 0x9000, payload or b'\0')
    m.mem_write(future + 0x38, bytes((board, group)))
    m.mem_write(future + 0x36, index.to_bytes(2, 'little'))
    e.putq(m, pair, connection)
    e.putq(m, connection + 0x60, fsm)
    outcome = {}

    def stop(m, address, _size, _user):
        if address == 0x2e5b28:
            outcome['key_state'] = bytes(m.mem_read(future + 0x1a9, 1))[0]
            outcome['sn_utf8'] = bytes(m.mem_read(e.q(m, future + 0x170),
                                                 e.q(m, future + 0x178))).decode('utf-8')
            m.emu_stop()
        elif address in (0x2e542c, 0x2e61f4, 0x2e6210):
            outcome['rejected_at'] = hex(address)
            m.emu_stop()
    m.hook_add(UC_HOOK_CODE, stop)
    sp = e.STACK + 0x1e000
    m.reg_write(UC_ARM64_REG_SP, sp)
    m.reg_write(UC_ARM64_REG_X29, sp + 0xdc0)
    m.reg_write(UC_ARM64_REG_X19, future)
    m.reg_write(UC_ARM64_REG_X27, sp + 0x80)
    m.emu_start(0x2e5368, e.STOP + 4, count=6000000)
    assert outcome, hex(m.reg_read(UC_ARM64_REG_PC))
    return dict(payload_hex=payload.hex(), board=board, group=group, index=index, **outcome)


def distribution_payload(raw, device_enc_hex, aad, nonce_hex):
    m, state = runtime(raw)
    args = []
    for index, value in enumerate((device_enc_hex.encode(), aad, nonce_hex.encode())):
        vec, buf = e.MEM + 0x3000 + 0x100 * index, e.MEM + 0x5000 + 0x1000 * index
        m.mem_write(buf, value or b'\0')
        for off, item in ((0, len(value)), (8, buf), (16, len(value))):
            e.putq(m, vec + off, item)
        args.append(vec)
    invoke(m, state, 0x38420c, args)
    assert e.q(m, e.RESULT) != e.MASK
    actual = bytes(m.mem_read(e.q(m, e.RESULT + 8), e.q(m, e.RESULT + 16)))
    expected = b''.join(bytes((tag, len(value))) + value for tag, value in (
        (0x10, bytes.fromhex(device_enc_hex)), (0x17, aad), (2, bytes.fromhex(nonce_hex))))
    assert actual == expected, (actual.hex(), expected.hex())
    return {'device_enc_hex': device_enc_hex, 'aad_hex': aad.hex(), 'nonce_hex': nonce_hex,
            'payload_hex': actual.hex(), 'independent_tlv_matches': True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    raw = e.LIB.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == e.EXPECTED
    constructors = [constructor(raw, version) for version in (0, 1, 2, 3, 4)]
    assert constructors[3]['protocol_discriminant'] == 3
    assert all(x['flags_hex'] == '000000' for x in constructors)
    messages = [message_wire(raw, payload, board, 0x60, index)
                for board in (4, 9, 0x23) for index, payload in (
                    (1, bytes.fromhex('010104020400010203')),
                    (3, bytes.fromhex('100241421702434402020001')),
                    (5, bytes.fromhex('131601f37a03a806ace1016b64a4009966fad06e1700a1cd02080001020304050607')))]
    receives = []
    for key_state in range(5):
        payload = bytes((15, 1, key_state, 20, 3)) + b'ABC'
        plain = bytes((0x5a, 0xa5, len(payload), 4, 0x3e, 0x60, 6)) + payload
        wire = plain + ((~sum(plain[2:])) & 65535).to_bytes(2, 'little')
        received = receive(raw, wire)
        assert not received['rejected'] and received['command_group'] == 0x60
        assert received['command_index'] == 6 and received['payload_hex'] == payload.hex()
        receives.append(received)
        bad = bytearray(wire)
        bad[-1] ^= 1
        received = receive(raw, bytes(bad))
        assert received['rejected']
        receives.append(received)
    replies = [fast_reply_parse(raw, bytes((15, 1, key_state, 20, 3)) + b'ABC')
               for key_state in range(5)]
    for x in replies:
        assert x['key_state'] in range(5) and x['sn_utf8'] == 'ABC'
    for payload in (b'', b'\x0f\x00\x14\x03ABC', b'\x0f\x02\0\0\x14\x03ABC',
                    b'\x0f\x01\0', b'\x0f\x01\0\x14\x01\xff'):
        result = fast_reply_parse(raw, payload)
        assert 'rejected_at' in result
        replies.append(result)
    for group, index in ((0x61, 6), (0x60, 5), (0x60, 7)):
        result = fast_reply_parse(raw, b'\x0f\x01\0\x14\x03ABC', group=group, index=index)
        assert 'rejected_at' in result
        replies.append(result)
    flags = [enable_cipher(raw, version) for version in range(5)]
    counters = [sequence_start(raw, tx, rx) for tx, rx in ((0, 0), (100, 200))]
    distributions = [distribution_payload(raw, key.hex(), aad, nonce.hex()) for key, aad, nonce in (
        (b'AB', b'CD', b'\0\1'), (bytes(range(22)), b'', bytes(range(8))),
        (bytes(range(64)), b'synthetic-aad', bytes(range(16))))]
    business = []
    key1 = b'NB-V3-SYNTHETIC'.ljust(16, b'\0')
    key2 = bytes(range(16))
    for incoming in (False, True):
        plain = bytes.fromhex('5aa503') + (b'\x04\x3e' if incoming else b'\x3e\x04') + bytes.fromhex('7100010203')
        encoded = w.common_cipher(raw, plain, key1, key2, key2, 1)
        assert encoded['bytes_hex'] == w.independent_cipher(plain, key1, key2, key2, 2).hex()
        business.append({'incoming': incoming, 'key1_hex': key1.hex(), 'key2_hex': key2.hex(),
                         'plaintext_hex': plain.hex(), 'wire_hex': encoded['bytes_hex']})
    result = {'ELF_sha256': e.EXPECTED, 'scope': 'Synthetic original constructor, message/frame, receive checksum, listener TLV/UTF8 segment, cipher enable/counter and 6003 payload boundaries. Not full authentication, cloud or vehicle acceptance.',
              'cases_passed': sum(map(len, (constructors, messages, receives, replies, flags, counters, distributions, business))),
              'constructors': constructors, 'messages': messages, 'receives': receives,
              'fast_reply_parser': replies, 'cipher_enable': flags, 'sequence_start': counters,
              'distribution_payload': distributions, 'business_frames': business}
    if args.write:
        (HERE / 'v23-session-boundary-validation.json').write_text(
            json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if not isinstance(value, list)}))


if __name__ == '__main__':
    main()
