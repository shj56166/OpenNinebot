"""Build the MPEG2-only Android codec from the pinned, verified FFmpeg source."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import re

VERSION = '7.1.1'
URL = f'https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz'


def msys(path):
    # LLVM selects ranlib behavior from the symlink name in argv[0].
    value = str(Path(path).absolute()).replace('\\', '/')
    return '/' + value[0].lower() + value[2:] if len(value) > 1 and value[1] == ':' else value


def normalize_build_configuration(source, command, toolchain, prefix):
    """Keep reproducible configure options without publishing machine paths."""
    header = source / 'config.h'
    body = header.read_text(encoding='utf-8')
    configuration = ' '.join(command[1:]).replace(msys(toolchain), '<Android-NDK>').replace(msys(prefix), '<codec-prefix>')
    body = re.sub(r'^#define FFMPEG_CONFIGURATION .*$', lambda _: '#define FFMPEG_CONFIGURATION ' + json.dumps(configuration), body, flags=re.M)
    body = body.replace(msys(prefix), '<codec-prefix>')
    header.write_text(body, encoding='utf-8', newline='\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--ndk', required=True)
    p.add_argument('--rebuild', action='store_true', help='Reconfigure and rebuild an existing extracted source tree')
    a = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    deps = root / '.deps'
    deps.mkdir(parents=True, exist_ok=True)
    archive = deps / f'ffmpeg-{VERSION}.tar.xz'
    if not archive.exists():
        archive.write_bytes(urllib.request.urlopen(URL, timeout=60).read())
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    lock = Path(__file__).with_name('cast-codecs.lock.json')
    expected = json.loads(lock.read_text(encoding='utf-8'))['sha256']
    if digest != expected:
        raise ValueError('ffmpeg_source_checksum_mismatch')
    source = deps / f'ffmpeg-{VERSION}'
    if not source.exists():
        with tarfile.open(archive) as tar:
            tar.extractall(deps, filter='data')
    prefix = deps / 'mpeg2-arm64'
    if not a.rebuild and all((prefix / 'lib' / name).is_file() for name in ('libavcodec.a', 'libavutil.a', 'libswscale.a')):
        print('Verified MPEG2-only codec build already available.')
        return
    bash = Path('C:/Program Files/Git/bin/bash.exe') if os.name=='nt' else Path(shutil.which('bash') or '/bin/bash')
    make = shutil.which('mingw32-make' if os.name=='nt' else 'make')
    if not make and os.name == 'nt':
        candidates = list((Path(os.environ['LOCALAPPDATA']) / 'Microsoft/WinGet/Packages').glob('BrechtSanders.WinLibs*/mingw64/bin/mingw32-make.exe'))
        make = str(candidates[0]) if candidates else None
    if not make or not bash.is_file():
        raise RuntimeError('git_bash_and_make_required')
    toolchain = Path(a.ndk) / ('toolchains/llvm/prebuilt/windows-x86_64' if os.name=='nt' else 'toolchains/llvm/prebuilt/linux-x86_64')
    executable = '.exe' if os.name == 'nt' else ''
    cc = f'{msys(toolchain / ("bin/clang" + executable))} --target=aarch64-linux-android24'
    cmd = [
        './configure', '--target-os=android', '--arch=aarch64', '--enable-cross-compile',
        '--disable-everything', '--disable-autodetect', '--disable-programs', '--disable-doc',
        '--disable-network', '--disable-asm', '--disable-shared', '--enable-static', '--enable-pic',
        '--enable-avcodec', '--enable-avutil', '--enable-swscale', '--enable-encoder=mpeg2video',
        '--disable-avdevice', '--disable-avfilter', '--disable-avformat', '--disable-swresample',
        '--disable-iconv', '--disable-debug', '--disable-vulkan', '--disable-xlib',
        '--cc=' + cc, '--ar=' + msys(toolchain / ('bin/llvm-ar' + executable)),
        '--ranlib=' + msys(toolchain / ('bin/llvm-ranlib' + executable)),
        '--strip=' + msys(toolchain / ('bin/llvm-strip' + executable)), '--prefix=' + msys(prefix),
    ]
    import shlex
    configure_script = '\n'.join([
        'set -eu', 'cd ' + shlex.quote(msys(source)),
        ' '.join(shlex.quote(v) for v in cmd),
    ])
    script = '\n'.join([
        'set -eu', 'cd ' + shlex.quote(msys(source)),
        shlex.quote(msys(make)) + ' -j4 SHELL=/bin/sh',
        shlex.quote(msys(make)) + ' install SHELL=/bin/sh',
    ])
    build_script = deps / 'build-mpeg2.sh'
    build_script.write_text(configure_script + '\n', encoding='utf-8', newline='\n')
    subprocess.run([str(bash), msys(build_script)], check=True)
    normalize_build_configuration(source, cmd, toolchain, prefix)
    build_script.write_text(script + '\n', encoding='utf-8', newline='\n')
    subprocess.run([str(bash), msys(build_script)], check=True)
    print('Built MPEG2-only arm64 codec; SHA-256:', digest)


if __name__ == '__main__':
    main()
