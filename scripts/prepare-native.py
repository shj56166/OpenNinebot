"""Fetch and verify the pinned Ninebot native dependency sources."""
import argparse
import hashlib
from pathlib import Path
import tarfile
import urllib.request

SOURCES = [
    ('mbedtls', 'https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.4/mbedtls-3.6.4.tar.bz2', 'ec35b18a6c593cf98c3e30db8b98ff93e8940a8c4e690e66b41dfc011d678110'),
    ('cjson', 'https://github.com/DaveGamble/cJSON/archive/refs/tags/v1.7.19.tar.gz', '7fa616e3046edfa7a28a32d5f9eacfd23f92900fe1f8ccd988c1662f30454562'),
]

def main():
    p = argparse.ArgumentParser(description=__doc__)
    args = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    deps = root / '.deps'
    deps.mkdir(parents=True, exist_ok=True)
    for name, url, digest in SOURCES:
        archive = deps / (name + '.archive')
        if not archive.exists():
            archive.write_bytes(urllib.request.urlopen(url, timeout=60).read())
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise ValueError('native_source_checksum_mismatch')
        target = deps / name
        if not target.exists():
            target.mkdir()
            with tarfile.open(archive) as source:
                for item in source.getmembers():
                    parts = Path(item.name).parts[1:]
                    if not parts:
                        continue
                    path = target.joinpath(*parts).resolve()
                    if not path.is_relative_to(target.resolve()) or item.issym() or item.islnk():
                        raise ValueError('unsafe_archive_entry')
                    if item.isdir():
                        path.mkdir(parents=True, exist_ok=True)
                    elif item.isfile():
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(source.extractfile(item).read())
    print('Pinned native sources verified.')

if __name__ == '__main__':
    main()
