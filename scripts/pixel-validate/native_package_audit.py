#!/usr/bin/env python3
"""Audit installed JPEG SIMD dispatch and whole-buffer compression; no installs."""
import argparse
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import random
import statistics
import subprocess
import sys
import time
import zlib

from package_benchmark import snapshot


def bind(lib, name, result, *arguments):
    function = getattr(lib, name)
    function.restype = result
    function.argtypes = arguments
    return function


def measure(operation, seconds, size):
    operation()  # Warm the dispatch and caches before timing.
    start = time.perf_counter()
    iterations = 0
    while True:
        operation()
        iterations += 1
        elapsed = time.perf_counter() - start
        if elapsed >= seconds:
            return {'iterations': iterations, 'seconds': elapsed,
                    'mib_per_second': size * iterations / elapsed / 2**20}


def load_library(name):
    path = Path(os.environ['PREFIX']) / 'lib' / name
    return C.CDLL(str(path)), {'path': str(path.resolve()),
                              'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def jpeg(seconds):
    lib, identity = load_library('libturbojpeg.so')
    pointer = C.POINTER(C.c_ubyte)
    init_c = bind(lib, 'tjInitCompress', C.c_void_p)
    init_d = bind(lib, 'tjInitDecompress', C.c_void_p)
    destroy = bind(lib, 'tjDestroy', C.c_int, C.c_void_p)
    bound = bind(lib, 'tjBufSize', C.c_ulong, C.c_int, C.c_int, C.c_int)
    compress = bind(lib, 'tjCompress2', C.c_int, C.c_void_p, C.c_void_p,
                    C.c_int, C.c_int, C.c_int, C.c_int, C.POINTER(pointer),
                    C.POINTER(C.c_ulong), C.c_int, C.c_int, C.c_int)
    decompress = bind(lib, 'tjDecompress2', C.c_int, C.c_void_p, C.c_void_p,
                      C.c_ulong, C.c_void_p, C.c_int, C.c_int, C.c_int,
                      C.c_int, C.c_int)
    error = bind(lib, 'tjGetErrorStr', C.c_char_p)
    # Odd dimensions exercise partial vector blocks and chroma edges.
    width, height = 1023, 769
    raw = bytes((i * 37 + (i // (width * 3)) * 13) & 255
                for i in range(width * height * 3))
    source = C.create_string_buffer(raw)
    capacity = bound(width, height, 2)  # TJSAMP_420
    output = C.create_string_buffer(capacity)
    output_pointer = C.cast(output, pointer)
    size = C.c_ulong(capacity)
    decoded = C.create_string_buffer(len(raw))
    compressor, decompressor = init_c(), init_d()
    try:
        if not compressor or not decompressor:
            raise RuntimeError('TurboJPEG initialization failed')

        def encode():
            size.value = capacity
            # TJPF_RGB=0, quality=90, NOREALLOC | ACCURATEDCT.
            if compress(compressor, source, width, 0, height, 0,
                        C.byref(output_pointer), C.byref(size), 2, 90, 1024 | 4096):
                raise RuntimeError(error().decode())

        def decode():
            if decompress(decompressor, output, size.value, decoded, width,
                          0, height, 0, 4096):
                raise RuntimeError(error().decode())

        encoded_timing = measure(encode, seconds, len(raw))
        decoded_timing = measure(decode, seconds, len(raw))
        return {'library': identity, 'width': width, 'height': height,
                'encode': encoded_timing, 'decode': decoded_timing,
                'compressed_bytes': size.value,
                'input_sha256': hashlib.sha256(raw).hexdigest(),
                'encoded_sha256': hashlib.sha256(output.raw[:size.value]).hexdigest(),
                'decoded_sha256': hashlib.sha256(decoded.raw).hexdigest()}
    finally:
        if compressor:
            destroy(compressor)
        if decompressor:
            destroy(decompressor)


def corpus(kind):
    size = 2**20
    if kind == 'random':
        return random.Random(20260922).randbytes(size)
    records = b''.join((f'2026-09-22 INFO job={i} status=ok path=src/module-{i % 71}/file-{i % 197}.go\n').encode()
                       for i in range(4096))
    return (records * (size // len(records) + 1))[:size]


def compression(backend, kind, seconds):
    raw = corpus(kind)
    source = C.create_string_buffer(raw)
    # Identical encoded stream for both decompression implementations.
    packed = zlib.compress(raw, 6)
    packed_source = C.create_string_buffer(packed)
    decoded = C.create_string_buffer(len(raw))
    compressor = decompressor = None
    if backend == 'zlib':
        lib, identity = load_library('libz.so')
        bound = bind(lib, 'compressBound', C.c_ulong, C.c_ulong)(len(raw))
        compress = bind(lib, 'compress2', C.c_int, C.c_void_p, C.POINTER(C.c_ulong),
                        C.c_void_p, C.c_ulong, C.c_int)
        decompress = bind(lib, 'uncompress', C.c_int, C.c_void_p,
                          C.POINTER(C.c_ulong), C.c_void_p, C.c_ulong)
    else:
        lib, identity = load_library('libdeflate.so')
        alloc_c = bind(lib, 'libdeflate_alloc_compressor', C.c_void_p, C.c_int)
        alloc_d = bind(lib, 'libdeflate_alloc_decompressor', C.c_void_p)
        free_c = bind(lib, 'libdeflate_free_compressor', None, C.c_void_p)
        free_d = bind(lib, 'libdeflate_free_decompressor', None, C.c_void_p)
        compressor, decompressor = alloc_c(6), alloc_d()
        if not compressor or not decompressor:
            free_c(compressor)
            free_d(decompressor)
            raise RuntimeError('libdeflate allocation failed')
        bound = bind(lib, 'libdeflate_zlib_compress_bound', C.c_size_t,
                     C.c_void_p, C.c_size_t)(compressor, len(raw))
        compress = bind(lib, 'libdeflate_zlib_compress', C.c_size_t,
                        C.c_void_p, C.c_void_p, C.c_size_t, C.c_void_p, C.c_size_t)
        decompress = bind(lib, 'libdeflate_zlib_decompress', C.c_int,
                          C.c_void_p, C.c_void_p, C.c_size_t, C.c_void_p,
                          C.c_size_t, C.POINTER(C.c_size_t))
    try:
        output = C.create_string_buffer(bound)
        compressed_size = 0

        def encode():
            nonlocal compressed_size
            if backend == 'zlib':
                length = C.c_ulong(bound)
                if compress(output, C.byref(length), source, len(raw), 6):
                    raise RuntimeError('zlib compression failed')
                compressed_size = length.value
            else:
                compressed_size = compress(compressor, source, len(raw), output, bound)
                if not compressed_size:
                    raise RuntimeError('libdeflate compression failed')

        def decode():
            if backend == 'zlib':
                length = C.c_ulong(len(raw))
                if decompress(decoded, C.byref(length), packed_source, len(packed)) or length.value != len(raw):
                    raise RuntimeError('zlib decompression failed')
            elif decompress(decompressor, packed_source, len(packed), decoded, len(raw), None):
                raise RuntimeError('libdeflate decompression failed')

        encoded_timing = measure(encode, seconds, len(raw))
        decoded_timing = measure(decode, seconds, len(raw))
        if decoded.raw != raw or zlib.decompress(output.raw[:compressed_size]) != raw:
            raise RuntimeError('Compression round-trip mismatch')
        return {'library': identity, 'encode': encoded_timing, 'decode': decoded_timing,
                'input_bytes': len(raw), 'compressed_bytes': compressed_size,
                'input_sha256': hashlib.sha256(raw).hexdigest(),
                'decode_input_sha256': hashlib.sha256(packed).hexdigest(), 'roundtrip': True}
    finally:
        if backend == 'libdeflate':
            free_c(compressor)
            free_d(decompressor)


def child_environment(workload, mode):
    env = dict(os.environ)
    # Only child processes get dispatch controls; nothing changes in the user's shell.
    for key in list(env):
        if key.startswith('JSIMD_') or key == 'LIBDEFLATE_DISABLE_CPU_FEATURES':
            del env[key]
    if workload == 'jpeg' and mode == 'disabled':
        env['JSIMD_FORCENONE'] = '1'
    return env


def validate_pair(samples, workload):
    fields = ('input_sha256', 'encoded_sha256', 'decoded_sha256') if workload == 'jpeg' else (
        'input_sha256', 'decode_input_sha256')
    for field in fields:
        if len({s[field] for s in samples}) != 1:
            raise RuntimeError(f'{workload}: {field} differs across samples')
    if workload != 'jpeg' and not all(s['roundtrip'] for s in samples):
        raise RuntimeError('Compression round-trip failed')


def package_inventory():
    try:
        result = subprocess.run(['pacman', '-Q', 'libjpeg-turbo', 'zlib', 'libdeflate'],
                                capture_output=True, text=True, timeout=5)
        return {'returncode': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'error': str(error)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--seconds', type=float, default=0.3)
    parser.add_argument('--output', type=Path, default=Path.home() / 'benchmarks' /
                        ('native-packages-' + time.strftime('%Y%m%d-%H%M%S') + '.json'))
    parser.add_argument('--child', choices=('jpeg', 'records', 'random'), help=argparse.SUPPRESS)
    parser.add_argument('--mode', choices=('default', 'disabled', 'zlib', 'libdeflate'), help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not 1 <= args.samples <= 20 or not 0.05 <= args.seconds <= 2:
        parser.error('Use samples 1..20 and seconds 0.05..2')
    if args.child:
        if args.child == 'jpeg' and args.mode not in ('default', 'disabled'):
            parser.error('JPEG requires default or disabled mode')
        if args.child != 'jpeg' and args.mode not in ('zlib', 'libdeflate'):
            parser.error('Compression requires zlib or libdeflate mode')
        print(json.dumps(jpeg(args.seconds) if args.child == 'jpeg' else
                         compression(args.mode, args.child, args.seconds)))
        return
    report = {'schema_version': 1, 'uid': os.getuid(), 'started_at': time.time(),
              'script_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'parameters': {'samples': args.samples, 'seconds_per_operation': args.seconds},
              'packages': package_inventory(),
              'before': snapshot(), 'workloads': {},
              'limitations': 'Synthetic warm-buffer workloads, uncontrolled clocks/thermals. JPEG uses child-only JSIMD_FORCENONE. Compression compares libraries, not SIMD on/off; level 6 is not an equivalent compression ratio. Output buffers are reused. No whole-app or battery-efficiency claim.'}
    failed = False
    for workload, modes in [('jpeg', ('default', 'disabled')),
                            ('records', ('zlib', 'libdeflate')), ('random', ('zlib', 'libdeflate'))]:
        entry = {'samples': []}
        report['workloads'][workload] = entry
        try:
            for pair in range(args.samples):
                for mode in modes if pair % 2 == 0 else modes[::-1]:
                    command = [sys.executable, str(Path(__file__).resolve()), '--child', workload,
                               '--mode', mode, '--seconds', str(args.seconds)]
                    p = subprocess.run(command, env=child_environment(workload, mode),
                                       capture_output=True, text=True, timeout=30, check=True)
                    entry['samples'].append(dict(json.loads(p.stdout), mode=mode, pair=pair))
            validate_pair(entry['samples'], workload)
            entry['median_mib_per_second'] = {
                mode: {op: statistics.median(s[op]['mib_per_second'] for s in entry['samples']
                                            if s['mode'] == mode) for op in ('encode', 'decode')}
                for mode in modes}
            entry['status'] = 'PASS'
        except Exception as error:
            entry['status'] = 'FAIL'
            entry['error'] = str(error)
            if isinstance(error, subprocess.CalledProcessError):
                entry['stderr'] = error.stderr
            failed = True
        print(workload + ': ' + entry['status'], flush=True)
    report['after'] = snapshot()
    report['finished_at'] = time.time()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(str(args.output))
    return int(failed)


if __name__ == '__main__':
    sys.exit(main())
