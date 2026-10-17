#!/usr/bin/env python3
"""Validate the installed Aether runtime from native Termux; optionally run Geekbench CPU."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time


def snapshot():
    try:
        p = subprocess.run(['termux-capabilities', '--json'], capture_output=True, text=True, timeout=25)
        return json.loads(p.stdout) if p.returncode == 0 else {'error': p.stderr}
    except Exception as e:
        return {'error': str(e)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--probe', type=Path, default=Path.home() / 'aether-prototype/aether-probe')
    parser.add_argument('--cpu', action='store_true', help='Run the full CPU benchmark (preview uploads results)')
    parser.add_argument('--geekbench', type=Path, default=Path.home() / 'Geekbench-7.0.0-LinuxARMPreview/geekbench_aarch64')
    parser.add_argument('--output', type=Path, default=Path.home() / 'benchmarks' / time.strftime('aether-%Y%m%d-%H%M%S'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    report = {'started_at': time.time(), 'uid': os.getuid(), 'before': snapshot(), 'checks': []}
    try:
        report['selinux'] = Path('/proc/self/attr/current').read_text().strip('\0\n')
        tasks = [('probe', ['aether-run', str(args.probe.resolve())]),
                 ('sysinfo', ['aether-run', str(args.geekbench.resolve()), '--sysinfo'])]
        if args.cpu:
            tasks.append(('cpu', ['aether-run', str(args.geekbench.resolve()), '--cpu']))
        for name, command in tasks:
            print(f'Running {name}; output: {args.output / (name + ".log")}', flush=True)
            started = time.monotonic()
            with (args.output / (name + '.log')).open('w') as log:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                        timeout=1200 if name == 'cpu' else 60)
            report['checks'].append({'name': name, 'returncode': result.returncode,
                                    'seconds': time.monotonic() - started})
            (args.output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
            if result.returncode:
                raise RuntimeError(f'{name} failed with exit {result.returncode}; inspect its log')
        report['passed'] = True
    except BaseException as error:
        report['passed'] = False
        report['error'] = repr(error)
        raise
    finally:
        report['after'] = snapshot()
        report['finished_at'] = time.time()
        (args.output / 'results.json').write_text(json.dumps(report, indent=2) + '\n')
        print(args.output / 'results.json', flush=True)


if __name__ == '__main__':
    main()
