#!/usr/bin/env python3
"""Exercise real Android process launches, rather than only Go --version."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def main():
    root = Path(sys.argv[1]).resolve()
    env = os.environ.copy()
    env.update(GOROOT=str(root), GOTOOLCHAIN='local', GOENV='off', GOTELEMETRY='off')
    env.pop('GOFLAGS', None)
    test = Path(tempfile.mkdtemp(prefix='aether-go-tests-', dir=os.environ['TMPDIR']))
    passed = []

    def run(args, expected=0, **kwargs):
        result = subprocess.run([str(x) for x in args], env=kwargs.pop('env', env),
                                capture_output=True, text=True, timeout=120, **kwargs)
        if result.returncode != expected:
            raise AssertionError(f'{args}: exit={result.returncode}\n{result.stdout}\n{result.stderr}')
        return result.stdout

    for cgo in ('0', '1'):
        buildenv = dict(env, CGO_ENABLED=cgo)
        source = Path(__file__).with_name('probe.go').read_text()
        if cgo == '1':
            source = source.replace('import (', '/* int answer(void) { return 42; } */\nimport "C"\n\nimport (')
            source = source.replace('func main() {', 'func main() {\n if C.answer() != 42 { panic("cgo") }')
        src = test / ('probe'+cgo+'.go')
        src.write_text(source)
        exe = test / ('probe'+cgo)
        run([root/'bin/go', 'build', '-p', '4', '-o', exe, src], env=buildenv)
        for args in ([], ['first', 'one two', '', '--flag']):
            value = json.loads(run([exe, *args]))
            assert value['Args'] == [str(exe), *args], value
            assert value['Executable'] == str(exe), value
        value = json.loads(run(['/system/bin/linker64', exe, 'manual', '']))
        assert value['Args'] == [str(exe), 'manual', ''], value
        assert value['Executable'] == str(exe), value
        for mode, first, tail in [('spawn', 'child-alias', ['report', 'one two', '', 'first']),
                                  ('exec', 'reexec-alias', ['report', 'one two', ''])]:
            value = json.loads(run([exe, mode]))
            # C startup receives the executable path from Android's linker.
            assert value['Args'][0] == (first if cgo == '0' else str(exe)), value
            assert value['Args'][1:] == tail, value
            assert value['Executable'] == str(exe), value
        assert run([exe, 'command', '/system/bin/sh', '-c', 'printf system-ok']) == 'system-ok'
        assert run([exe, 'command', os.environ['PREFIX']+'/bin/printf', '%s|%s', 'one two', '']) == 'one two|'
        script = test/'script'
        script.write_text('#!/bin/sh -e\nprintf "%s|%s" "$1" "$2"\n')
        script.chmod(0o700)
        assert run([exe, 'command', script, 'space arg', '']) == 'space arg|'
        relativeenv = dict(env, AETHER_TEST_DIR=str(test))
        assert run([exe, 'command', './script', 'relative', ''], env=relativeenv) == 'relative|'
        # The optional shebang argument is a single string, including -S and
        # its arguments. The native env interpreter expands it, not this patch.
        script.write_text('#!/usr/bin/env -S sh -e\nprintf "%s" "$1"\n')
        assert run([exe, 'command', script, 'env-shebang']) == 'env-shebang'
        # A path that names a directory, a full unterminated shebang, and a
        # recursive interpreter must fail in a bounded way.
        run([exe, 'command', test], expected=1)
        script.write_text('#!/'+('a'*300))
        run([exe, 'command', script], expected=1)
        script.write_text('#!'+str(script)+'\n')
        run([exe, 'command', script], expected=1)
        script.chmod(0o600)
        run([exe, 'command', script], expected=1)
        run([exe, 'command', test/'does-not-exist'], expected=1)
        passed.append('CGO_ENABLED='+cgo+': argv, executable, fork/exec, reexec, system, shebang, cwd, permissions')
        print(passed[-1], flush=True)
    (test/'go.mod').write_text('module aether.example/probe\n\ngo 1.27\n')
    (test/'basic_test.go').write_text('package probe\nimport "testing"\nfunc TestBasic(t *testing.T) {}\n')
    # Keep generated main programs out of this separate package.
    for path in test.glob('probe*.go'):
        path.rename(path.with_suffix('.txt'))
    run([root/'bin/go', 'test', '-count=1', '.'], cwd=test)
    passed.append('go test builds and executes its test binary')
    (test/'results.json').write_text(json.dumps(passed, indent=2))
    print('PASS:', test, flush=True)


if __name__ == '__main__':
    main()
