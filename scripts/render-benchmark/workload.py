#!/usr/bin/env python3
"""Foreground terminal workload; pair with capture.py for Android frame metrics."""
import argparse
import json
import os
from pathlib import Path
import time
import select
import termios
import tty


def emit(data):
    view = memoryview(data)
    while view:
        view = view[os.write(1, view):]


def sixel(width, height, declared=True):
    raster = f'"1;1;{width};{height}' if declared else ''
    bands = [f'#{i % 3 + 1}!{width}~' for i in range(height // 6)]
    return ('\x1bP0;1q' + raster + '#1;2;100;0;0#2;2;0;100;0#3;2;0;0;100'
            + '-'.join(bands) + '\x1b\\').encode()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    parser.add_argument('--seconds', type=float, default=20)
    parser.add_argument('--fps', type=float, default=60)
    parser.add_argument('--input-phase', action='store_true')
    args = parser.parse_args()
    if not os.isatty(1):
        parser.error('Run in a foreground Termux terminal')
    if not (0 < args.seconds <= 120 and 0 < args.fps <= 120):
        parser.error('seconds and fps must be positive and at most 120')
    args.directory.mkdir(parents=True, exist_ok=False)
    columns, rows = os.get_terminal_size()
    image = sixel(960, 600)
    image_growth = sixel(960, 600, declared=False)
    results = []
    received = []
    saved_terminal = termios.tcgetattr(0) if args.input_phase else None
    if saved_terminal is not None:
        tty.setcbreak(0)

    def check_abort():
        if (args.directory / 'abort').exists():
            raise SystemExit('Collector stopped; benchmark aborted')

    def collect_input():
        if select.select([0], [], [], 0)[0]:
            data = os.read(0, 1024)
            if any(c != ord('a') for c in data):
                raise RuntimeError('Unexpected keyboard input during synthetic input test')
            received.extend([time.monotonic_ns()] * len(data))
            emit(('\r\ninput received: ' + str(len(received)) + '\r\n').encode())

    emit(b'\x1b[?1049h\x1b[?25l')
    try:
        phases = ['text_scroll', 'image_redraw', 'image_replace']
        if args.input_phase:
            phases.append('input_echo')
        for name in phases:
            emit(b'\x1b[0m\x1b[2J\x1b[H')
            if name in ('text_scroll', 'input_echo'):
                emit(('render baseline ' * (columns * rows // 16)).encode())
            else:
                emit(image)
            time.sleep(1)
            (args.directory / 'state.json').write_text(json.dumps({'phase': name, 'state': 'ready'}))
            go = args.directory / (name + '.go')
            wait_start = time.monotonic()
            while not go.exists():
                check_abort()
                if time.monotonic() - wait_start > 120:
                    raise TimeoutError('No collector start signal')
                time.sleep(.05)
            start = time.monotonic()
            count = 0
            late = 0
            while time.monotonic() - start < args.seconds:
                check_abort()
                if os.get_terminal_size() != (columns, rows):
                    raise RuntimeError('Terminal dimensions changed during capture')
                if name in ('text_scroll', 'input_echo'):
                    payload = ((f'\x1b[32m{count:06d}\x1b[0m ' + 'abcdefghij ' * (columns // 11))
                               + '\r\n') * 4
                    emit(payload.encode())
                elif name == 'image_redraw':
                    # A text update invalidates the whole terminal, redrawing the existing image.
                    emit(f'\x1b[{rows};1Hframe {count:06d}'.encode())
                else:
                    # Undeclared raster exercises bitmap growth as well as decoding and uploads.
                    emit(b'\x1b[H' + image_growth)
                if name == 'input_echo':
                    collect_input()
                count += 1
                delay = start + count / args.fps - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                else:
                    late += 1
            results.append({'phase': name, 'updates': count, 'late_updates': late,
                            'elapsed_seconds': time.monotonic() - start})
            (args.directory / 'state.json').write_text(json.dumps({'phase': name, 'state': 'done'}))
            ack = args.directory / (name + '.ack')
            wait_start = time.monotonic()
            while not ack.exists():
                check_abort()
                if name == 'input_echo':
                    collect_input()
                if time.monotonic() - wait_start > 120:
                    raise TimeoutError('No collector finish signal')
                time.sleep(.05)
        (args.directory / 'result.json').write_text(json.dumps({
            'columns': columns, 'rows': rows, 'image_width': 960, 'image_height': 600,
            'fps': args.fps, 'seconds': args.seconds, 'results': results,
            'input_received_ns': received}, indent=2))
    finally:
        if saved_terminal is not None:
            termios.tcsetattr(0, termios.TCSANOW, saved_terminal)
        emit(b'\x1b[0m\x1b[?25h\x1b[?1049l')


if __name__ == '__main__':
    main()
