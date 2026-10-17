#!/data/data/com.termux/files/usr/bin/python
"""Validate/install a suite package, optionally adopting known legacy commands."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import tempfile

PREFIX = Path('/data/data/com.termux/files/usr')
LEGACY = {'termux-arch', 'termux-arch-resources', 'termux-arch-share', 'termux-arch-vm',
          'termux-capabilities', 'termux-shizuku', 'termux-virtualization', 'Æ', 'æ'}
REPLACED = {'termux-exec', 'termux-api', 'termux-aether-exec', 'termux-aether-api', 'termux-aether-suite'}


def owners(database):
    result = {}
    for directory in database.iterdir():
        if not directory.is_dir():
            continue
        desc, files = directory / 'desc', directory / 'files'
        if not desc.exists() or not files.exists():
            continue
        name = desc.read_text().split('%NAME%\n', 1)[1].split('\n', 1)[0]
        for file in files.read_text().split('%FILES%\n', 1)[-1].split('\n\n', 1)[0].splitlines():
            result[file] = name
    return result


def inspect(archive, prefix=PREFIX):
    with tarfile.open(archive, 'r:xz') as package:
        metadata = package.extractfile('.PKGINFO').read().decode()
        if 'pkgname = termux-aether-suite\n' not in metadata or 'arch = aarch64\n' not in metadata:
            raise ValueError('Expected an aarch64 termux-aether-suite Pacman package')
        paths = []
        base = str(prefix).lstrip('/') + '/'
        for member in package.getmembers():
            if member.name in ('.PKGINFO', '.INSTALL'):
                continue
            if member.isdir():
                name = member.name.rstrip('/')
                if '..' in PurePosixPath(name).parts or not (name == str(prefix).lstrip('/') or name.startswith(base) or name in ('data', 'data/data', 'data/data/com.termux', 'data/data/com.termux/files')):
                    raise ValueError('Unexpected package directory: ' + name)
                continue
            path = PurePosixPath(member.name)
            if '..' in path.parts or not member.name.startswith(base) or not (member.isfile() or member.issym()):
                raise ValueError('Unexpected package path/type: ' + member.name)
            if member.issym() and (PurePosixPath(member.linkname).is_absolute() or '..' in PurePosixPath(member.linkname).parts):
                raise ValueError('Unexpected package symlink: ' + member.name)
            paths.append(member.name)
    return paths


def conflicts(paths, ownership, prefix=PREFIX):
    existing, legacy, blocked = [], [], []
    for name in paths:
        path = Path('/' + name)
        if not path.exists() and not path.is_symlink():
            continue
        existing.append(path)
        owner = ownership.get(name)
        if owner in REPLACED:
            continue
        if owner is None and path.parent == prefix / 'bin' and path.name in LEGACY and not path.is_dir():
            legacy.append(path)
        else:
            blocked.append(f'{path} ({owner or "unowned"})')
    return existing, legacy, blocked


def checksum(archive):
    fields = Path(str(archive) + '.sha256').read_text().split()
    with archive.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if fields != [digest, archive.name]:
        raise ValueError('Package checksum mismatch; nothing installed')


def install_transaction(legacy, recovery, archive, env):
    moved = []
    try:
        for path in legacy:
            saved = recovery / ('legacy-' + path.name)
            path.rename(saved)
            moved.append((path, saved))
        subprocess.run(['pacman', '-U', '--needed', str(archive)], env=env, check=True)
    except BaseException:
        # Restore only paths still absent; never overwrite files Pacman installed.
        for path, saved in moved:
            if not path.exists() and not path.is_symlink():
                saved.rename(path)
        print('Installation stopped. Recovery files retained at ' + str(recovery), flush=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--check', action='store_true', help='validate only; do not move files or install')
    parser.add_argument('--adopt-legacy', action='store_true', help='back up and adopt the nine known unowned Aether commands')
    args = parser.parse_args()
    if os.environ.get('PREFIX') != str(PREFIX) or os.uname().machine != 'aarch64':
        parser.error('Run inside native aarch64 Termux with the com.termux prefix')
    archive = args.package.resolve(strict=True)
    checksum(archive)
    paths = inspect(archive)
    existing, legacy, blocked = conflicts(paths, owners(PREFIX / 'var/lib/pacman/local'))
    if blocked:
        raise ValueError('Files owned by other packages or unknown unowned files:\n' + '\n'.join(blocked))
    if legacy:
        print('Legacy unowned commands: ' + ', '.join(p.name for p in legacy), flush=True)
        if not args.adopt_legacy:
            raise ValueError('Retry with --adopt-legacy to back up and adopt only these commands. No files changed.')
    subprocess.run(['pacman', '-Qip', str(archive)], check=True)
    if args.check:
        print('PASS: package, checksum and file ownership; no changes made.')
        return
    state = Path.home() / '.local/state/termux-aether'
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    recovery = Path(tempfile.mkdtemp(prefix='upgrade-', dir=state))
    with tarfile.open(archive, 'r:xz') as package:
        library = package.extractfile(str(PREFIX).lstrip('/') + '/lib/libtermux-exec-direct-ld-preload.so')
        with (recovery / 'preload.so').open('wb') as stream:
            shutil.copyfileobj(library, stream)
    (recovery / 'preload.so').chmod(0o700)
    env = dict(os.environ, LD_PRELOAD=str(recovery / 'preload.so'))
    with (recovery / 'packages-before.txt').open('w') as stream:
        subprocess.run(['pacman', '-Q'], stdout=stream, env=env, check=True)
    with tarfile.open(recovery / 'files-before.tar', 'w') as backup:
        for path in existing:
            backup.add(path, arcname=str(path.relative_to(PREFIX)), recursive=False)
    (recovery / 'adopted.json').write_text(json.dumps([str(p) for p in legacy], ensure_ascii=False) + '\n')
    print('Recovery files: ' + str(recovery), flush=True)
    install_transaction(legacy, recovery, archive, env)
    print('Native suite installed. Run aether-apks api, then aether-apks app to update Android apps.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
