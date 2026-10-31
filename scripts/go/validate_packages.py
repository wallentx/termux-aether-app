#!/usr/bin/env python3
"""Check real Pacman replacement in a disposable root before live installation."""
import hashlib
import io
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile
import tempfile


def main():
    packages = sorted(Path(sys.argv[1]).resolve().glob('*.pkg.tar.xz'))
    if len(packages) != 2:
        raise ValueError('expected exactly the Go and Gum packages')
    prefix = Path(os.environ['PREFIX'])
    before = {name: hashlib.sha256((prefix/'bin'/name).read_bytes()).hexdigest()
              for name in ('go', 'gum')}
    for archive in packages:
        expected = archive.with_name(archive.name+'.sha256').read_text().split()[0]
        with archive.open('rb') as stream:
            assert hashlib.file_digest(stream, 'sha256').hexdigest() == expected
        with tarfile.open(archive) as package:
            for member in package:
                assert member.name == '.PKGINFO' or member.name.startswith('data/data/com.termux/files/usr/')
                assert '..' not in PurePosixPath(member.name).parts
                assert member.isfile() or member.isdir() or member.issym()
    with tempfile.TemporaryDirectory(prefix='aether-go-pacman-', dir=os.environ['TMPDIR']) as tmp:
        base = Path(tmp)
        for name in ('root', 'db', 'cache', 'hooks'):
            (base/name).mkdir()
        (base/'pacman.conf').write_text('[options]\nArchitecture = aarch64\nSigLevel = Never\n')
        common = ['pacman', '--root', str(base/'root'), '--dbpath', str(base/'db'),
                  '--cachedir', str(base/'cache'), '--hookdir', str(base/'hooks'),
                  '--logfile', str(base/'log'), '--config', str(base/'pacman.conf')]
        fixtures = []
        for name, extra, relative in [('golang', '', 'bin/go'), ('gum', '', 'bin/gum'),
                ('fixture-deps', 'provides = clang\nprovides = termux-exec=1:1000.0.0\n', None)]:
            archive = base/(name+'.pkg.tar.xz')
            fixtures.append(str(archive))
            files = {'.PKGINFO': f'pkgname = {name}\npkgver = 1.0-1\npkgdesc = fixture\narch = aarch64\nsize = 0\n'+extra}
            if relative:
                files['data/data/com.termux/files/usr/'+relative] = 'old'
            with tarfile.open(archive, 'w:xz') as package:
                for key, value in files.items():
                    data = value.encode()
                    member = tarfile.TarInfo(key)
                    member.size, member.mode = len(data), 0o700
                    package.addfile(member, io.BytesIO(data))
        install = common+['--noscriptlet', '--noprogressbar', '-U']
        subprocess.run(install+fixtures, input='y\n', text=True, check=True, stdout=subprocess.DEVNULL)
        subprocess.run(install+[str(p) for p in packages], input='y\ny\ny\n', text=True, check=True)
        assert not list((base/'db/local').glob('golang-*'))
        assert not list((base/'db/local').glob('gum-*'))
        subprocess.run(common+['-Qk', 'termux-aether-golang', 'termux-aether-gum'], check=True)
    assert all(hashlib.sha256((prefix/'bin'/name).read_bytes()).hexdigest() == digest
               for name, digest in before.items())
    print('PASS: isolated replacement, dependencies, ownership; installed programs unchanged')


if __name__ == '__main__':
    main()
