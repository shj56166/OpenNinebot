"""Package matching LGPL sources, libraries and usable Ninebot relink objects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

RELINK = '''import argparse, json, os, platform, subprocess
from pathlib import Path
p = argparse.ArgumentParser()
p.add_argument('--ndk', type=Path, required=True)
p.add_argument('--ffmpeg-lib', type=Path)
p.add_argument('--output', type=Path, default=Path('libninebot_cast-relinked.so'))
a = p.parse_args()
root = Path(__file__).resolve().parent
host = 'windows-x86_64' if os.name == 'nt' else 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
suffix = '.exe' if os.name == 'nt' else ''
tool = a.ndk / 'toolchains/llvm/prebuilt' / host / ('bin/clang++' + suffix)
libs = a.ffmpeg_lib or root / 'lib'
objects = [root / 'objects' / name for name in json.loads((root / 'objects.json').read_text(encoding='utf-8'))]
command = [str(tool), '-target', 'aarch64-none-linux-android24', '-shared', '-Wl,-soname,libninebot_cast.so',
           '-Wl,--gc-sections', '-Wl,--build-id=sha1', '-Wl,--no-rosegment', '-Wl,--exclude-libs,ALL',
           '-static-libstdc++', '-Wl,--no-undefined', '-Wl,--fatal-warnings']
command += [str(path) for path in objects]
command += [str(libs / name) for name in ('libavcodec.a', 'libswscale.a', 'libavutil.a')]
command += [str(root / 'lib/libcast_crypto.a'), '-latomic', '-llog', '-lm', '-lz', '-lc', '-o', str(a.output)]
subprocess.run(command, check=True)
print(a.output)
'''


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--object-root', type=Path, required=True,
                        help='Exact matching app/build/intermediates/cxx/Release/*/obj/local/arm64-v8a')
    parser.add_argument('--deps', type=Path, required=True)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    objects = args.object_root.resolve()
    model = objects.parents[2] / 'logs/arm64-v8a/configure_stdout.txt'
    link = [line for line in model.read_text(encoding='utf-8').splitlines() if ' -shared ' in line][-1]
    tokens = [token.strip('"') for token in re.findall(r'"[^\"]+"|\S+', link)]
    linked = [Path(token) for token in tokens if token.endswith('.o')]
    if not linked or any(re.search(r'(?i)trial|zeeho|ae8|easyconn', path.name) for path in linked):
        raise ValueError('unexpected_native_objects')
    stage = args.output / 'codec-relink'
    if stage.exists():
        raise ValueError('output_must_be_new')
    (stage / 'objects').mkdir(parents=True)
    (stage / 'lib').mkdir()
    host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    suffix = '.exe' if os.name == 'nt' else ''
    tools = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    names = []
    for source in linked:
        if source.name in names:
            raise ValueError('duplicate_object_name')
        names.append(source.name)
        target = stage / 'objects' / source.name
        shutil.copy2(source, target)
        subprocess.run([str(tools / ('llvm-strip' + suffix)), '--strip-debug', str(target)], check=True)
    (stage / 'objects.json').write_text(json.dumps(names, indent=2) + '\n', encoding='utf-8')
    for name in ('libavcodec.a', 'libswscale.a', 'libavutil.a'):
        shutil.copy2(args.deps / 'mpeg2-arm64/lib' / name, stage / 'lib' / name)
    shutil.copy2(objects / 'libcast_crypto.a', stage / 'lib/libcast_crypto.a')
    subprocess.run([str(tools / ('llvm-strip' + suffix)), '--strip-debug', str(stage / 'lib/libcast_crypto.a')], check=True)
    copies = [
        (args.deps / 'ffmpeg-7.1.1.tar.xz', '.deps/ffmpeg-7.1.1.tar.xz'),
        (args.deps / 'ffmpeg-7.1.1/config.h', 'ffmpeg-config.h'),
        (args.deps / 'ffmpeg-7.1.1/config_components.h', 'ffmpeg-components.h'),
        (project / 'scripts/prepare-codecs.py', 'scripts/prepare-codecs.py'),
        (project / 'scripts/cast-codecs.lock.json', 'scripts/cast-codecs.lock.json'),
        (project / 'app/src/main/assets/licenses/FFmpeg-LGPL-2.1.txt', 'COPYING.LGPLv2.1'),
        (project / 'app/src/main/assets/licenses/mbedTLS-LICENSE.txt', 'mbedTLS-LICENSE.txt'),
        (project / 'LICENSE', 'APPLICATION-LICENSE.txt'),
        (project / 'THIRD_PARTY_NOTICES.md', 'THIRD_PARTY_NOTICES.md'),
        (project / 'app/src/main/assets/licenses/cJSON-LICENSE.txt', 'cJSON-LICENSE.txt'),
        (project / 'app/src/main/assets/licenses/KCP-LICENSE.txt', 'KCP-LICENSE.txt'),
    ]
    for source, relative in copies:
        target = stage / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    config = (stage / 'ffmpeg-components.h').read_text(encoding='utf-8')
    if re.findall(r'^#define CONFIG_(\w+)_ENCODER 1$', config, re.M) != ['MPEG2VIDEO']:
        raise ValueError('unexpected_codec_configuration')
    (stage / 'relink.py').write_text(RELINK, encoding='utf-8')
    (stage / 'README.md').write_text('''# OpenNinebot FFmpeg source and relink kit

This kit corresponds to the exact APK SHA-256 in verification.json. FFmpeg
7.1.1 is unmodified upstream, LGPL-2.1-or-later. Its complete pinned source
archive is in .deps/ (show hidden files); configuration, codec libraries,
linkable application objects and the crypto static library are included.

With Android NDK 28.2.13676358, run:

    python relink.py --ndk <NDK>

To modify FFmpeg, extract/build once using the included standalone script,
edit .deps/ffmpeg-7.1.1/, then build again with --rebuild:

    python scripts/prepare-codecs.py --ndk <NDK>
    python scripts/prepare-codecs.py --ndk <NDK> --rebuild
    python relink.py --ndk <NDK> --ffmpeg-lib .deps/mpeg2-arm64/lib

The script preserves the exact MPEG2-only configure options and verifies
the upstream archive hash. It needs Python 3.12+, Bash, Make and a C build
environment (Git Bash and mingw32-make on Windows).

Replace lib/arm64-v8a/libninebot_cast.so in an APK copy, align and sign the
modified APK with your own key. A different signature requires a separate
installation and cannot update an installed publisher-signed application.
No publisher signing key or application activation service is required.
Reverse engineering to debug modifications of LGPL components is permitted.
Application objects are MIT; third-party components retain their licenses.
''', encoding='utf-8')
    output_library = stage / 'libninebot_cast-relinked.so'
    subprocess.run([os.sys.executable, str(stage / 'relink.py'), '--ndk', str(args.ndk), '--output', str(output_library)], check=True)
    with zipfile.ZipFile(args.apk) as apk:
        original = apk.read('lib/arm64-v8a/libninebot_cast.so')
    report = {'apkSha256': sha256(args.apk), 'originalLibrarySha256': hashlib.sha256(original).hexdigest(),
              'ffmpegSourceSha256': sha256(stage / '.deps/ffmpeg-7.1.1.tar.xz'),
              'objects': names, 'relinkSucceeded': True, 'relinkedSha256': sha256(output_library)}
    (stage / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    archive = args.output / (args.apk.stem + '-codec-source-relink.zip')
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
        for path in sorted(stage.rglob('*')):
            if path.is_file():
                package.write(path, path.relative_to(stage).as_posix())
    print(json.dumps({'file': str(archive), 'bytes': archive.stat().st_size, 'sha256': sha256(archive)}))


if __name__ == '__main__':
    main()
