#!/usr/bin/env python3
"""Build one real Pacman package from verified native packages and APK assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import time

PREFIX = 'data/data/com.termux/files/usr'
SHARE = PREFIX + '/share/termux-aether-suite'


def metadata(text):
    result = {}
    for line in text.splitlines():
        if ' = ' in line:
            key, value = line.split(' = ', 1)
            result.setdefault(key, []).append(value)
    return result


def merge_package(source, stage):
    # Zstd inputs are expanded before packaging; shipping suite uses builtin XZ.
    with tempfile.TemporaryFile() as uncompressed:
        subprocess.run(['tar', '-xOf', str(source), '.PKGINFO'], stdout=uncompressed, check=True)
        uncompressed.seek(0)
        info = metadata(uncompressed.read().decode())
    if info.get('pkgname') not in (['termux-aether-api'], ['termux-aether-exec']) or info.get('arch') != ['aarch64']:
        raise ValueError('Unexpected component package: ' + str(source))
    command = ['zstd', '-dc', str(source)] if source.name.endswith('.zst') else ['xz', '-dc', str(source)]
    with tempfile.TemporaryFile() as data:
        subprocess.run(command, stdout=data, check=True)
        data.seek(0)
        with tarfile.open(fileobj=data) as package:
            for member in package:
                if member.name in ('.PKGINFO', '.INSTALL', '.MTREE', '.BUILDINFO'):
                    continue
                name = PurePosixPath(member.name)
                if member.isdir() and member.name.rstrip('/') in ('data', 'data/data', 'data/data/com.termux', 'data/data/com.termux/files'):
                    continue
                if '..' in name.parts or not (member.name == PREFIX or member.name.startswith(PREFIX + '/')):
                    raise ValueError('Unsafe component path: ' + member.name)
                if not (member.isfile() or member.isdir() or member.issym()):
                    raise ValueError('Unsupported component entry: ' + member.name)
                if member.issym() and (PurePosixPath(member.linkname).is_absolute() or '..' in PurePosixPath(member.linkname).parts):
                    raise ValueError('Unsafe component symlink: ' + member.name)
                destination = stage / member.name
                if not member.isdir() and (destination.exists() or destination.is_symlink()):
                    raise ValueError('Overlapping component files: ' + member.name)
                package.extract(member, stage, filter='data')
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('assets', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--version', default='1000.0.0')
    parser.add_argument('--pkgrel', type=int, default=2)
    args = parser.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version) or args.pkgrel < 1:
        parser.error('Invalid version or package release')
    args.output.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parents[2]
    suite_commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    def one(pattern):
        paths = list(args.assets.glob(pattern))
        if len(paths) != 1:
            raise ValueError('Expected exactly one ' + pattern)
        return paths[0]
    with tempfile.TemporaryDirectory(prefix='aether-pacman-suite-') as tmp:
        stage = Path(tmp)
        components = [merge_package(one('termux-aether-exec-*.pkg.tar.zst'), stage),
                      merge_package(one('termux-aether-api-*.pkg.tar.xz'), stage)]
        share = stage / SHARE
        share.mkdir(parents=True)
        for name in ('termux-aether-app.apk', 'termux-aether-api.apk'):
            shutil.copy2(args.assets / name, share / name)
        source = one('aether-source-*.tar.gz')
        shutil.copy2(source, share / source.name)
        release = json.loads((args.assets / 'release.json').read_text())
        if release['version'] != args.version:
            raise ValueError('APK release manifest version mismatch')
        release.update(suite_commit=suite_commit, package_release=args.pkgrel,
                       native_packages={c['pkgname'][0]: c['pkgver'][0] for c in components})
        (share / 'release.json').write_text(json.dumps(release, indent=2) + '\n')
        for source, target in [('aether-install.py', 'aether-install'), ('aether-apks', 'aether-apks')]:
            dest = stage / PREFIX / 'bin' / target
            shutil.copy2(Path(__file__).with_name(source), dest)
            dest.chmod(0o700)
        sums = []
        for file in sorted(share.iterdir()):
            with file.open('rb') as stream:
                sums.append(f'{hashlib.file_digest(stream, "sha256").hexdigest()}  {file.name}\n')
        (share / 'SHA256SUMS').write_text(''.join(sums))
        provisions = {'termux-exec', 'termux-aether-exec', 'termux-api', 'termux-aether-api'}
        dependencies = sorted({d for c in components for d in c.get('depend', [])
                               if re.split(r'[<>=]', d)[0] not in provisions})
        info = ['pkgname = termux-aether-suite', 'pkgbase = termux-aether-suite', 'xdata = pkgtype=pkg',
                f'pkgver = 1:{args.version}-{args.pkgrel}', 'arch = aarch64',
                'pkgdesc = Termux-Aether native runtime, API commands and Android APK payloads',
                'url = https://github.com/wallentx/termux-aether-app',
                f'builddate = {int(time.time())}', 'packager = wallentx <william.allentx@gmail.com>',
                'license = GPL-3.0-or-later', 'license = Apache-2.0', 'license = MIT', 'license = LGPL-2.1-or-later',
                'size = ' + str(sum(p.stat().st_size for p in stage.rglob('*') if p.is_file() and not p.is_symlink()))]
        for c in components:
            info.append('provides = ' + c['pkgname'][0] + '=' + c['pkgver'][0])
            info.extend('provides = ' + p for p in c.get('provides', []))
        for name in sorted(provisions):
            info.extend(['conflict = ' + name, 'replaces = ' + name])
        info.extend('depend = ' + d for d in dependencies)
        info.extend('optdepend = ' + d for c in components for d in c.get('optdepend', []))
        (stage / '.PKGINFO').write_text('\n'.join(info) + '\n')
        (stage / '.INSTALL').write_text('''post_install() {
    /data/data/com.termux/files/usr/bin/termux-exec-ld-preload-lib setup -v
    printf 'Aether native suite installed. Run aether-apks api, then aether-apks app.\\n'
}
post_upgrade() { post_install; }
''')
        archive = args.output / 'termux-aether-suite-aarch64.pkg.tar.xz'
        def owner(member):
            member.uid = member.gid = 0
            member.uname = member.gname = 'root'
            return member
        with tarfile.open(archive, 'w:xz', preset=3) as package:
            for name in ('.PKGINFO', '.INSTALL', 'data'):
                package.add(stage / name, arcname=name, filter=owner)
        with archive.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        Path(str(archive) + '.sha256').write_text(f'{digest}  {archive.name}\n')
        print(archive)


if __name__ == '__main__':
    main()
