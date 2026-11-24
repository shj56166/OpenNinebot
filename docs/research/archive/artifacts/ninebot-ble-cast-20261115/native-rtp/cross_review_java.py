"""Read-only independent integrity audit; packet/session methods run in sibling scripts."""
from pathlib import Path
import hashlib
import json
import struct
import zipfile

ROOT=Path(__file__).resolve().parent
JAVA=ROOT.parent/'java-media'
manifest=json.loads((JAVA/'evidence.json').read_text(encoding='utf-8-sig'))
checks=[]
def verify(label,actual,expected):
    checks.append({'label':label,'passed':actual==expected,'actual':actual,'expected':expected})
    if actual!=expected:
        raise AssertionError(label)
def digest(data):
    return hashlib.sha256(data).hexdigest()
apk=Path(manifest['apk']['path'])
verify('frozen APK sha256',digest(apk.read_bytes()),manifest['apk']['sha256'])
with zipfile.ZipFile(apk) as archive:
    for row in manifest['dex_files']:
        verify(row['apk_entry']+' sha256',digest(archive.read(row['apk_entry'])),row['sha256'])
for row in manifest['sources']:
    verify(row['snapshot']+' sha256',digest((JAVA/row['snapshot']).read_bytes()),row['sha256'])
for row in manifest['frozen_tools_and_converted_jars']:
    verify(row['path']+' sha256',digest((JAVA/row['path']).read_bytes()),row['sha256'])
session=json.loads((JAVA/'session-validation.json').read_text(encoding='utf-8-sig'))
queue=json.loads((JAVA/'validation.json').read_text(encoding='utf-8-sig'))
packet=bytes.fromhex(session['ble_java_vector'])
vpxcc,mpt,seq,ts,ssrc,profile,words=struct.unpack('>BBHIIHH',packet[:16])
vector={'length':len(packet),'version':vpxcc>>6,'extension_flag':bool(vpxcc&16),
        'csrc_count':vpxcc&15,'marker':bool(mpt&128),'payload_type':mpt&127,
        'sequence':seq,'timestamp':ts,'ssrc':ssrc,'extension_profile':profile,
        'extension_words':words,'extension_bytes':packet[16:20].hex(),
        'picture_type':packet[17]&7,'media_bytes':packet[20:].hex()}
verify('original packet PT',vector['payload_type'],0)
verify('original packet fixed header length',len(packet)-len(packet[20:]),20)
verify('original packet extension profile',profile,0)
verify('original packet extension word count',words,1)
verify('original packet key picture type',vector['picture_type'],1)
verify('original packet raw media retained',vector['media_bytes'],'000001657f')
result={'status':'passed','audit':'Independent rerun and frozen-evidence integrity check',
        'integrity_checks':len(checks),'checks':checks,'packet_vector_decoded':vector,
        'rerun_original_method_results':{'queue_and_split':queue,'session':session},
        'new_application_test_cases_added':0,
        'scope':'JVM execution of unchanged retained dex2jar methods; no Android encoder, BLE or vehicle execution'}
(ROOT/'cross-java-validation.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k not in ('checks','rerun_original_method_results')},indent=2))
