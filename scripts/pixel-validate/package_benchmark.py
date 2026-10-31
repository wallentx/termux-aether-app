#!/usr/bin/env python3
"""Repeatable native-package checks; SHA acceleration is distinct from general SIMD."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import ssl
import statistics
import subprocess
import sys
import time
import zlib


def child(mib, loops):
    assert hashlib.sha256(b'abc').hexdigest() == 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
    data = bytes(range(256)) * (mib * 4096)
    start = time.perf_counter()
    for _ in range(loops):
        digest = hashlib.sha256(data).hexdigest()
    elapsed = time.perf_counter() - start
    return {'digest': digest, 'mib_per_second': mib * loops / elapsed,
            'seconds': elapsed, 'openssl': ssl.OPENSSL_VERSION}


def snapshot():
    try:
        p = subprocess.run(['termux-capabilities', '--json'], capture_output=True, text=True, timeout=25)
        return json.loads(p.stdout)
    except Exception as e:
        return {'error': str(e)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--mib', type=int, default=64)
    parser.add_argument('--loops', type=int, default=8)
    parser.add_argument('--output', type=Path, default=Path.home() / 'benchmarks' / ('packages-' + time.strftime('%Y%m%d-%H%M%S') + '.json'))
    parser.add_argument('--child', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not 1 <= args.samples <= 50 or not 1 <= args.mib <= 512 or not 1 <= args.loops <= 100:
        parser.error('Use samples 1..50, MiB 1..512 and loops 1..100')
    if args.child:
        print(json.dumps(child(args.mib, args.loops)))
        return
    report = {'started_at': time.time(), 'uid': os.getuid(), 'before': snapshot(), 'samples': [],
              'note': 'Alternating native OpenSSL SHA-256 dispatch vs child-only capability-mask override; not a terminal FPS or general SIMD benchmark.'}
    digest = None
    for pair in range(args.samples):
        for mode in (('default', 'disabled') if pair % 2 == 0 else ('disabled', 'default')):
            env = dict(os.environ)
            env.pop('OPENSSL_armcap', None)
            if mode == 'disabled':
                env['OPENSSL_armcap'] = '0'
            p = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--child', '--mib', str(args.mib), '--loops', str(args.loops)],
                               env=env, capture_output=True, text=True, check=True, timeout=90)
            result = json.loads(p.stdout)
            if digest is not None and result['digest'] != digest:
                raise RuntimeError('Dispatch modes produced different digests')
            digest = result['digest']
            report['samples'].append(dict(result, mode=mode, pair=pair))
    medians = {mode: statistics.median(s['mib_per_second'] for s in report['samples'] if s['mode'] == mode)
               for mode in ('default', 'disabled')}
    report['median_mib_per_second'] = medians
    report['ratio'] = medians['default'] / medians['disabled']
    data = bytes(range(256)) * 4096
    packed = zlib.compress(data)
    assert zlib.decompress(packed) == data
    report['zlib'] = {'version': zlib.ZLIB_RUNTIME_VERSION, 'roundtrip': True}
    library = Path(os.environ.get('PREFIX', '/nonexistent')) / 'lib/libcrypto.so.3'
    if library.is_file():
        report['libcrypto_sha256'] = hashlib.sha256(library.read_bytes()).hexdigest()
    report['after'] = snapshot()
    report['finished_at'] = time.time()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'report': str(args.output), 'median_mib_per_second': medians, 'ratio': report['ratio']}))


if __name__ == '__main__':
    main()
