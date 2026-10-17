#!/usr/bin/env python3
"""Rebuild the Aether Go toolchain and Gum natively, in an isolated directory."""
import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shlex
import subprocess
import tarfile

from bootstrap import bootstrap_binary

BASE_SHA256 = 'd0fcea13d27528bb59a36a7a767085474a4b3fcf74aa5621d187dda1f69fae46'
GUM_COMMIT = '7179388031ae67d7f538d001be87d931f1cf5e28'
TOOLS = ('asm', 'cgo', 'compile', 'cover', 'fix', 'link', 'preprofile', 'vet')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', type=Path)
    parser.add_argument('--base-package', type=Path, default=Path(os.environ['PREFIX']) /
                        'var/cache/pacman/pkg/golang-3:1.27.1-0-aarch64.pkg.tar.xz')
    args = parser.parse_args()
    stage = args.stage.resolve()
    if stage.exists():
        parser.error('stage must not exist; installed files are never build inputs or outputs')
    with args.base_package.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != BASE_SHA256:
            parser.error('baseline package checksum mismatch')
    stage.mkdir(parents=True)
    goroot = stage / 'go'
    prefix = 'data/data/com.termux/files/usr/lib/go/'
    with tarfile.open(args.base_package, 'r:xz') as package:
        for entry in package:
            if not entry.name.startswith(prefix):
                continue
            relative = entry.name[len(prefix):]
            if not relative or '..' in PurePosixPath(relative).parts or relative.startswith('/'):
                continue
            entry.name = relative
            package.extract(entry, goroot, filter='data')
    for directory in ('bin', 'pkg/tool/android_arm64'):
        for binary in (goroot / directory).iterdir():
            if binary.is_file():
                bootstrap_binary(binary, stage / 'bootstrap' / binary.name)
    scripts = Path(__file__).resolve().parent
    subprocess.run(['python', str(scripts/'patch.py'), str(goroot)], check=True)
    wrapper = stage/'toolexec.sh'
    wrapper.write_text('#!/system/bin/sh\ntool="$1"; shift\n'
                       'exec /system/bin/linker64 "$AETHER_GO_BOOTSTRAP/${tool##*/}" "$@"\n')
    env = os.environ.copy()
    env.update(AETHER_GO_BOOTSTRAP=str(stage/'bootstrap'), GOROOT=str(goroot),
               CGO_ENABLED='0', GOOS='android', GOARCH='arm64', GOTOOLCHAIN='local', GOTELEMETRY='off', GOENV='off')
    env.pop('GOFLAGS', None)
    base = ['/system/bin/linker64', str(stage/'bootstrap/go'), 'build', '-p', '4',
            '-toolexec=/system/bin/sh '+shlex.quote(str(wrapper))]
    for output, packages in [(goroot/'bin/go', ['cmd/go']),
                             (goroot/'pkg/tool/android_arm64/', ['cmd/'+x for x in TOOLS]),
                             (goroot/'bin/gofmt', ['cmd/gofmt'])]:
        subprocess.run(base+['-o', str(output)]+packages, env=env, check=True)
    # The final tools execute normally, with no bootstrap argv modification.
    subprocess.run(['python', str(scripts/'validate.py'), str(goroot)], check=True)
    gum = stage/'gum'
    subprocess.run(['git', 'clone', '--depth', '1', '--branch', 'v2.0.1',
                    'https://github.com/charmbracelet/gum.git', str(gum)], check=True)
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=gum, text=True).strip()
    if actual != GUM_COMMIT:
        raise ValueError('Gum source tag no longer matches pinned commit')
    subprocess.run([str(goroot/'bin/go'), 'build', '-p', '4', '-trimpath',
                    '-ldflags=-s -w -X main.Version=2.0.1-aether.1 -X main.CommitSHA='+GUM_COMMIT,
                    '-o', str(stage/'gum-aether'), '.'], cwd=gum, env=env, check=True)
    subprocess.run(['python', str(scripts/'package.py'), str(stage)], check=True)
    subprocess.run(['python', str(scripts/'validate_packages.py'), str(stage/'packages')], check=True)


if __name__ == '__main__':
    main()
