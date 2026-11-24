"""Original SDK common framing/cipher, synthetic data and no network/radio.

Executes the frozen ARM64 ELF's message serializers and built-in crypto backend.
Only Rust memory/runtime helpers are hooked, never framing, SHA1, AES or MAC.
This does not yet establish that a particular handshake selects these functions.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct

import emulate_fast_auth as f
import emulate_secret_boundary as e
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def machine(raw: bytes):
    m, state = f.runtime(raw)

    def extra(m, address, _size, _user):
        if address in (0x42406c,0x29993c,0x2999ac):  # Runtime drops only.
            e.ret(m)
        elif address in (0x6373c0,0x373b64):  # Log formatting, not protocol serialization.
            output=e.reg(m,8)
            for off,value in ((0,0),(8,1),(16,0)):e.putq(m,output+off,value)
            e.ret(m)
        elif address==0x35fe40:e.ret(m)  # SDK diagnostic log sink.
        elif address == 0x636094:  # Vec<u8>::extend_from_slice.
            vec, source, count = (e.reg(m, i) for i in range(3))
            capacity, pointer, length = (e.q(m, vec + i * 8) for i in range(3))
            if capacity < length + count:
                capacity = max(8, capacity * 2, length + count)
                pointer_new = state['bump']
                state['bump'] += (capacity + 15) & ~15
                assert state['bump'] < f.HEAP + 0x100000
                if length:
                    m.mem_write(pointer_new, bytes(m.mem_read(pointer, length)))
                pointer = pointer_new
                e.putq(m, vec, capacity)
                e.putq(m, vec + 8, pointer)
            if count:
                m.mem_write(pointer + length, bytes(m.mem_read(source, count)))
            e.putq(m, vec + 16, length + count)
            e.ret(m)
    m.hook_add(UC_HOOK_CODE, extra)
    return m, state


def dispatch_v23_unestablished(raw,payload):
    """Execute actual protocol discriminant 3 dispatcher with cipher flag=0."""
    m,state=machine(raw)
    profile,message,data=e.MEM+0x3000,e.MEM+0x4000,e.MEM+0x5000
    e.putq(m,profile,3)
    m.mem_write(profile+0x40,b'\0')
    m.mem_write(data,payload or b'\0')
    for off,value in ((0,len(payload)),(8,data),(16,len(payload)),(24,e.MASK)):
        e.putq(m,message+off,value)
    m.mem_write(message+0x38,b'\x60\x05')
    m.mem_write(message+0x36,b'\0\0')
    m.reg_write(UC_ARM64_REG_X0,profile)
    m.reg_write(UC_ARM64_REG_X1,message)
    m.reg_write(UC_ARM64_REG_X8,e.RESULT)
    m.emu_start(0x389150,e.STOP+4,count=3000000)
    assert state['stopped']
    pointer,length=e.q(m,e.RESULT+8),e.q(m,e.RESULT+16)
    result=bytes(m.mem_read(pointer,length))
    plain=b'\x5a\xa5'+bytes([len(payload),0x3e,0x60,5,0])+payload
    expected=plain+((~sum(plain[2:]))&65535).to_bytes(2,'little')
    assert result==expected,(result.hex(),expected.hex())
    return {'payload_hex':payload.hex(),'wire_hex':result.hex(),
            'discriminant':3,'cipher_enabled':False,'plaintext_checksum_matches':True}


def common_cipher(raw, plaintext, key1, key2, auth, counter=0, *, decrypt=False):
    m, state = machine(raw)
    context, input_ptr, output_ptr = e.MEM+0x3000, e.MEM+0x4000, e.MEM+0x8000
    key1_ptr, key2_ptr, auth_ptr = e.MEM+0x5000, e.MEM+0x5100, e.MEM+0x5200
    m.mem_write(key1_ptr, key1)
    m.mem_write(key2_ptr, key2)
    m.mem_write(auth_ptr, auth)
    m.mem_write(input_ptr, plaintext)
    for offset, value in ((0x28, key1_ptr), (0x30, len(key1)),
                          (0x40, key2_ptr), (0x48, len(key2)),
                          (0x58, auth_ptr), (0x60, len(auth)),
                          (0x68, counter),
                          (0x80, key1_ptr), (0x88, len(key1)),
                          (0x98, key2_ptr), (0xa0, len(key2)),
                          (0xb0, auth_ptr), (0xb8, len(auth)), (0xc0, 0)):
        e.putq(m, context+offset, value)
    for i, value in enumerate((context, input_ptr, len(plaintext), output_ptr, 4096)):
        m.reg_write(UC_ARM64_REG_X0+i, value)
    target = 0x424448 if decrypt else 0x424b44
    m.emu_start(target, e.STOP+4, count=15000000)
    assert state['stopped'], hex(m.reg_read(UC_ARM64_REG_PC))
    length = e.reg(m, 0)
    if length >= (1 << 31):
        return {'error': (length&0xffffffff)-(1 << 32)}
    assert length <= 4096, length
    return {'bytes_hex': bytes(m.mem_read(output_ptr, length)).hex(),
            'tx_counter': struct.unpack('<I', m.mem_read(context+0x68, 4))[0],
            'rx_counter': struct.unpack('<I', m.mem_read(context+0xc0, 4))[0]}


def independent_cipher(plain, key1, key2, auth, counter):
    """Independent Python hashlib/AES oracle; no original code or production C++."""
    def pad(value):return value[:16].ljust(16,b'\0')
    key=hashlib.sha1(pad(key1)+pad(key2)).digest()[:16]
    def aes(value):
        instance=Cipher(algorithms.AES(key),modes.ECB()).encryptor()
        return instance.update(value)+instance.finalize()
    def xor(a,b):return bytes(x^y for x,y in zip(a,b))
    header_size=4 if plain[:2]==b'\xaa\x55' else 3
    header,body=plain[:header_size],plain[header_size:]
    if counter==0:
        mask=aes(bytes.fromhex('97cfb802844143de56002b3b34780a5d'))
        encrypted=bytes(value^mask[i%16] for i,value in enumerate(body))
        tail=b'\0\0'+((~sum(body))&65535).to_bytes(2,'little')+b'\0\0'
    else:
        nonce=counter.to_bytes(4,'big')+pad(auth)[:8]
        # The common Rust backend uses a 16-bit body length, unlike the old
        # Java helper's low-byte-only B0 field at the 255/256 boundary.
        state=aes(b'\x59'+nonce+b'\0'+len(body).to_bytes(2,'big'))
        state=aes(xor(state,header.ljust(16,b'\0')))
        for offset in range(0,len(body),16):
            state=aes(xor(state,body[offset:offset+16].ljust(16,b'\0')))
        a=lambda index:b'\x01'+nonce+b'\0\0'+bytes([index])
        encrypted=b''.join(xor(body[offset:offset+16],aes(a(offset//16+1)))
                           for offset in range(0,len(body),16))
        tail=xor(state[:4],aes(a(0))[:4])+(counter&65535).to_bytes(2,'big')
    return header+encrypted+tail


def serialize(raw, payload, source, command, index, aa55=False):
    m, state = machine(raw)
    message = e.MEM+0x3000
    m.mem_write(e.MEM+0x4000, payload or b'\0')
    for off, value in ((0, len(payload)), (8, e.MEM+0x4000), (16, len(payload)), (24, e.MASK)):
        e.putq(m, message+off, value)
    m.mem_write(message+0x38, bytes([source, command]))
    m.mem_write(message+0x36, struct.pack('<H', index))
    m.reg_write(UC_ARM64_REG_X0, message)
    m.reg_write(UC_ARM64_REG_X8, e.RESULT)
    m.emu_start(0x5a4288 if aa55 else 0x5a4e94, e.STOP+4, count=1000000)
    assert state['stopped']
    # Serialize the converted object in a second fresh register frame.
    m.reg_write(UC_ARM64_REG_SP, e.STACK+0x1f000)
    m.reg_write(UC_ARM64_REG_X30, e.STOP)
    m.reg_write(UC_ARM64_REG_X0, e.RESULT)
    m.reg_write(UC_ARM64_REG_X8, e.MEM+0x6000)
    state['stopped'] = False
    m.emu_start(0x5a307c if aa55 else 0x5a2f1c, e.STOP+4, count=1000000)
    assert state['stopped']
    pointer, length = e.q(m,e.MEM+0x6008), e.q(m,e.MEM+0x6010)
    return bytes(m.mem_read(pointer, length))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true')
    args=parser.parse_args()
    raw=e.LIB.read_bytes()
    assert hashlib.sha256(raw).hexdigest()==e.EXPECTED
    key1=b'NB-V3-SYNTHETIC'.ljust(16,b'\0')
    key2=bytes(range(16))
    auth=bytes(range(16))
    payload=bytes.fromhex('131601f37a03a806ace1016b64a4009966fad06e1700a1cd02080001020304050607')
    cases=[]
    dispatch_cases=[dispatch_v23_unestablished(raw,p) for p in (b'',payload,bytes(range(64)))]
    for aa55 in (False,True):
        for data,index in ((b'',0),(payload,5),(bytes(range(255)),255)):
            plain=serialize(raw,data,0x60,5,index,aa55)
            expected=(b'\xaa\x55'+len(data).to_bytes(2,'little')+bytes([0x3e,0x60,5])+index.to_bytes(2,'little')+data
                      if aa55 else b'\x5a\xa5'+bytes([len(data),0x3e,0x60,5,index&255])+data)
            assert plain==expected,(plain.hex(),expected.hex())
            for counter in (0,1,255,65534):
                result=common_cipher(raw,plain,key1,key2,auth,counter)
                expected_wire=independent_cipher(plain,key1,key2,auth,counter+1 if counter else 0)
                assert result['bytes_hex']==expected_wire.hex(),(result['bytes_hex'],expected_wire.hex())
                decoded=common_cipher(raw,expected_wire,key1,key2,auth,decrypt=True)
                assert decoded.get('bytes_hex')==plain.hex(),decoded
                invalid=bytearray(expected_wire)
                invalid[-3]^=1
                rejected=common_cipher(raw,bytes(invalid),key1,key2,auth,decrypt=True)
                assert rejected.get('error',0)!=0 or rejected.get('bytes_hex')!=plain.hex(),rejected
                cases.append({'aa55':aa55,'plaintext_hex':plain.hex(),'key1_hex':key1.hex(),
                              'key2_hex':key2.hex(),'auth_hex':auth.hex(),'initial_counter':counter,
                              'original_cipher':result,'python_aes_oracle_matches':True,
                              'original_decrypt':decoded,'tampered_tail_rejected':rejected})
    report={'ELF_sha256':e.EXPECTED,'scope':'Original common serializers and built-in cipher only; synthetic inputs. Not full Gen3 authentication or vehicle acceptance.',
            'cases_passed':len(cases)+len(dispatch_cases),'cases':cases,
            'unestablished_v23_dispatch':dispatch_cases}
    if args.write:
        (HERE/'v3-common-wire-validation.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
