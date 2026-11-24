"""Audit local delivery; synthetic tests and real vehicle validation stay separate."""
from collections import Counter
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
TOOL = ROOT / 'tools/ninebot-ble-test'
DOC = ROOT / 'docs/research/九号全仪表连接复现与分阶段发包方案-20261115.md'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def checked(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='strict', **kwargs)
    if result.returncode:
        raise AssertionError(f'Command failed ({result.returncode}): {command}\n{result.stdout}\n{result.stderr}')
    return result


def main():
    apk = TOOL / 'app/build/outputs/apk/debug/app-debug.apk'
    assert apk.is_file(), 'Build APK first'
    app_inputs = [p for p in (TOOL / 'app/src').rglob('*') if p.is_file()]
    app_inputs += [TOOL / name for name in ('app/build.gradle', 'build.gradle', 'settings.gradle', 'gradle.properties')]
    assert apk.stat().st_mtime >= max(p.stat().st_mtime for p in app_inputs), 'APK is older than current app inputs'
    with zipfile.ZipFile(apk) as archive:
        native = [name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
        assert not native, 'Probe must contain no official or other native SO'
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'AppData/Local/Android/Sdk')))
    aapt = sorted((sdk / 'build-tools').glob('*/aapt2.exe'))[-1]
    permissions = checked([str(aapt), 'dump', 'permissions', str(apk)]).stdout
    assert 'android.permission.INTERNET' not in permissions
    badging = checked([str(aapt), 'dump', 'badging', str(apk)]).stdout
    assert "package: name='com.motexa.ninebotbletest'" in badging
    assert re.search(r"(?:minSdkVersion|sdkVersion):'26'", badging) and "targetSdkVersion:'35'" in badging
    assert "launchable-activity: name='com.motexa.ninebotbletest.ProbeActivity'" in badging

    suites = []
    for path in sorted((TOOL / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        row = {name: int(suite.attrib.get(name, 0)) for name in ('tests', 'failures', 'errors', 'skipped')}
        row['suite'] = suite.attrib['name']
        assert row['failures'] == row['errors'] == row['skipped'] == 0
        suites.append(row)
    assert len(suites) >= 4 and sum(row['tests'] for row in suites) >= 37
    lint_file = TOOL / 'app/build/reports/lint-results-debug.xml'
    lint_root = ET.parse(lint_file).getroot()
    lint_counts = Counter(issue.attrib['severity'] for issue in lint_root.findall('issue'))
    assert not any(issue.attrib['severity'] in ('Error', 'Fatal') for issue in lint_root.findall('issue'))

    python_result = checked([sys.executable, '-X', 'utf8', '-m', 'unittest', 'discover',
                             '-s', str(TOOL / 'scripts'), '-p', 'test_*.py', '-v'])
    test_count = re.search(r'Ran (\d+) tests', python_result.stderr)
    assert test_count and 'OK' in python_result.stderr
    (HERE / 'python-tests.log').write_text(python_result.stdout + python_result.stderr, encoding='utf-8')
    checked([sys.executable, '-X', 'utf8', str(HERE / 'gen2/review_hci_boundaries.py')])
    boundary = read_json(HERE / 'gen2/hci-review-validation.json')
    assert boundary['failures'] == 0

    clip_path = TOOL / 'app/src/main/assets/probe-240x320.json'
    clip = read_json(clip_path)
    encoded = b''.join(base64.b64decode(packet['base64'], validate=True) for packet in clip['packets'])
    assert hashlib.sha256(encoded).hexdigest() == clip['elementaryStreamSha256']
    ffmpeg = shutil.which('ffmpeg')
    assert ffmpeg, 'FFmpeg is needed to independently decode the built-in clip'
    decoded = subprocess.run([ffmpeg, '-v', 'error', '-f', 'mpegvideo', '-i', 'pipe:0', '-f', 'framemd5', 'pipe:1'],
                              input=encoded, capture_output=True)
    assert decoded.returncode == 0 and not decoded.stderr.strip(), decoded.stderr.decode('utf-8', errors='replace')
    hashes = [line for line in decoded.stdout.decode('utf-8').splitlines() if line and not line.startswith('#')]
    assert len(hashes) == len(clip['packets']) == 10
    distinct = len({line.split(',')[-1].strip() for line in hashes})
    assert distinct > 1, 'Test pattern must change across frames'

    source_catalog = HERE / 'official-connect/model-catalog.json'
    app_catalog = TOOL / 'app/src/main/assets/model-catalog.json'
    assert sha(source_catalog) == sha(app_catalog)
    catalog = read_json(source_catalog)
    assert catalog['model_count'] == len(catalog['models']) == 131
    assert sum(model['command_catalog_available'] for model in catalog['models']) == 3

    official = read_json(HERE / 'official-connect/original-method-validation.json')
    gen2 = read_json(HERE / 'gen2/session-validation.json')
    assert official['passed'] and official['scenarios'] == 120 and official['assertions'] == 277
    assert gen2['status'] == 'passed' and gen2['junit_tests'] == 27
    for item in gen2['sources']:
        assert sha(ROOT / item['path']) == item['sha256'], item['path']
    gen3 = {}
    for name in ('secret-boundary-validation.json', 'hex-key-validation.json', 'fast-auth-crypto-validation.json'):
        report = read_json(HERE / 'gen3-auth' / name)
        assert report.get('status') == 'passed', name
        gen3[name] = {'cases': report.get('cases_passed'), 'scope': report.get('scope')}
    gen3_manifest = read_json(HERE / 'gen3-auth/validation-manifest.json')
    for item in gen3_manifest['files']:
        assert sha(ROOT / item['path']) == item['sha256'], item['path']
    assert gen3_manifest['original_execution_total'] == sum(item['cases'] for item in gen3.values()) == 334
    gen3_cross = {}
    for name in ('gen3-cross-validation.json', 'fast-auth-map-cross-validation.json'):
        report = read_json(HERE / 'official-connect' / name)
        assert report['status'] == 'passed'
        gen3_cross[name] = {'cases': report['cases_passed'], 'scope': report['scope']}

    documents = [DOC, TOOL / 'README.md', HERE / 'README.md']
    links = 0
    for document in documents:
        text = document.read_text(encoding='utf-8', errors='strict')
        for target in re.findall(r'(?<!!)\[[^\]]*\]\(([^)\n]+)\)', text):
            target = target.strip('<>').split('#')[0]
            if not target or re.match(r'^[a-z]+://', target):
                continue
            # Generated audit files are written below, after all checks succeed.
            path = (document.parent / target).resolve()
            if path not in (HERE / 'validation-summary.json', HERE / 'input-inventory.json'):
                assert path.exists(), f'{document.name}: {target}'
            links += 1

    apk_hash = sha(apk)
    delivered = ROOT / 'outputs/ninebot-ble-test-0.1.0-20261115.apk'
    delivered.parent.mkdir(parents=True, exist_ok=True)
    if delivered.exists() and sha(delivered) != apk_hash:
        delivered = delivered.with_stem(delivered.stem + '-' + apk_hash[:12])
    if not delivered.exists():
        shutil.copy2(apk, delivered)
    assert sha(delivered) == apk_hash
    summary = {
        'status': 'passed', 'researchDate': '2026-11-15',
        'scope': 'Local APK/static/original-method/synthetic verification only; no phone, vehicle, cloud login or actual credential.',
        'realVehicleValidated': False,
        'apk': {'path': delivered.relative_to(ROOT).as_posix(), 'bytes': delivered.stat().st_size,
                'sha256': apk_hash, 'nativeLibraries': native, 'internetPermission': False,
                'package': 'com.motexa.ninebotbletest', 'version': '0.1.0', 'minSdk': 26, 'targetSdk': 35},
        'androidJUnit': {'tests': sum(row['tests'] for row in suites), 'suites': suites},
        'androidLint': dict(lint_counts),
        'pythonUnitTests': {'tests': int(test_count[1]), 'log': 'python-tests.log'},
        'independentHciBoundaryCases': len(boundary['cases']),
        'officialOriginalJava': {'scenarios': official['scenarios'], 'assertions': official['assertions']},
        'gen2': {key: gen2[key] for key in ('junit_tests', 'independent_python_wire_vectors', 'original_arm64_encryption_cases')},
        'gen3OriginalArm64': gen3,
        'gen3IndependentCrossReview': gen3_cross,
        'modelCatalog': {'models': 131, 'decodedCommandTables': 3, 'sha256': sha(source_catalog)},
        'mpeg2Clip': {'sha256': sha(clip_path), 'decodedFrames': len(hashes), 'distinctFrameHashes': distinct,
                      'width': clip['width'], 'height': clip['height'], 'fps': clip['fps'], 'rawBytes': len(encoded)},
        'documentationLinksChecked': links,
        'countingNote': 'Reruns and cross-reviews do not add coverage; Session tests also appear within Android JUnit.'
    }
    (HERE / 'validation-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    inventory = []
    excluded = {'raw', 'classes', 'stubs', 'build', '.gradle', '__pycache__', 'captures'}
    for base in (HERE, TOOL):
        for path in sorted(base.rglob('*')):
            if not path.is_file() or excluded.intersection(path.relative_to(base).parts):
                continue
            if path.name in ('input-inventory.json', 'validation-summary.json', '.gitignore'):
                continue
            inventory.append({'path': path.relative_to(ROOT).as_posix(), 'bytes': path.stat().st_size, 'sha256': sha(path)})
    inventory.append({'path': DOC.relative_to(ROOT).as_posix(), 'bytes': DOC.stat().st_size, 'sha256': sha(DOC)})
    (HERE / 'input-inventory.json').write_text(json.dumps({'files': inventory, 'count': len(inventory)}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': 'passed', 'apk': summary['apk'], 'androidTests': summary['androidJUnit']['tests'],
                      'pythonTests': summary['pythonUnitTests']['tests'], 'inventoryFiles': len(inventory)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
