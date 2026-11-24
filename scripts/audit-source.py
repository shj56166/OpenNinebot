"""Check the publishable tree without printing credential-like values."""
from pathlib import Path
import re
import json
import hashlib

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', '.gradle', '.deps', '.cxx', '.native-tests', 'build', '__pycache__'}
KEY = re.compile(rb'-----BEGIN (?:EC |RSA |OPENSSH )?PRIVATE KEY-----')
TOKEN = re.compile(rb'(?:github_pat_[A-Za-z0-9_]{30,}|gh[pousr]_[A-Za-z0-9]{30,})')
PRIVATE_CODE = re.compile(rb'\b(?:Ae8\w*|zeeho\w*|NativeEasyConn|vehicle_cast_authorized)\b', re.I)
PRIVATE_RESEARCH = re.compile('极核|春风'.encode('utf-8'))
PRIVATE_PROJECT = re.compile(rb'ANTby2|motexaqixing|jihe|MotexaProtocolCrypto|ios-app/|shared/vehicle-|\.\./\.\./shared/|\.\./\.\./app/android/|download\.motexa\.top|VehicleCastSessionManager|VehicleCastVideoPipeline|VehicleCastProjectionService|RideRecorderManager|RideDynamicsAnalyzer|ControllerStatusData|DeviceSessionManager|NativeDeviceSessionPlugin', re.I)
GUARDS = {'scripts/audit-source.py', 'scripts/package-codecs.py'}
failures = []
count = 0
for path in sorted(ROOT.rglob('*')):
    relative = path.relative_to(ROOT)
    if not path.is_file() or any(part in EXCLUDED for part in relative.parts):
        continue
    count += 1
    if path.suffix.lower() in {'.apk', '.so', '.pem', '.keystore', '.jks', '.dex', '.o', '.a'} or path.name == 'signing.properties':
        failures.append((str(relative), 'private_or_binary_input'))
        continue
    body = path.read_bytes()
    if KEY.search(body) or TOKEN.search(body):
        failures.append((str(relative), 'secret'))
    if relative.as_posix() not in GUARDS:
        if PRIVATE_CODE.search(body) or PRIVATE_RESEARCH.search(body):
            failures.append((str(relative), 'unrelated_product_reference'))
        if PRIVATE_PROJECT.search(body) or PRIVATE_PROJECT.search(relative.as_posix().encode()):
            failures.append((str(relative), 'private_project_reference'))
manifest = ROOT / 'SOURCE_MANIFEST.json'
if manifest.is_file():
    for name, entry in json.loads(manifest.read_text(encoding='utf-8'))['sourceFiles'].items():
        source = ROOT / name
        if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != entry['sha256']:
            failures.append((name, 'source_manifest_mismatch'))
if failures:
    for path, reason in failures:
        print(reason + ': ' + path)
    raise SystemExit(1)
print(f'Publishable source audit passed: {count} files; no private implementation, signing key or vendor binary.')
