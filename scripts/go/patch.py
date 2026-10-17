#!/usr/bin/env python3
"""Apply the Aether Android/ARM64 source changes to a private Go source tree."""
from pathlib import Path
import shutil
import sys


def replace(root, name, old, new):
    path = root / 'src' / name
    text = path.read_text()
    if text.count(old) != 1:
        raise ValueError(f'expected one patch anchor in {name}')
    path.write_text(text.replace(old, new))


def main():
    root = Path(sys.argv[1]).resolve()
    replace(root, 'runtime/runtime1.go', 'func goargs() {', '''// aetherLinkerEntry is set from the kernel's AT_EXECFN, not from an
// inherited environment variable. Direct kernel starts must retain argv[1].
var aetherLinkerEntry bool

func goargs() {''')
    replace(root, 'runtime/runtime1.go', '''	argslice = make([]string, argc)
	for i := int32(0); i < argc; i++ {
		argslice[i] = gostringnocopy(argv_index(argv, i))
	}''', '''	adjust := int32(0)
	if GOOS == "android" && GOARCH == "arm64" && !iscgo && aetherLinkerEntry && argc >= 2 {
		adjust = 1
	}
	argslice = make([]string, argc-adjust)
	for i := int32(0); i < argc-adjust; i++ {
		index := i
		if i > 0 {
			index += adjust
		}
		argslice[i] = gostringnocopy(argv_index(argv, index))
	}
	if adjust != 0 && (argslice[0] == "/system/bin/linker64" || argslice[0] == "/apex/com.android.runtime/bin/linker64") {
		argslice[0] = gostringnocopy(argv_index(argv, 1))
	}''')
    replace(root, 'runtime/os_linux.go', '''		case _AT_RANDOM:''', '''		case 31: // AT_EXECFN: the pathname the kernel actually executed
			if GOOS == "android" && GOARCH == "arm64" && val != 0 {
				name := gostringnocopy((*byte)(unsafe.Pointer(val)))
				aetherLinkerEntry = name == "/system/bin/linker64" || name == "/apex/com.android.runtime/bin/linker64"
			}
		case _AT_RANDOM:''')
    replace(root, 'os/executable_procfs.go', '''	path, err := Readlink(procfn)
''', '''	path, err := Readlink(procfn)
	if runtime.GOOS == "android" && runtime.GOARCH == "arm64" && err == nil &&
		(path == "/system/bin/linker64" || path == "/apex/com.android.runtime/bin/linker64") {
		// The exec interceptor supplies the actual ELF, including when argv[0]
		// is an alias. Manual linker invocations can fall back to adjusted Args.
		name := Getenv("TERMUX_EXEC__PROC_SELF_EXE")
		if name == "" && len(Args) > 0 {
			name = Args[0]
		}
		if len(name) > 0 && name[0] == '/' && name != path {
			if st, statErr := Stat(name); statErr == nil && st.Mode().IsRegular() {
				return name, nil
			}
		}
	}
''')
    replace(root, 'syscall/exec_unix.go', '''	// Convert args to C form.
''', '''	// Prepare Android linker execution before fork; do not reinterpret paths
	// in a requested chroot or under a different credential.
	if sys.Chroot == "" && sys.Credential == nil {
		copyAttr := *attr
		argv0, argv, copyAttr.Env, err = aetherExec(argv0, argv, attr.Env, attr.Dir)
		if err != nil {
			return 0, err
		}
		attr = &copyAttr
	}
	// Convert args to C form.
''')
    replace(root, 'syscall/exec_unix.go', '''func Exec(argv0 string, argv []string, envv []string) (err error) {
''', '''func Exec(argv0 string, argv []string, envv []string) (err error) {
	argv0, argv, envv, err = aetherExec(argv0, argv, envv, "")
	if err != nil {
		return err
	}
''')
    shutil.copyfile(Path(__file__).with_name('exec_android_arm64.go'), root / 'src/syscall/exec_android_arm64.go')
    (root / 'src/syscall/exec_aether_other.go').write_text('''//go:build unix && (!android || !arm64)

package syscall

func aetherExec(name string, argv, env []string, dir string) (string, []string, []string, error) {
	return name, argv, env, nil
}
''')


if __name__ == '__main__':
    main()
