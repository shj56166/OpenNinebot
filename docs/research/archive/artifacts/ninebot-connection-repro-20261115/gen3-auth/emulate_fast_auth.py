"""Compare original ARM64 Gen3 fast-auth crypto/TLV with independent references.

Synthetic data only. Allocation, memcpy/memset and destruction are OS/runtime
hooks; all AES/CCM instructions remain those of the frozen official ARM64 ELF.
"""
from pathlib import Path
import hashlib, json, struct
import emulate_secret_boundary as e
from unicorn import UC_HOOK_CODE
from unicorn.arm64_const import *
from cryptography.hazmat.primitives.ciphers.aead import AESCCM

ROOT = Path(__file__).resolve().parent
HEAP = 0x40000000


def runtime(raw, key=b'\0'*16):
    m = e.machine(raw, key)
    m.mem_map(HEAP, 0x100000)
    state = {'bump': HEAP, 'seen': set(), 'stopped': False, 'allocations':{}}

    def allocate(n):
        assert 0 <= n <= 0x10000, n
        result = state['bump']
        state['bump'] += (max(n, 1)+15)&~15
        assert state['bump'] < HEAP+0x100000
        state['allocations'][result]=n
        return result

    def grow(vec, required):
        cap, ptr, length = (e.q(m, vec+i*8) for i in range(3))
        if required <= cap:
            return
        newcap = max(8, cap*2, required)
        newptr = allocate(newcap)
        if length: m.mem_write(newptr, bytes(m.mem_read(ptr, length)))
        e.putq(m, vec, newcap); e.putq(m, vec+8, newptr)

    def hook(m, a, size, user):
        if a in (0x37cd04, 0x5a0f48, 0x329fd0, 0x38dc74, 0x39a140, 0x423fb0):
            state['seen'].add(hex(a))
        if a == 0x636c2c:
            n = e.reg(m, 0); ptr = allocate(n); out = e.reg(m, 8)
            for off, value in ((0,0),(8,n),(16,ptr)): e.putq(m,out+off,value)
            e.ret(m)
        elif a == 0x635fe0:
            m.reg_write(UC_ARM64_REG_X0, 0); m.reg_write(UC_ARM64_REG_X1, 1); e.ret(m)
        elif a == 0x636d74:
            grow(e.reg(m,0), e.q(m,e.reg(m,0)+16)+e.reg(m,1)); e.ret(m)
        elif a == 0x636a70:
            grow(e.reg(m,0), e.q(m,e.reg(m,0)+16)+1); e.ret(m)
        elif a in (0x6477c0, 0x647840):
            dst, src, n = (e.reg(m,i) for i in range(3))
            if n: m.mem_write(dst, bytes(m.mem_read(src,n)))
            e.ret(m,dst)
        elif a == 0x647810:
            dst, value, n = (e.reg(m,i) for i in range(3))
            if n: m.mem_write(dst, bytes([value&255])*n)
            e.ret(m,dst)
        elif a==0x647a70: e.ret(m,allocate(e.reg(m,0)))
        elif a==0x647a90: e.ret(m)
        elif a==0x647b20:
            old,n=e.reg(m,0),e.reg(m,1); ptr=allocate(n)
            size=min(state['allocations'][old],n)
            if size: m.mem_write(ptr,bytes(m.mem_read(old,size)))
            e.ret(m,ptr)
        elif a==0x647b30:
            n=e.reg(m,0)*e.reg(m,1); ptr=allocate(n)
            if n:m.mem_write(ptr,b'\0'*n)
            e.ret(m,ptr)
        elif a in (0x288f2c, 0x29e1dc): e.ret(m)
        elif a == e.STOP:
            state['stopped'] = True; m.emu_stop()
        elif 0x647700 <= a < 0x649000:
            raise RuntimeError(f'unhandled external {a:#x} LR={m.reg_read(UC_ARM64_REG_X30):#x}')
    m.hook_add(UC_HOOK_CODE, hook)
    return m, state


def original_encrypt(raw, key, nonce, plaintext, aad):
    m, state = runtime(raw,key)
    textptr, aadptr = e.MEM+0x4000, e.MEM+0x6000
    m.mem_write(textptr, plaintext or b'\0'); m.mem_write(aadptr, aad or b'\0')
    args = [e.KEY,len(key),int.from_bytes(nonce,'little'),textptr,len(plaintext),aadptr,len(aad)]
    for i,v in enumerate(args): m.reg_write(UC_ARM64_REG_X0+i,v)
    m.reg_write(UC_ARM64_REG_X8,e.RESULT)
    m.emu_start(0x37cd04,e.STOP+4,count=3000000)
    assert state['stopped'], hex(m.reg_read(UC_ARM64_REG_PC))
    discriminant = e.q(m,e.RESULT)
    assert discriminant == e.MASK, (discriminant, [e.q(m,e.RESULT+i*8) for i in range(4)])
    ptr,n = e.q(m,e.RESULT+16), e.q(m,e.RESULT+24)
    result = bytes(m.mem_read(ptr,n))
    reference = AESCCM(key,tag_length=16).encrypt(nonce,plaintext,aad)
    assert result == reference, (result.hex(),reference.hex())
    return {'plaintext_hex':plaintext.hex(),'aad_hex':aad.hex(),'nonce_hex':nonce.hex(),
            'key_hex':key.hex(),'ciphertext_and_tag_hex':result.hex(),
            'reference_matches':True,'original_functions_seen':sorted(state['seen'])}


def reference_tlv(payload, type_width=1, length_width=1):
    """Deliberately independent, minimal reference for the observed decoder."""
    pos=0; entries=[]
    while pos+type_width+length_width <= len(payload):
        tag=int.from_bytes(payload[pos:pos+type_width],'big'); pos+=type_width
        length=int.from_bytes(payload[pos:pos+length_width],'little'); pos+=length_width
        if pos+length > len(payload): break
        entries.append((tag,payload[pos:pos+length])); pos+=length
    return entries


def original_tlv(raw, payload, type_width=1, length_width=1):
    m,state=runtime(raw); seen=[]
    m.mem_write(e.MEM+0x4000,payload or b'\0')
    def hook(m,a,size,user):
        if a==0x5e4de4:
            m.reg_write(UC_ARM64_REG_X0,0x12345678); m.reg_write(UC_ARM64_REG_X1,0x87654321); e.ret(m)
        elif a==0x355fb8:
            tag=e.reg(m,1); vec=e.reg(m,2)
            seen.append((tag,bytes(m.mem_read(e.q(m,vec+8),e.q(m,vec+16)))))
            e.putq(m,e.reg(m,8),-1); e.ret(m)
    m.hook_add(UC_HOOK_CODE,hook)
    for i,v in enumerate([e.MEM+0x4000,len(payload),type_width,length_width]): m.reg_write(UC_ARM64_REG_X0+i,v)
    m.reg_write(UC_ARM64_REG_X8,e.RESULT)
    m.emu_start(0x37ce98,e.STOP+4,count=100000)
    assert state['stopped']
    expected=reference_tlv(payload,type_width,length_width)
    assert seen==expected,(seen,expected)
    return {'input_hex':payload.hex(),'type_width':type_width,'length_width':length_width,
            'entries_at_original_map_insert':[{'tag':tag,'value_hex':v.hex()} for tag,v in seen],
            'reference_matches':True}


def original_challenge(raw,key,nonce,seconds):
    m,state=runtime(raw,key); rng=e.MEM+0x7000
    e.putq(m,rng,2); e.putq(m,rng+0x10,int.from_bytes(nonce,'little')); e.putq(m,rng+0x150,0)
    def hook(m,a,size,user):
        if a==0x617830:
            m.reg_write(UC_ARM64_REG_X0,seconds); m.reg_write(UC_ARM64_REG_X1,0); e.ret(m)
        elif a==0x59a148: e.ret(m,rng)
    m.hook_add(UC_HOOK_CODE,hook)
    m.reg_write(UC_ARM64_REG_X0,e.KEY);m.reg_write(UC_ARM64_REG_X1,len(key));m.reg_write(UC_ARM64_REG_X8,e.RESULT)
    m.emu_start(0x383df8,e.STOP+4,count=3000000)
    assert state['stopped'],hex(m.reg_read(UC_ARM64_REG_PC))
    assert e.q(m,e.RESULT)!=e.MASK
    payload=bytes(m.mem_read(e.q(m,e.RESULT+8),e.q(m,e.RESULT+16)))
    plaintext=b'\x0c\x04'+struct.pack('<I',seconds&0xffffffff)
    blob=AESCCM(key,tag_length=16).encrypt(nonce,plaintext,b'')
    expected=b'\x13'+bytes([len(blob)])+blob+b'\x02\x08'+nonce
    assert payload==expected,(payload.hex(),expected.hex())
    return {'key_hex':key.hex(),'nonce_hex':nonce.hex(),'unix_seconds':seconds,
            'plaintext_tlv_hex':plaintext.hex(),'payload_hex':payload.hex(),
            'reference_matches':True,'original_functions_seen':sorted(state['seen'])}


def main():
    raw=e.LIB.read_bytes(); assert hashlib.sha256(raw).hexdigest()==e.EXPECTED
    cases=[]
    for n in (0,1,6,15,16,17,31,32,33,255):
        for aad in (b'',b'synthetic aad'):
            cases.append(original_encrypt(raw,bytes(range(16)),bytes(range(8)),bytes(i%256 for i in range(n)),aad))
    challenge_cases=[]
    for seconds in (0,1,1700000000,0xffffffff,0x100000000,0x1122334455):
        for nonce in (bytes(range(8)),b'\xff'*8):
            challenge_cases.append(original_challenge(raw,bytes(range(16)),nonce,seconds))
    tlv_inputs=[b'',b'\x0f',b'\x0f\x01',b'\x0f\x01\x00',b'\x0f\x00',b'\x0f\x02\x00\x01',
                b'\x0f\x01\x00\x14\x02AB',b'\x0f\x01\x00\x14\x02A',
                b'\x0f\x01\x00\x14',b'\x0f\x01\x00\x0f\x01\x04',
                b'\xff\x03ABC',b'\x00\x00']
    tlv_cases=[original_tlv(raw,p) for p in tlv_inputs]
    tlv_cases.extend(original_tlv(raw,p,2,2) for p in (b'\x12\x34\x03\x00ABC',b'\x12\x34\x00\x01'+b'A'*256,b'\x12\x34\x03\x00AB'))
    report={'status':'passed','ELF_sha256':e.EXPECTED,'cases_passed':len(cases)+len(challenge_cases)+len(tlv_cases),
            'crypto_cases':len(cases),'complete_challenge_builder_cases':len(challenge_cases),'tlv_decoder_cases':len(tlv_cases),
            'engine':'Unicorn 2.1.4; original ARM64 instructions',
            'conclusion':'AES-128-CCM; nonce 8 bytes; tag 16 bytes; ciphertext followed by tag',
            'scope':'Original 37cd04 AES+CCM; complete 383df8 challenge builder (clock source and RNG state hooked, buffered RNG index=0 only; initialization/reseed untested); original 37ce98 TLV parser (map insertion captured). Widths (2,2) prove generic parser behavior, not a vehicle schema. Memory/runtime hooks. Synthetic inputs only. No BLE/cloud/vehicle execution.',
            'cases':cases,'challenge_cases':challenge_cases,'tlv_cases':tlv_cases}
    (ROOT/'fast-auth-crypto-validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if not k.endswith('cases')}))

if __name__=='__main__': main()
