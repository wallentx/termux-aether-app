#!/usr/bin/env python3
"""Package tested Go/Gum builds as optional, separately owned Pacman packages."""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import time

from build import BASE_SHA256, GUM_COMMIT

PREFIX = 'data/data/com.termux/files/usr'
VERSION = '1:1000.0.0-1'


def archive(stage, root, name, description, provides, conflicts, dependencies):
    output = stage/'packages'/f'{name}-1000.0.0-1-aarch64.pkg.tar.xz'
    output.parent.mkdir(exist_ok=True)
    files = sorted(root.rglob('*'))
    size = sum(p.stat().st_size for p in files if p.is_file() and not p.is_symlink())
    fields = [('pkgname', name), ('pkgver', VERSION), ('pkgdesc', description),
              ('url', 'https://github.com/wallentx/termux-aether-app'),
              ('builddate', str(int(time.time()))), ('packager', 'Termux Aether'),
              ('size', str(size)), ('arch', 'aarch64'),
              ('license', 'BSD-3-Clause' if name.endswith('golang') else 'MIT'),
              ('provides', provides), ('conflict', conflicts), ('replaces', conflicts)]
    fields += [('depend', value) for value in dependencies]
    metadata = ''.join(f'{key} = {value}\n' for key, value in fields).encode()
    with tarfile.open(output, 'w:xz', preset=3) as package:
        info = tarfile.TarInfo('.PKGINFO')
        info.size, info.mode = len(metadata), 0o644
        package.addfile(info, io.BytesIO(metadata))
        for file in files:
            relative = file.relative_to(root).as_posix()
            if not relative.startswith(PREFIX+'/'):
                continue
            info = package.gettarinfo(file, arcname=relative)
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            if info.isfile():
                with file.open('rb') as stream:
                    package.addfile(info, stream)
            else:
                package.addfile(info)
    with output.open('rb') as file:
        digest = hashlib.file_digest(file, 'sha256').hexdigest()
    output.with_name(output.name+'.sha256').write_text(f'{digest}  {output.name}\n')
    print(output, flush=True)


def main():
    stage = Path(sys.argv[1]).resolve()
    sources = Path(__file__).resolve().parent
    payload = stage/'payload'
    if payload.exists():
        raise SystemExit('payload already exists; refusing to reuse possibly stale files')
    go = payload/'golang'/PREFIX
    (go/'lib').mkdir(parents=True)
    shutil.copytree(stage/'go', go/'lib/go', symlinks=True)
    (go/'bin').mkdir()
    for name in ('go', 'gofmt'):
        (go/'bin'/name).symlink_to('../lib/go/bin/'+name)
    doc = go/'share/doc/termux-aether-golang'
    doc.mkdir(parents=True)
    baseline = Path(os.environ['PREFIX'])/'share/doc/golang/copyright'
    if not baseline.exists():
        baseline = Path(os.environ['PREFIX'])/'share/doc/termux-aether-golang/copyright'
    shutil.copy2(baseline, doc/'copyright')
    for source in sources.iterdir():
        if source.suffix in ('.py', '.go', '.md'):
            shutil.copy2(source, doc/source.name)
    (doc/'provenance.json').write_text(json.dumps({
        'go_version': '1.27.1', 'baseline_package_sha256': BASE_SHA256,
        'gum_commit': GUM_COMMIT, 'bootstrap_bytes_shipped': False,
        'patch_sources': {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sources.iterdir() if p.suffix in ('.py', '.go')},
    }, indent=2)+'\n')
    archive(stage, payload/'golang', 'termux-aether-golang',
            'Go 1.27.1 with Aether Android execution support', 'golang=3:1.27.1', 'golang',
            ['clang', 'termux-exec>=1:1000.0.0'])
    gum = payload/'gum'/PREFIX
    (gum/'bin').mkdir(parents=True)
    shutil.copy2(stage/'gum-aether', gum/'bin/gum')
    gumdoc = gum/'share/doc/gum'
    gumdoc.mkdir(parents=True)
    shutil.copy2(stage/'gum/LICENSE', gumdoc/'LICENSE')
    outputs = [('bash', 'share/bash-completion/completions/gum'),
               ('zsh', 'share/zsh/site-functions/_gum'),
               ('fish', 'share/fish/vendor_completions.d/gum.fish')]
    for shell, relative in outputs:
        path = gum/relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(subprocess.check_output([str(gum/'bin/gum'), 'completion', shell]))
    manual = gum/'share/man/man1/gum.1.gz'
    manual.parent.mkdir(parents=True)
    manual.write_bytes(gzip.compress(subprocess.check_output([str(gum/'bin/gum'), 'man']), mtime=0))
    archive(stage, payload/'gum', 'termux-aether-gum', 'Gum 2.0.1 built with the Aether Go runtime',
            'gum=2.0.1', 'gum', ['termux-exec>=1:1000.0.0'])


if __name__ == '__main__':
    main()
