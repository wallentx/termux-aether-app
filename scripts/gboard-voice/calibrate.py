#!/usr/bin/env python3
"""Calibrate a visible, idle Gboard microphone from a lossless device screenshot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('screenshot', type=Path)
    parser.add_argument('--x', type=int, required=True)
    parser.add_argument('--y', type=int, required=True)
    parser.add_argument('--version-code', type=int, required=True)
    parser.add_argument('--output', type=Path, default=Path.home() / '.termux/gboard-mic.json')
    args = parser.parse_args()
    with args.screenshot.open('rb') as source:
        header = source.read(24)
    if len(header) != 24 or header[:8] != b'\x89PNG\r\n\x1a\n' or header[12:16] != b'IHDR':
        parser.error('Use the original, unscaled PNG screenshot.')
    width, height = struct.unpack('>II', header[16:24])
    if not (32 <= args.x <= width - 32 and 32 <= args.y <= height - 32):
        parser.error('The 64-pixel microphone sample must fit inside the screenshot.')
    pixels = subprocess.check_output([
        'ffmpeg', '-v', 'error', '-i', str(args.screenshot.resolve()),
        '-vf', f'crop=64:64:{args.x - 32}:{args.y - 32}',
        '-f', 'rawvideo', '-pix_fmt', 'argb', '-frames:v', '1', 'pipe:1',
    ], timeout=30)
    if len(pixels) != 64 * 64 * 4:
        raise RuntimeError('Unexpected pixel data; calibration was not changed.')
    profile = dict(ime='com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME',
                   versionCode=args.version_code, x=args.x, y=args.y, width=width, height=height,
                   fingerprint=hashlib.sha256(pixels).hexdigest())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.gboard-mic-', dir=args.output.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            json.dump(profile, output, indent=2)
            output.write('\n')
        os.replace(name, args.output)
    finally:
        if os.path.exists(name):
            os.unlink(name)
    print(f'Saved calibration to {args.output}')


if __name__ == '__main__':
    main()
