"""Sign the exact GitHub release APK policy; the private key is never copied."""
import argparse, base64, hashlib, json
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['apk','key','certificate-sha256','version-name','tag','output']:parser.add_argument('--'+name,required=True)
    parser.add_argument('--version-code',type=int,required=True);parser.add_argument('--minimum-version',type=int,required=True)
    args=parser.parse_args();apk=Path(args.apk)
    if not 1<=args.minimum_version<=args.version_code or len(args.certificate_sha256)!=64 or any(c not in '0123456789abcdef' for c in args.certificate_sha256):raise ValueError('invalid_release_identity')
    key=serialization.load_pem_private_key(Path(args.key).read_bytes(),None)
    if not isinstance(key,ec.EllipticCurvePrivateKey) or key.curve.name!='secp256r1':raise ValueError('P256_key_required')
    value={'schemaVersion':1,'packageName':'com.motexa.openninebot','versionCode':args.version_code,'versionName':args.version_name,'minSupportedVersionCode':args.minimum_version,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'certificateSha256':args.certificate_sha256,'url':f'https://github.com/shj56166/OpenNinebot/releases/download/{args.tag}/{apk.name}'}
    payload=json.dumps(value,separators=(',',':')).encode();encode=lambda b:base64.urlsafe_b64encode(b).decode().rstrip('=')
    result={'signedPayload':encode(payload),'signature':encode(key.sign(payload,ec.ECDSA(hashes.SHA256())))}
    output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print('Signed update policy:',output)
if __name__=='__main__':main()
