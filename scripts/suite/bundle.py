#!/usr/bin/env python3
"""Assemble already-verified release assets into one offline Aether suite."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile


def one(directory, pattern):
    matches = list(directory.glob(pattern))
    if len(matches) != 1:
        raise ValueError(f'Expected one {pattern}, found {len(matches)}')
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('assets', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--commits', type=Path, required=True, help='JSON mapping the four repository names to commit SHAs')
    args = parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version):
        parser.error('version must be major.minor.patch')
    major, minor, patch = map(int, args.version.split('.'))
    version_code = major * 1000000 + minor * 1000 + patch
    if minor > 999 or patch > 999 or not 1 <= version_code <= 2100000000:
        parser.error('version exceeds the Android version-code allocation')
    commits = json.loads(args.commits.read_text())
    expected = {'termux-aether-app', 'termux-aether-api', 'termux-aether-api-package', 'termux-aether-exec-package'}
    if set(commits) != expected or any(not re.fullmatch('[a-f0-9]{40}', v) for v in commits.values()):
        parser.error('commits must identify exact source revisions of all four components')
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='aether-suite-') as tmp:
        stage = Path(tmp)
        for pattern, name in [
            ('termux-aether-app_*.apk', 'termux-aether-app.apk'),
            ('termux-aether-api_*.apk', 'termux-aether-api.apk'),
            (f'termux-aether-exec-{args.version}-1-aarch64.pkg.tar.zst', None),
            (f'termux-aether-api-{args.version}-1-aarch64.pkg.tar.xz', None),
            ('aether-source-*.tar.gz', None),
        ]:
            source = one(args.assets, pattern)
            shutil.copy2(source, stage / (name or source.name))
        exec_package = one(stage, '*.pkg.tar.zst')
        with (stage / 'recovery-preload.so').open('wb') as out:
            subprocess.run(['tar', '-xOf', str(exec_package),
                'data/data/com.termux/files/usr/lib/libtermux-exec-direct-ld-preload.so'], stdout=out, check=True)
        shutil.copy2(Path(__file__).with_name('install.sh'), stage / 'install.sh')
        (stage / 'release.json').write_text(json.dumps({'version': args.version, 'commits': commits,
            'android_version_code': version_code, 'architecture': 'aarch64',
            'application_ids': ['com.termux', 'com.termux.api']}, indent=2) + '\n')
        sums = []
        for file in sorted(stage.iterdir()):
            with file.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            sums.append(f'{digest}  {file.name}\n')
        (stage / 'SHA256SUMS').write_text(''.join(sums))
        archive = args.output / 'termux-aether-suite-aarch64.tar.xz'
        with tarfile.open(archive, 'w:xz') as bundle:
            for file in sorted(stage.iterdir()):
                bundle.add(file, arcname=file.name)
        with archive.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        archive.with_suffix('.xz.sha256').write_text(f'{digest}  {archive.name}\n')
        print(archive)


if __name__ == '__main__':
    main()
