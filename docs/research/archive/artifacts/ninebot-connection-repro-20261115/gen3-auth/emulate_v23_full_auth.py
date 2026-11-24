"""Original V2_3 full-auth message boundaries, synthetic inputs only.

This executes frozen official ARM64 instructions in Unicorn. Allocation and
diagnostics are runtime hooks; TLV/hex/JSON and cryptographic code are original.
No vehicle, Bluetooth, account or cloud is contacted.
"""
from __future__ import annotations

import argparse
import hashlib
import json

import emulate_v23_session as s
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *

e = s.e
HERE = s.HERE


def put_vec(m, address, data, pointer):
    m.mem_write(pointer, data or b'\0')
    for off, value in ((0, len(data)), (8, pointer), (16, len(data))):
        e.putq(m, address + off, value)


def challenge_payload(raw, level, nonce, public_key=b'', signature=b'', certificate=b''):
    m, state = s.runtime(raw)
    future, response, level_ptr = [e.MEM + x for x in (0x3000, 0x4000, 0x6000)]
    put_vec(m, response + 0x70, nonce.hex().encode(), e.MEM + 0x7000)
    put_vec(m, response + 0x88, public_key, e.MEM + 0x8000)
    put_vec(m, response + 0xa0, signature, e.MEM + 0x9000)
    put_vec(m, response + 0xb8, certificate, e.MEM + 0xa000)
    m.mem_write(level_ptr, level.to_bytes(4, 'little'))
    outcome = {}

    def stop(m, address, _size, _user):
        if address == 0x2f1f90:
            outcome['payload_hex'] = bytes(m.mem_read(e.q(m, future + 0x20),
                                                        e.q(m, future + 0x28))).hex()
            m.emu_stop()
    m.hook_add(UC_HOOK_CODE, stop)
    sp = e.STACK + 0x1e000
    for reg, value in ((UC_ARM64_REG_SP, sp), (UC_ARM64_REG_X29, sp + 0x160),
                       (UC_ARM64_REG_X19, future), (UC_ARM64_REG_X23, sp + 0x60),
                       (UC_ARM64_REG_X24, response), (UC_ARM64_REG_X22, level_ptr)):
        m.reg_write(reg, value)
    m.emu_start(0x2f1830, e.STOP + 4, count=6000000)
    assert outcome, hex(m.reg_read(UC_ARM64_REG_PC))
    entries = [(1, bytes((level,))), (2, nonce)]
    if level == 2:
        entries.append((8, certificate))
    elif level == 4:
        entries.extend(((3, public_key), (4, signature)))
    expected = b''.join(bytes((tag, len(value))) + value for tag, value in entries)
    assert outcome['payload_hex'] == expected.hex(), (outcome, expected.hex())
    return dict(level=level, nonce_hex=nonce.hex(), public_key_hex=public_key.hex(),
                signature_hex=signature.hex(), certificate_hex=certificate.hex(),
                independent_tlv_matches=True, **outcome)


def byte_string(raw, value):
    m, state = s.runtime(raw)
    ptr, vec, ref = e.MEM + 0x6000, e.MEM + 0x4000, e.MEM + 0x3000
    put_vec(m, vec, value, ptr)
    e.putq(m, ref, vec)
    s.invoke(m, state, 0x2bd884, (ref,))
    return bytes(m.mem_read(e.q(m, e.RESULT + 8), e.q(m, e.RESULT + 16))).decode('utf-8')


def response_request(raw, level, payload):
    m, state = s.runtime(raw)
    future, pair, inner, response, level_ptr = [e.MEM + x for x in
                                              (0x3000, 0x5000, 0x6000, 0x7000, 0x8000)]
    put_vec(m, future + 0x50, payload, e.MEM + 0x9000)
    e.putq(m, future + 0x48, pair)
    e.putq(m, pair + 8, inner)
    e.putq(m, inner + 0x38, response)
    e.putq(m, response + 0x30, level_ptr)
    e.putq(m, response + 0x38, 1)
    m.mem_write(level_ptr, level.to_bytes(4, 'little'))
    put_vec(m, response + 0x40, b'SYNTHETIC-SERIAL', e.MEM + 0xa000)
    put_vec(m, response + 0x58, b'SYNTHETIC-PN', e.MEM + 0xb000)
    outcome = {}

    def stop(m, address, _size, _user):
        if address == 0x2f4d30:
            outcome['assembled'] = True
            m.emu_stop()
        elif address in (0x2f4880, 0x2f487c, 0x2f4858):
            outcome['rejected_at'] = hex(address)
            m.emu_stop()
    m.hook_add(UC_HOOK_CODE, stop)
    sp = e.STACK + 0x1d000
    m.reg_write(UC_ARM64_REG_SP, sp)
    m.reg_write(UC_ARM64_REG_X29, sp + 0x680)
    m.reg_write(UC_ARM64_REG_X19, future)
    m.emu_start(0x2f2cd0, e.STOP + 4, count=6000000)
    assert outcome, hex(m.reg_read(UC_ARM64_REG_PC))
    if 'assembled' not in outcome:
        return dict(level=level, payload_hex=payload.hex(), **outcome)
    vec, serializer = e.MEM + 0xc000, e.MEM + 0xc100
    e.putq(m, vec, 0)
    e.putq(m, vec + 8, 1)
    e.putq(m, vec + 16, 0)
    e.putq(m, serializer, vec)
    s.invoke(m, state, 0x2ba96c, (future + 0xf0, serializer))
    serialized = bytes(m.mem_read(e.q(m, vec + 8), e.q(m, vec + 16))).decode()
    expected = {'useCase': 1, 'version': '3', 'level': [level],
                'sn': 'SYNTHETIC-SERIAL', 'pn': 'SYNTHETIC-PN',
                'nonce': '0001020304050607', 'deviceSign': '0001FEFF',
                'pkDeviceTemp': [65, 66, 67] if level > 1 else None,
                'clientCert': [67, 69, 82, 84] if level > 3 else None}
    assert json.loads(serialized) == expected
    return dict(level=level, payload_hex=payload.hex(), request=json.loads(serialized),
                request_json=serialized, **outcome)


def initial_zero_key(raw):
    m, state = s.runtime(raw)
    s.invoke(m, state, 0x473a98, (0, 16))
    actual = bytes(m.mem_read(e.q(m, e.RESULT + 8), e.q(m, e.RESULT + 16)))
    assert actual == bytes(16)
    return {'constructor': '0x473a98(0,16)', 'key_hex': actual.hex()}


def full_6006(raw, payload, snapshot):
    m, state = s.runtime(raw)
    future, pair, inner, fsm, response = [e.MEM + x for x in
                                         (0x3000, 0x5000, 0x6000, 0x7000, 0x8000)]
    ptr = e.MEM + 0x9000
    m.mem_write(ptr, payload or b'\0')
    e.putq(m, future + 0x48, pair)
    m.mem_write(future + 0x101, bytes((snapshot,)))
    put_vec(m, future + 0x90, b'SYNTHETIC-NAME', e.MEM + 0xa000)
    e.putq(m, pair + 8, inner)
    e.putq(m, inner + 0x50, fsm)
    m.mem_write(fsm + 0x10, bytes((snapshot,)))
    e.putq(m, inner + 0x40, response)
    put_vec(m, response + 0x28, bytes(range(16)).hex().encode(), e.MEM + 0xb000)
    put_vec(m, response + 0x40, b'0001', e.MEM + 0xc000)
    callback, vtable = e.MEM + 0xd000, e.MEM + 0xe000
    e.putq(m, inner + 0x20, callback)
    e.putq(m, inner + 0x28, vtable)
    e.putq(m, vtable + 0x10, 1)
    e.putq(m, vtable + 0x28, e.STOP + 0x40)
    outcome = {'events': [], 'original_state_transitions': []}
    sp = e.STACK + 0x1d000

    def stop(m, address, _size, _user):
        if address in (0x359310, 0x3298b4, 0x329cb8, 0x3299a0):
            # Diagnostic sink and external timer cancellation/scheduling.
            e.ret(m)
        elif address == 0x369c2c:
            outcome['original_state_transitions'].append(e.reg(m, 1))
        elif address == e.STOP + 0x40:
            outcome['events'].append(e.reg(m, 1))
            e.ret(m)
        elif address in (0x2f6bb8, 0x2f6190, 0x2f63a8, 0x2f5fd0, 0x2f6a40):
            outcome['stopped_at'] = hex(address)
            outcome['fsm'] = bytes(m.mem_read(fsm + 0x10, 1))[0]
            if address == 0x2f6bb8:
                vecs = {}
                for off, name in ((0, 'version'), (0x18, 'levels'), (0x30, 'sn'), (0x48, 'pn')):
                    cap, buf, length = (e.q(m, sp + 0x50 + off + 8 * i) for i in range(3))
                    if name == 'levels':
                        vecs[name] = [int.from_bytes(m.mem_read(buf + 4 * i, 4), 'little') for i in range(length)]
                    else:
                        vecs[name] = bytes(m.mem_read(buf, length)).decode()
                outcome['challenge_request_fields'] = vecs
            m.emu_stop()
    m.hook_add(UC_HOOK_CODE, stop)
    for reg, value in ((UC_ARM64_REG_SP, sp), (UC_ARM64_REG_X29, sp + 0xe10),
                       (UC_ARM64_REG_X19, future), (UC_ARM64_REG_X22, ptr),
                       (UC_ARM64_REG_X23, len(payload)), (UC_ARM64_REG_X24, len(payload)),
                       (UC_ARM64_REG_X26, sp + 0x50), (UC_ARM64_REG_X28, sp + 0x150)):
        m.reg_write(reg, value)
    m.emu_start(0x2f5d20, e.STOP + 4, count=6000000)
    assert 'stopped_at' in outcome, hex(m.reg_read(UC_ARM64_REG_PC))
    return dict(payload_hex=payload.hex(), snapshot=snapshot, **outcome)


def verification_request(raw, payload):
    m, state = s.runtime(raw)
    future, response = e.MEM + 0x3000, e.MEM + 0x5000
    data = e.MEM + 0x6000
    m.mem_write(data, payload)
    s.invoke(m, state, 0x37ce98, (data, len(payload), 1, 1), future + 0xc0)
    put_vec(m, future + 0x108, b'ABC', e.MEM + 0x7000)
    e.putq(m, future + 0x120, response + 0x10)
    put_vec(m, response + 0x40, b'0001', e.MEM + 0x8000)
    pair, inner, sdk_arc = e.MEM + 0x9000, e.MEM + 0xa000, e.MEM + 0xb000
    e.putq(m, future + 0x48, pair)
    e.putq(m, pair + 8, inner)
    e.putq(m, inner + 0x30, sdk_arc)
    sp = e.STACK + 0x1d000
    outcome = {}

    def stop(m, address, _size, _user):
        if address == 0x2f6964:
            outcome['assembled'] = True
            m.emu_stop()
    m.hook_add(UC_HOOK_CODE, stop)
    for reg, value in ((UC_ARM64_REG_SP, sp), (UC_ARM64_REG_X29, sp + 0xe10),
                       (UC_ARM64_REG_X19, future), (UC_ARM64_REG_X26, sp + 0x50),
                       (UC_ARM64_REG_X28, sp + 0x150)):
        m.reg_write(reg, value)
    m.emu_start(0x2f66ac, e.STOP + 4, count=6000000)
    assert outcome, hex(m.reg_read(UC_ARM64_REG_PC))
    vec, serializer = e.MEM + 0xc000, e.MEM + 0xc100
    e.putq(m, vec + 8, 1)
    e.putq(m, serializer, vec)
    # The serializer must not overwrite its still-live source stack.
    request = e.MEM + 0xd000
    m.mem_write(request, bytes(m.mem_read(sp + 0x150, 0x80)))
    s.invoke(m, state, 0x2baae0, (request, serializer))
    serialized = bytes(m.mem_read(e.q(m, vec + 8), e.q(m, vec + 16))).decode()
    assert json.loads(serialized) == {'useCase': 1, 'sn': 'ABC', 'pn': 'PN',
        'nonce': '0001', 'deviceEnc': '4142', 'aad': '0001FEFF'}
    return dict(payload_hex=payload.hex(), request=json.loads(serialized), request_json=serialized)


def distribution_reply(raw, payload, snapshot=7):
    m, state = s.runtime(raw)
    future, pair, inner, fsm, challenge_response, distribution = [e.MEM + x for x in
        (0x3000, 0x5000, 0x6000, 0x7000, 0x8000, 0x9000)]
    put_vec(m, future + 0x118, payload, e.MEM + 0xa000)
    e.putq(m, pair + 8, inner)
    e.putq(m, inner + 0x50, fsm)
    m.mem_write(fsm + 0x10, bytes((snapshot,)))
    e.putq(m, inner + 0x38, challenge_response)
    e.putq(m, inner + 0x40, distribution)
    put_vec(m, challenge_response + 0x40, b'ABC', e.MEM + 0xb000)
    put_vec(m, distribution + 0x28, bytes(range(16)).hex().encode(), e.MEM + 0xc000)
    sp = e.STACK + 0x1d000
    outcome = {'storage': [], 'state_transitions': []}

    def stop(m, address, _size, _user):
        if address in (0x359310, 0x369770):
            e.ret(m)
        elif address == 0x383b58:
            outcome['storage'].append({'sn': bytes(m.mem_read(e.reg(m, 0), e.reg(m, 1))).decode(),
                'key': bytes(m.mem_read(e.reg(m, 2), e.reg(m, 3))).decode(), 'next': e.reg(m, 4)})
            e.ret(m, 0)
        elif address == 0x369c2c:
            outcome['state_transitions'].append(e.reg(m, 1))
        elif address in (0x2e74f0, 0x2e738c):
            outcome['stopped_at'] = hex(address)
            outcome['fsm'] = bytes(m.mem_read(fsm + 0x10, 1))[0]
            m.emu_stop()
    m.hook_add(UC_HOOK_CODE, stop)
    for reg, value in ((UC_ARM64_REG_SP, sp), (UC_ARM64_REG_X29, sp + 0x400),
                       (UC_ARM64_REG_X19, future), (UC_ARM64_REG_X27, pair)):
        m.reg_write(reg, value)
    m.emu_start(0x2e71b8, e.STOP + 4, count=6000000)
    assert 'stopped_at' in outcome, hex(m.reg_read(UC_ARM64_REG_PC))
    return dict(payload_hex=payload.hex(), snapshot=snapshot, **outcome)


def inbound_frame(raw, index, payload):
    plain = bytes((0x5a, 0xa5, len(payload), 4, 0x3e, 0x60, index)) + payload
    wire = plain + ((~sum(plain[2:])) & 65535).to_bytes(2, 'little')
    result = s.receive(raw, wire)
    assert not result['rejected'] and result['payload_hex'] == payload.hex()
    return dict(index=index, payload_hex=payload.hex(), wire_hex=wire.hex(),
                original_receiver_accepted=True)


def button_reply(raw, snapshot):
    m, state = s.runtime(raw)
    future, pair, inner, fsm = [e.MEM + x for x in (0x3000, 0x4000, 0x5000, 0x6000)]
    e.putq(m, future + 0x60, pair)
    e.putq(m, pair + 8, inner)
    e.putq(m, inner + 0x50, fsm)
    m.mem_write(fsm + 0x10, bytes((snapshot,)))
    outcome = {}

    def stop(m, address, _size, _user):
        if address in (0x369c80, 0x2e7904):
            outcome['accepted'] = address == 0x2e7904
            outcome['fsm'] = bytes(m.mem_read(fsm + 0x10, 1))[0]
            m.emu_stop()
    m.hook_add(UC_HOOK_CODE, stop)
    for reg, value in ((UC_ARM64_REG_SP, e.STACK + 0x1d000),
                       (UC_ARM64_REG_X19, future), (UC_ARM64_REG_X8, 7)):
        m.reg_write(reg, value)
    m.emu_start(0x2e78dc, e.STOP + 4, count=6000000)
    assert outcome, hex(m.reg_read(UC_ARM64_REG_PC))
    assert outcome['accepted'] == (snapshot == 3)
    assert outcome['fsm'] == (4 if snapshot == 3 else snapshot)
    return dict(before=snapshot, **outcome)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    raw = e.LIB.read_bytes()
    assert hashlib.sha256(raw).hexdigest() == e.EXPECTED
    challenges = [challenge_payload(raw, level, nonce, bytes(range(64)),
                                    bytes(range(32)), b'synthetic-server-certificate')
                  for level in (1, 2, 4) for nonce in (bytes(range(8)), bytes(range(16)))]
    strings = [{'bytes_hex': value.hex(), 'string': byte_string(raw, value)}
               for value in (b'', b'AB', b'\0\x01\xfe\xff')]
    responses = [response_request(raw, level, bytes.fromhex(
        '07040001feff020800010203040506070603414243050443455254'))
        for level in (1, 2, 4)]
    initial = [full_6006(raw, bytes.fromhex('0f010314034142431602504e0101071901') + bytes((capability,)), 2)
               for capability in (0, 1, 2)]
    zero_key = initial_zero_key(raw)
    verify = verification_request(raw, bytes.fromhex('1602504e17040001feff18024142'))
    distributions = [distribution_reply(raw, bytes((15, 1, value))) for value in (0, 1, 4)]
    assert [x['fsm'] for x in initial] == [3, 4, 3]
    assert [x['events'] for x in initial] == [[1], [], [1]]
    assert [x['fsm'] for x in distributions] == [8, 7, 7]
    assert [len(x['storage']) for x in distributions] == [1, 0, 0]
    incoming = [inbound_frame(raw, index, bytes.fromhex(payload)) for index, payload in (
        (6, '0f010314034142431602504e010107190100'),
        (6, '0f010314034142431602504e010107190101'), (7, ''),
        (2, '07040001feff020800010203040506070603414243050443455254'),
        (4, '0f0100'), (4, '0f0101'),
        (6, '0f010014034142431602504e17040001feff18024142'))]
    outgoing = [s.message_wire(raw, bytes.fromhex(row['payload_hex']), 4, 0x60, 1)
                for row in challenges]
    for key in (bytes(16), bytes(range(16))):
        payload = s.w.f.original_challenge(raw, key, bytes(range(8)), 0)['payload_hex']
        outgoing.append(s.message_wire(raw, bytes.fromhex(payload), 4, 0x60, 5))
    outgoing.append(s.message_wire(raw, bytes.fromhex('100241421702434402020001'), 4, 0x60, 3))
    buttons = [button_reply(raw, snapshot) for snapshot in range(13)]
    result = {'ELF_sha256': e.EXPECTED, 'cases_passed': sum(map(len, (challenges, strings, responses, initial, distributions, incoming, outgoing, buttons))) + 2,
              'scope': 'Original 6001 builder, 6002 request+JSON, initial 6006 state/capability+challenge fields, 6004 status+storage+state, final 6006 verify TLVs+JSON, framing and initial key constructor. Synthetic segment boundaries; no end-to-end SDK, cloud or vehicle acceptance.',
              'challenge_payload': challenges, 'byte_string': strings, 'response_request': responses,
              'initial_zero_key': zero_key, 'full_6006_initial': initial,
              'verification_request': verify, 'distribution_reply': distributions,
              'incoming_messages': incoming, 'outgoing_messages': outgoing,
              'button_reply': buttons}
    if args.write:
        (HERE / 'v23-full-auth-boundary-validation.json').write_text(
            json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if not isinstance(value, (list, dict))}))


if __name__ == '__main__':
    main()
