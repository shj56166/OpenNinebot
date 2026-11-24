from pathlib import Path
import json,re,struct,hashlib,bisect
import native_trace as n
from capstone.arm64 import ARM64_OP_IMM,ARM64_OP_REG
ROOT=Path(__file__).resolve().parent
NEEDLES=[b'InitialChallengeSent',b'InitialChallengeReceived',b'KeyDistributionSent',b'EncryptedChallengeSent',b'HandshakeV3',b'HandshakeV2_3',b'handshake_v3',b'session_key_hex',b'tmp_session_key_hex',b'pkDeviceTemp',b'clientCert',b'deviceSign',b'pkCloudTemp',b'cloudSign',b'X-Ctx-Hash',b'InitialStarted',b'V3_2',b'Missing SN in SecretInfo',b'Invalid session key',b'initial challenge',b'key distribution',b'KeyDistribution',b'HandshakeChallengeResp',b'HandshakeResponseResp']

def string_hits():
    result=[]
    for needle in NEEDLES:
        for base,data in n.section_bytes:
            if base>=n.text_base:continue
            start=0
            while True:
                off=data.find(needle,start)
                if off<0:break
                addr=base+off
                result.append({'needle':needle.decode(),'addr':hex(addr),'context':n.read(max(base,addr-32),min(200,len(data)-max(0,off-32))).decode('utf-8','replace')})
                start=off+len(needle)
    return result

def refs(addresses):
    result=[]
    regs={}
    n.md.skipdata=True
    for ins in n.md.disasm(n.text_raw,n.text_base):
        m,op=ins.mnemonic,ins.op_str
        if m in ('adr','adrp'):
            ma=re.fullmatch(r'(x\d+), #0x([0-9a-f]+)',op)
            if ma:
                regs[ma[1]]=int(ma[2],16)
                if m=='adr' and regs[ma[1]] in addresses:
                    result.append({'instruction':hex(ins.address),'function':hex(n.function(ins.address)[0]),'target':hex(regs[ma[1]])})
        elif m=='add':
            ma=re.fullmatch(r'(x\d+), (x\d+), #0x([0-9a-f]+)',op)
            if ma and ma[2] in regs:
                addr=regs[ma[2]]+int(ma[3],16)
                regs[ma[1]]=addr
                if addr in addresses:
                    result.append({'instruction':hex(ins.address),'function':hex(n.function(ins.address)[0]),'target':hex(addr)})
        elif m in ('bl','blr','ret','b'):regs={}
    return result

if __name__=='__main__':
    hits=string_hits(); r=refs({int(h['addr'],16) for h in hits})
    report={'sha256':hashlib.sha256(n.LIB.read_bytes()).hexdigest(),'strings':hits,'xrefs':r}
    (ROOT/'auth-locations.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'strings':[{'needle':h['needle'],'addr':h['addr']} for h in hits],'xrefs':r},indent=2))
