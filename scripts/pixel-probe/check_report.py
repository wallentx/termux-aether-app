#!/usr/bin/env python3
"""Validate app-context probe evidence; never equate a smoke test with migration readiness."""
import argparse
import json
import sys


def problems(report, require_bootstrap=False):
    errors = []
    if not isinstance(report, dict):
        return ['report must be an object']
    if report.get('schema_version') != 1:
        errors.append('unsupported schema_version')
    if report.get('package') != 'com.termux':
        errors.append('not the com.termux app context')
    uid = report.get('uid')
    if type(uid) is not int or uid < 10000:
        errors.append('UID is not an app UID; ADB shell execution is insufficient')
    if report.get('target_sdk') != 37 or report.get('sdk', 0) < 37:
        errors.append('expected target 37 on Android API 37 or later')
    if report.get('is_64_bit') is not True:
        errors.append('expected a 64-bit process')
    cpu = report.get('cpu')
    if not isinstance(cpu, dict):
        return errors + ['missing CPU report']
    if cpu.get('architecture') != 'aarch64':
        errors.append('expected aarch64 CPU report')
    for name in ('hwcap', 'hwcap2'):
        if cpu.get(name + '_errno') != 0:
            errors.append(name + ' could not be read')
        try:
            value = cpu.get(name)
            if not isinstance(value, str) or not value.startswith('0x') or int(value, 16) < 0:
                raise ValueError()
        except (ValueError, TypeError):
            errors.append(name + ' must be a hex string')
    features = cpu.get('features', {})
    if not isinstance(features, dict):
        return errors + ['features must be an object']
    for name in ('asimd', 'sve', 'sve2', 'sme', 'sme2', 'i8mm', 'bf16'):
        if type(features.get(name)) is not bool:
            errors.append('missing boolean feature: ' + name)
    if features.get('asimd') is not True:
        errors.append('ARM64 NEON baseline missing')
    for name in ('sve', 'sme'):
        vector = cpu.get(name, {})
        if not isinstance(vector, dict):
            errors.append(name + ' vector report missing')
            continue
        length = vector.get('vector_length_bytes')
        if features.get(name) is True:
            if vector.get('query_errno') != 0 or type(length) is not int or length < 16 or length % 16:
                errors.append(name + ' is advertised but vector length is unverified')
        elif length is not None:
            errors.append(name + ' length reported without feature support')
    rows = report.get('execution')
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        return errors + ['execution must be a list of objects']
    results = {}
    for row in rows:
        name = row.get('name')
        if not isinstance(name, str) or name in results:
            errors.append('missing or duplicate execution name')
            continue
        results[name] = row

    def success(name, marker=None):
        row = results.get(name, {})
        if (row.get('error') or row.get('exit_code') != 0 or row.get('signal') != 0
                or row.get('timed_out') is not False or row.get('truncated') is not False
                or row.get('wait_errno') != 0 or row.get('read_errno') != 0):
            errors.append(name + ': no clean successful execution')
        if marker and marker not in row.get('output', ''):
            errors.append(name + ': expected output marker missing')

    success('system_control')
    success('private_linker')
    direct = results.get('private_direct', {})
    if (type(direct.get('exit_code')) is not int or direct['exit_code'] == 0
            or 'Permission denied' not in direct.get('output', '')
            or direct.get('timed_out') is not False or direct.get('signal') != 0
            or direct.get('wait_errno') != 0 or direct.get('read_errno') != 0):
        errors.append('private_direct: expected app-data execution denial was not established')
    if require_bootstrap:
        if report.get('bootstrap_shell_present') is not True:
            errors.append('bootstrap shell is missing; full bootstrap smoke tests did not run')
        if report.get('termux_exec_present') is not True:
            errors.append('termux-exec preload is missing')
        success('bootstrap_linker', 'pixel-shell-ok')
        success('bootstrap_preload_child', 'pixel-child-ok')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report')
    parser.add_argument('--require-bootstrap', action='store_true')
    args = parser.parse_args()
    try:
        with open(args.report, encoding='utf-8') as source:
            report = json.load(source)
        errors = problems(report, args.require_bootstrap)
    except (OSError, ValueError, TypeError) as failure:
        print('Invalid report: ' + str(failure), file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print('FAIL: ' + error, file=sys.stderr)
        return 1
    scope = 'including bootstrap child execution' if args.require_bootstrap else 'capabilities and fixture execution only'
    print('PASS: ' + scope + '; package migration and SIMD performance remain unverified.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
