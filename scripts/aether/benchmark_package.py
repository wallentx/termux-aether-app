#!/usr/bin/env python3
"""Compare two package verifiers on an existing release APK and source archive."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import resource
import statistics
import subprocess
import sys
import tarfile
import tempfile
import time
from unittest.mock import patch
import zipfile


def file_digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(apk_path, source_archive, root):
    """Create only the known source/build paths needed by both verifiers."""
    with tarfile.open(source_archive, 'r:gz') as archive:
        def read(name):
            member = archive.getmember('./' + name)
            if not member.isfile():
                raise ValueError('Fixture is not a regular file: ' + name)
            with archive.extractfile(member) as data:
                return data.read()

        manifest = json.loads(read('manifest.json'))
        targets = {name: 'app/src/aether/assets/aether/' + name
                   for name in ('provenance.json', 'COPYING.LIB', 'LICENSES')}
        targets.update({name: 'scripts/aether/' + name for name in (
            'launcher.c', 'compat.c', 'exec.c', 'system.c', 'probe.c',
            'build.sh', 'source-bundle.sh', 'README.md')})
        for name, target in targets.items():
            destination = root / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(read(name))
    with zipfile.ZipFile(apk_path) as apk:
        for entry, target in (
            ('lib/arm64-v8a/libaether-run.so', 'jniLibs/arm64-v8a/libaether-run.so'),
            ('lib/arm64-v8a/libaether-exec.so', 'jniLibs/arm64-v8a/libaether-exec.so'),
            ('assets/aether/libaether-compat.so', 'assets/aether/libaether-compat.so'),
            ('assets/aether/aether-probe', 'probes/aether-probe'),
        ):
            destination = root / 'app/build/generated/aether' / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(apk.read(entry))
    return manifest['source_commit']


def child(verifier, apk, source, root, commit):
    spec = importlib.util.spec_from_file_location('measured_verifier', verifier)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    # The fixture represents the release's historical checkout. Substitute only
    # its git HEAD lookup; payload, provenance, source and hash checks still run.
    real_check_output = module.subprocess.check_output

    def historical_head(command, *args, **kwargs):
        if command == ['git', 'rev-parse', 'HEAD'] and kwargs.get('cwd') == root:
            return commit + '\n'
        return real_check_output(command, *args, **kwargs)

    with patch.object(module.subprocess, 'check_output', side_effect=historical_head):
        start = time.perf_counter()
        module.verify(apk, True, source, root)
        elapsed = time.perf_counter() - start
    return {'seconds': elapsed, 'process_peak_rss_kib': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, default=Path(__file__).with_name('verify-package.py'))
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--output', type=Path, default=Path.home() / 'benchmarks' /
                        ('source-verification-' + time.strftime('%Y%m%d-%H%M%S') + '.json'))
    parser.add_argument('--child', choices=('baseline', 'candidate'), help=argparse.SUPPRESS)
    parser.add_argument('--root', type=Path, help=argparse.SUPPRESS)
    parser.add_argument('--commit', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not 1 <= args.samples <= 20:
        parser.error('Use samples 1..20')
    if args.child:
        print(json.dumps(child(getattr(args, args.child), args.apk, args.source, args.root, args.commit)))
        return
    report = {'schema_version': 1, 'uid': os.getuid(), 'started_at': time.time(), 'samples': [],
              'inputs': {name: {'sha256': file_digest(getattr(args, name)),
                               'bytes': getattr(args, name).stat().st_size}
                         for name in ('baseline', 'candidate', 'apk', 'source')},
              'limitations': 'Existing release artifacts; fixture HEAD and generated paths reconstructed from the release. Verification only: excludes fixture preparation, interpreter startup, builds and downloads. Peak RSS is whole child-process high-water memory on Linux/Android. Warm filesystem caches; uncontrolled CPU clocks and thermals. Not an app-runtime benchmark.'}
    with tempfile.TemporaryDirectory(prefix='aether-verify-', dir=os.environ.get('TMPDIR')) as tmp:
        root = Path(tmp)
        commit = prepare(args.apk, args.source, root)
        report['source_commit'] = commit
        for pair in range(-1, args.samples):
            for mode in ('baseline', 'candidate') if pair % 2 == 0 else ('candidate', 'baseline'):
                command = [sys.executable, '-B', str(Path(__file__).resolve()), '--child', mode,
                           '--baseline', str(args.baseline.resolve()), '--candidate', str(args.candidate.resolve()),
                           '--apk', str(args.apk.resolve()), '--source', str(args.source.resolve()),
                           '--root', str(root), '--commit', commit]
                p = subprocess.run(command, capture_output=True, text=True, check=True, timeout=120)
                if pair >= 0:
                    result = dict(json.loads(p.stdout), mode=mode, pair=pair)
                    report['samples'].append(result)
                    print(f'{mode}: {result["seconds"]:.3f}s, {result["process_peak_rss_kib"]} KiB', flush=True)
    report['medians'] = {mode: {key: statistics.median(s[key] for s in report['samples'] if s['mode'] == mode)
                                for key in ('seconds', 'process_peak_rss_kib')}
                         for mode in ('baseline', 'candidate')}
    report['finished_at'] = time.time()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(str(args.output))


if __name__ == '__main__':
    main()
