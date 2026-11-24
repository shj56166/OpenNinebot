"""Run the APK's dex2jar g71/sd bytecode with inert socket/owner stubs.

Only o31, j31, and fe are stubbed. g71 and sd must load from the converted APK.
No socket is bound and no packet is put on a network.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', type=Path, default=HERE.parent / 'raw/ninebot-pro-061-dex2jar.jar')
parser.add_argument('--apk', type=Path, default=HERE.parent / 'raw/ninebot-pro-v0.6.1.apk')
parser.add_argument('--jbr', type=Path, default=Path(r'C:\Program Files\Android\Android Studio\jbr\bin'))
args = parser.parse_args()
JAR, APK, JBR = args.jar, args.apk, args.jbr
BUILD = HERE / 'build'
BUILD.mkdir(exist_ok=True)

STUBS = {
    'o31.java': 'public final class o31 { public volatile boolean g; public java.net.DatagramSocket h; }\n',
    'j31.java': 'public final class j31 { public final o31 a; public final long[] b,c; public j31(o31 a,long[] b,long[] c){this.a=a;this.b=b;this.c=c;} }\n',
    'fe.java': 'public final class fe { public static void s(String m){throw new IllegalArgumentException(m);} }\n',
}
for name, content in STUBS.items():
    (BUILD / name).write_text(content, encoding='utf-8')

cp = str(BUILD) + os.pathsep + str(JAR)
sources = [str(BUILD / name) for name in STUBS] + [str(HERE / 'JPEGVectors.java')]
comp = subprocess.run([str(JBR / 'javac.exe'), '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-classpath', str(JAR), '-d', str(BUILD), *sources], capture_output=True, text=True, encoding='utf-8', errors='replace')
(HERE / 'javac.log').write_text(comp.stdout + comp.stderr, encoding='utf-8')
comp.check_returncode()
run = subprocess.run([str(JBR / 'java.exe'), '-noverify', '-Dfile.encoding=UTF-8', '-classpath', cp, 'JPEGVectors'], capture_output=True, text=True, encoding='utf-8', errors='replace')
(HERE / 'java.log').write_text(run.stdout + run.stderr, encoding='utf-8')
run.check_returncode()
rows = [json.loads(line) for line in run.stdout.splitlines() if line.startswith('{')]
with zipfile.ZipFile(JAR) as jar:
    class_hashes = {x: hashlib.sha256(jar.read(x)).hexdigest() for x in ['g71.class', 'sd.class']}
report = {
    'apk_sha256': hashlib.sha256(APK.read_bytes()).hexdigest(),
    'dex2jar_sha256': hashlib.sha256(JAR.read_bytes()).hexdigest(),
    'class_sha256': class_hashes,
    'method': 'Original dex2jar g71 and sd; inert o31/j31/fe stubs only; no network IO',
    'jvm_flag': '-noverify needed because dex2jar classfiles have no required stack-map frames; no bytecode rewrite',
    'assertions': rows,
    'exit_code': run.returncode,
}
(HERE / 'validation.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
for cls in ['g71', 'sd']:
    dis = subprocess.run([str(JBR / 'javap.exe'), '-J-Dfile.encoding=UTF-8', '-classpath', str(JAR), '-c', '-p', cls], capture_output=True, text=True, encoding='utf-8', errors='replace', check=True)
    (HERE / f'{cls}-javap.txt').write_text(dis.stdout, encoding='utf-8')
print(json.dumps({'status': 'passed', 'groups': len(rows), 'report': str(HERE / 'validation.json')}, ensure_ascii=False))
