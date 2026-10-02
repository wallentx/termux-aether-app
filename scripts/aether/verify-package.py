#!/usr/bin/env python3
"""Reject APKs whose Aether payload or accompanying sources do not match this checkout."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_hashes(source_archive):
    """Hash each regular member once, including archives with the manifest last."""
    hashes = {}
    manifest = None
    with gzip.open(source_archive, 'rb') as compressed:
        with tarfile.open(fileobj=compressed, mode='r|') as archive:
            for member in archive:
                if member.isdir():
                    continue
                name = member.name.removeprefix('./')
                if name in hashes:
                    raise ValueError('Duplicate source entry: ' + name)
                if not member.isfile():
                    raise ValueError('Source entry is not a regular file: ' + name)
                with archive.extractfile(member) as data:
                    if name == 'manifest.json':
                        contents = data.read()
                        manifest = json.loads(contents)
                        hashes[name] = digest(contents)
                    else:
                        checksum = hashlib.sha256()
                        for chunk in iter(lambda: data.read(1024 * 1024), b''):
                            checksum.update(chunk)
                        hashes[name] = checksum.hexdigest()
        # Tar ends before the gzip stream does. Drain it to check the gzip CRC
        # and reject truncation even when every requested tar member was read.
        while compressed.read(1024 * 1024):
            pass
    if manifest is None:
        raise ValueError('Missing source manifest')
    return manifest, hashes


def verify(apk_path, enabled, source_archive, root=Path('.')):
    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        aether_names = {n for n in names if n.startswith('assets/aether/') or '/libaether-' in n}
        if not enabled:
            if aether_names:
                raise ValueError('Aether payload present in an opted-out APK')
            return
        provenance_bytes = (root / 'app/src/aether/assets/aether/provenance.json').read_bytes()
        if apk.read('assets/aether/provenance.json') != provenance_bytes:
            raise ValueError('APK runtime provenance differs from checkout')
        provenance = json.loads(provenance_bytes)
        for name, expected in provenance.items():
            if name.endswith(('.so.1', '.so.2', '.so.6', '.so.0')):
                entry = ('lib/arm64-v8a/libaether-loader.so' if name.startswith('ld-')
                         else 'assets/aether/' + name)
                if digest(apk.read(entry)) != expected:
                    raise ValueError('Runtime binary hash mismatch: ' + name)
        for entry, built in [
            ('lib/arm64-v8a/libaether-run.so', 'jniLibs/arm64-v8a/libaether-run.so'),
            ('lib/arm64-v8a/libaether-exec.so', 'jniLibs/arm64-v8a/libaether-exec.so'),
            ('assets/aether/libaether-compat.so', 'assets/aether/libaether-compat.so'),
            ('assets/aether/aether-probe', 'probes/aether-probe'),
        ]:
            data = apk.read(entry)
            # AGP may strip JNI executables; check their ELF headers here.
            # Assets must be byte-identical to the generated payload.
            if not data.startswith(b'\x7fELF'):
                raise ValueError('Not an ELF: ' + entry)
            if entry.startswith('assets/') and data != (root / 'app/build/generated/aether' / built).read_bytes():
                raise ValueError('Generated payload mismatch: ' + entry)
        for name in ('COPYING.LIB', 'LICENSES'):
            if apk.read('assets/aether/' + name) != (root / 'app/src/aether/assets/aether' / name).read_bytes():
                raise ValueError('Missing or changed runtime license: ' + name)

    manifest, hashes = source_hashes(source_archive)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    if manifest['source_commit'] != commit:
        raise ValueError('Source archive is from a different commit')
    required = {'termux-app-source.tar.gz', 'glibc-2.44.tar.xz', 'termux-glibc-recipes-c2b00b9e.tar.gz',
                'COPYING.LIB', 'LICENSES', 'provenance.json', 'launcher.c', 'compat.c', 'exec.c',
                'system.c', 'probe.c', 'build.sh', 'source-bundle.sh', 'README.md'}
    if not required <= manifest['sha256'].keys() or not required <= hashes.keys():
        raise ValueError('Incomplete source archive')
    for name, expected in manifest['sha256'].items():
        if hashes.get(name) != expected:
            raise ValueError('Source archive hash mismatch: ' + name)
    if hashes['glibc-2.44.tar.xz'] != provenance['source_sha256']:
        raise ValueError('Wrong upstream glibc source')
    if hashes['provenance.json'] != digest(provenance_bytes):
        raise ValueError('Source provenance differs from APK')
    for name in ('launcher.c', 'compat.c', 'exec.c', 'system.c', 'probe.c', 'build.sh', 'source-bundle.sh', 'README.md'):
        if hashes[name] != digest((root / 'scripts/aether' / name).read_bytes()):
            raise ValueError('Compatibility source differs from checkout: ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--enabled', choices=('true', 'false'), required=True)
    parser.add_argument('--source-archive', type=Path, required=True)
    args = parser.parse_args()
    verify(args.apk, args.enabled == 'true', args.source_archive)
    print('Aether package/source verification passed:', args.apk)


if __name__ == '__main__':
    main()
