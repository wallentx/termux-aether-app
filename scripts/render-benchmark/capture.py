#!/usr/bin/env python3
"""ADB collector for workload.py. Saves raw gfxinfo plus bounded frame samples."""
import argparse
import csv
import io
import json
import os
import re
import tempfile
from pathlib import Path
import shlex
import subprocess
import time


def parse_frames(text):
    frames = {}
    for block in text.split('---PROFILEDATA---')[1::2]:
        for row in csv.DictReader(io.StringIO(block.strip())):
            try:
                if int(row['Flags']) == 0:
                    frames[int(row['IntendedVsync'])] = {k: int(v) for k, v in row.items() if k and v}
            except (KeyError, ValueError, TypeError):
                continue
    return frames


def percentile(values, p):
    values = sorted(values)
    return round(values[min(len(values)-1, int((len(values)-1)*p))], 3) if values else None


def local_shell(command):
    """Capture via files: rish pipe output can be truncated on this firmware."""
    with tempfile.TemporaryDirectory(prefix='aether-render-',
                                     dir='/storage/emulated/0/Download') as directory:
        root = Path(directory)
        out, err, status = (root / name for name in ('out', 'err', 'status'))
        wrapped = ('( ' + command + ' ) > ' + shlex.quote(str(out)) + ' 2> '
                   + shlex.quote(str(err)) + "; printf '%s' $? > " + shlex.quote(str(status)))
        subprocess.run(['rish', '-c', wrapped], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=True, timeout=15)
        code = int(status.read_text())
        if code:
            raise subprocess.CalledProcessError(code, command, out.read_text(), err.read_text())
        return out.read_text()


def validate_display(text, expected_rotation=None):
    focus = re.findall(r'^\s*mFocusedWindow=(.*)$', text, re.M)
    rotations = re.findall(r'^\s*mRotation=(\d+)\b', text, re.M)
    if (len(focus) != 1 or 'com.termux/com.termux.app.TermuxActivity}' not in focus[0]
            or 'isKeyguardShowing=false' not in text or 'isKeyguardShowing=true' in text
            or len(rotations) != 1):
        raise RuntimeError('Termux must remain focused and unlocked throughout capture')
    rotation = int(rotations[0])
    if expected_rotation is not None and rotation != expected_rotation:
        raise RuntimeError('Display rotated during capture; rerun with stable orientation')
    return rotation


def summarize_frames(phase, frames):
    rows = list(frames.values())
    deadlines = [f for f in rows if 0 < f.get('FrameDeadline', 0) < f.get('IntendedVsync', 0) + 10**9
                 and 0 <= f.get('FrameCompleted', 0) - f.get('IntendedVsync', 0) < 10**9]
    missed = sum(f['FrameCompleted'] > f['FrameDeadline'] for f in deadlines)
    return {'phase': phase, 'sampled_frames': len(rows),
            'sampled_deadlines': len(deadlines), 'sampled_deadline_misses': missed,
            'sampled_deadline_miss_percent': round(100 * missed / len(deadlines), 3) if deadlines else None,
            'frame_intervals_ms': sorted({round(f['FrameInterval'] / 1e6, 3) for f in rows
                                          if 0 < f.get('FrameInterval', 0) < 10**9})}


def main():
    parser = argparse.ArgumentParser()
    transport = parser.add_mutually_exclusive_group(required=True)
    transport.add_argument('--serial')
    transport.add_argument('--local-rish', action='store_true',
                           help='Collect on this Termux device using authorized Shizuku')
    parser.add_argument('--remote-dir', required=True)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--input-phase', action='store_true',
                        help='Also measure synthetic key delivery during output; match workload option')
    args = parser.parse_args()
    if args.input_phase and not args.local_rish:
        parser.error('--input-phase requires --local-rish for a shared monotonic clock')
    if args.input_phase:
        try:
            local_shell('input --help')
        except subprocess.CalledProcessError as error:
            parser.error('Synthetic input is unavailable through Shizuku: ' + error.stderr.strip())
    args.output.mkdir(parents=True, exist_ok=False)

    def adb(command):
        if args.local_rish:
            return local_shell(command)
        return subprocess.check_output(['adb', '-s', args.serial, 'shell', command], text=True, timeout=15)

    def app(command):
        if args.local_rish:
            return subprocess.check_output([os.environ['PREFIX'] + '/bin/sh', '-c', command],
                                           text=True, timeout=15)
        return adb('run-as com.termux /system/bin/sh -c ' + shlex.quote(command))

    def state():
        try:
            if args.local_rish:
                return json.loads((Path(args.remote_dir) / 'state.json').read_text())
            return json.loads(app('cat ' + shlex.quote(args.remote_dir + '/state.json')))
        except (OSError, ValueError, subprocess.CalledProcessError):
            return {}

    def touch(suffix):
        if args.local_rish:
            (Path(args.remote_dir) / suffix).touch()
        else:
            app('touch ' + shlex.quote(args.remote_dir + '/' + suffix))

    summaries = []
    rotation = None

    def check_display():
        nonlocal rotation
        if args.local_rish:
            raw = adb("dumpsys window displays | grep -E 'mFocusedWindow=|^ *mRotation=|isKeyguardShowing='")
            rotation = validate_display(raw, rotation)
            with (args.output / 'display-checks.jsonl').open('a') as log:
                log.write(json.dumps({'monotonic_ns': time.monotonic_ns(), 'state': raw}) + '\n')

    complete = False
    try:
        check_display()
        (args.output / 'device.txt').write_text(adb('getprop ro.product.model; getprop ro.build.fingerprint; dumpsys package com.termux | grep versionName'))
        phases = ['text_scroll', 'image_redraw', 'image_replace']
        if args.input_phase:
            phases.append('input_echo')
        for phase in phases:
            ready_deadline = time.monotonic() + 150
            while state() != {'phase': phase, 'state': 'ready'}:
                if time.monotonic() > ready_deadline:
                    raise TimeoutError('Waiting for ' + phase)
                time.sleep(.25)
            check_display()
            (args.output / (phase + '-before-memory.txt')).write_text(adb('dumpsys meminfo com.termux'))
            adb('dumpsys gfxinfo com.termux reset')
            # Capture stale ring records before starting so only new frame IDs are retained.
            previous = parse_frames(adb('dumpsys gfxinfo com.termux framestats'))
            cutoff = max(previous, default=0)
            touch(phase + '.go')
            deadline = time.monotonic() + 150
            frames = {}
            injections = []
            while True:
                check_display()
                raw = adb('dumpsys gfxinfo com.termux framestats')
                frames.update({k: v for k, v in parse_frames(raw).items() if k > cutoff})
                if state() == {'phase': phase, 'state': 'done'}:
                    # Completion can race the preceding sample; collect the tail before ACK.
                    raw = adb('dumpsys gfxinfo com.termux framestats')
                    frames.update({k: v for k, v in parse_frames(raw).items() if k > cutoff})
                    break
                if time.monotonic() > deadline:
                    raise TimeoutError('Collecting ' + phase)
                if phase == 'input_echo':
                    check_display()
                    injections.append(time.monotonic_ns())
                    adb('input keyevent 29')
                time.sleep(.25 if args.local_rish else .75)
            check_display()
            (args.output / (phase + '-gfxinfo.txt')).write_text(raw)
            (args.output / (phase + '-frames.json')).write_text(json.dumps(list(frames.values())))
            (args.output / (phase + '-after-memory.txt')).write_text(adb('dumpsys meminfo com.termux'))
            (args.output / (phase + '-thermal.txt')).write_text(adb('dumpsys thermalservice'))
            summary = summarize_frames(phase, frames)
            if injections:
                (args.output / 'input-dispatch.json').write_text(json.dumps(injections))
            for label, begin, end in [('frame', 'IntendedVsync', 'FrameCompleted'),
                                      ('draw', 'DrawStart', 'SyncQueued'),
                                      ('ui', 'PerformTraversalsStart', 'SyncQueued'),
                                      ('gpu', 'SwapBuffers', 'GpuCompleted')]:
                durations = [(f[end]-f[begin])/1e6 for f in frames.values()
                             if end in f and begin in f and 0 <= f[end]-f[begin] < 1e9]
                summary[label + '_ms'] = {str(p): percentile(durations, p) for p in (.5, .9, .95, .99)}
            summaries.append(summary)
            (args.output / 'partial-summary.json').write_text(json.dumps(summaries, indent=2))
            print(json.dumps(summary), flush=True)
            touch(phase + '.ack')
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                result = json.loads(app('cat ' + shlex.quote(args.remote_dir + '/result.json')))
                (args.output / 'workload.json').write_text(json.dumps(result, indent=2))
                break
            except (OSError, ValueError, subprocess.CalledProcessError):
                time.sleep(.2)
        else:
            raise TimeoutError('Waiting for valid workload result.json')
        if args.input_phase:
            received = result.get('input_received_ns', [])
            if not injections or len(received) != len(injections):
                raise RuntimeError('Input count mismatch; keep the benchmark session focused and do not type')
            latencies = [(end - begin) / 1e6 for begin, end in zip(injections, received)]
            if any(t < 0 for t in latencies):
                raise RuntimeError('Invalid input timestamp ordering')
            (args.output / 'input-summary.json').write_text(json.dumps({
                'samples': len(latencies), 'dispatch_to_pty_ms':
                {str(p): percentile(latencies, p) for p in (.5, .9, .95, .99)},
                'note': 'Synthetic key dispatch to PTY receipt, including Shizuku/input-command startup; '
                        'not physical keyboard latency or input-to-display latency.'}, indent=2))
        empty_phases = [s['phase'] for s in summaries if s['sampled_frames'] == 0]
        if empty_phases:
            raise RuntimeError('Invalid display capture: no frames for ' + ', '.join(empty_phases)
                               + '. Keep Termux visible and the screen awake; rerun in fresh directories.')
        (args.output / 'summary.json').write_text(json.dumps(summaries, indent=2))

        complete = True
    finally:
        if not complete:
            try:
                touch('abort')
            except (OSError, subprocess.SubprocessError):
                pass


if __name__ == '__main__':
    main()
