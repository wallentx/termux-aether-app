// Copyright 2026 The Termux Aether Authors.
// SPDX-License-Identifier: BSD-3-Clause

package syscall

import (
	"internal/byteorder"
	"internal/stringslite"
)

const aetherSelfExe = "TERMUX_EXEC__PROC_SELF_EXE="
const aetherPrefix = "/data/data/com.termux/files/usr"

// aetherExec prepares arguments before fork, where allocation and file I/O are
// safe. Go's raw execve bypasses the termux-exec libc interceptor.
func aetherExec(name string, argv, env []string, dir string) (string, []string, []string, error) {
	mode, _ := Getenv("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE")
	intercept, _ := Getenv("TERMUX_EXEC__EXECVE_CALL__INTERCEPT")
	if mode == "disable" || mode == "0" || intercept == "disable" || intercept == "0" {
		return name, argv, aetherExecEnv(env, ""), nil
	}
	if name == "" || stringslite.IndexByte(name, 0) >= 0 {
		return name, argv, env, nil // preserve the normal exec error
	}
	// Validate before opening paths or rewriting argv; never discard an invalid
	// caller-supplied argv[0] or environment entry.
	for _, values := range [][]string{argv, env} {
		for _, value := range values {
			if stringslite.IndexByte(value, 0) >= 0 {
				return "", nil, nil, EINVAL
			}
		}
	}
	var err error
	name, err = aetherExecPath(name, dir)
	if err != nil {
		return "", nil, nil, err
	}
	for depth := 0; depth < 5; depth++ {
		// System executables already have kernel execution permission. Do not
		// wrap the linker itself or inherit another executable's identity.
		if stringslite.HasPrefix(name, "/system/") || stringslite.HasPrefix(name, "/apex/") || stringslite.HasPrefix(name, "/vendor/") {
			return name, argv, aetherExecEnv(env, ""), nil
		}
		fd, err := Open(name, O_RDONLY|O_CLOEXEC, 0)
		if err != nil {
			return "", nil, nil, err
		}
		var st Stat_t
		err = Fstat(fd, &st)
		if err == nil && (st.Mode&S_IFMT != S_IFREG || st.Mode&0111 == 0) {
			err = EACCES
		}
		if err == nil {
			err = Access(name, 1) // honor executable permissions
		}
		var header [256]byte
		var n int
		if err == nil {
			n, err = Pread(fd, header[:], 0)
		}
		var linker string
		if err == nil && n >= 64 && string(header[:4]) == "\x7fELF" {
			linker, err = aetherELFLinker(fd, header[:n])
		}
		Close(fd)
		if err != nil {
			return "", nil, nil, err
		}
		if linker != "" {
			arg0 := name
			if len(argv) != 0 {
				arg0 = argv[0]
			}
			wrapped := []string{arg0, name}
			if len(argv) > 1 {
				wrapped = append(wrapped, argv[1:]...)
			}
			return linker, wrapped, aetherExecEnv(env, name), nil
		}
		if n < 2 || string(header[:2]) != "#!" {
			return name, argv, aetherExecEnv(env, ""), nil
		}
		line := header[2:n]
		end := stringslite.IndexByte(string(line), '\n')
		if end >= 0 {
			line = line[:end]
		} else if n == len(header) {
			return "", nil, nil, ENOEXEC // don't truncate an interpreter path
		}
		text := aetherTrimSpace(string(line))
		end = 0
		for end < len(text) && text[end] != ' ' && text[end] != '\t' {
			end++
		}
		if end == 0 {
			return "", nil, nil, ENOEXEC
		}
		interpreter, err := aetherExecPath(text[:end], dir)
		if err != nil {
			return "", nil, nil, err
		}
		next := []string{interpreter}
		if arg := aetherTrimSpace(text[end:]); arg != "" {
			next = append(next, arg) // Linux passes the optional shebang arg intact
		}
		next = append(next, name)
		if len(argv) > 1 {
			next = append(next, argv[1:]...)
		}
		name, argv = interpreter, next
	}
	return "", nil, nil, ELOOP
}

func aetherTrimSpace(s string) string {
	for len(s) > 0 && (s[0] == ' ' || s[0] == '\t') {
		s = s[1:]
	}
	for len(s) > 0 && (s[len(s)-1] == ' ' || s[len(s)-1] == '\t') {
		s = s[:len(s)-1]
	}
	return s
}

func aetherExecPath(name, dir string) (string, error) {
	if stringslite.HasPrefix(name, "/bin/") {
		return aetherPrefix + name, nil
	}
	if stringslite.HasPrefix(name, "/usr/bin/") {
		return aetherPrefix + name[4:], nil
	}
	if name[0] == '/' {
		return name, nil
	}
	if dir == "" || dir[0] != '/' {
		wd, err := Getwd()
		if err != nil {
			return "", err
		}
		if dir != "" {
			wd += "/" + dir
		}
		dir = wd
	}
	// Do not lexically clean '..': symlink traversal must retain kernel semantics.
	return dir + "/" + name, nil
}

func aetherExecEnv(env []string, executable string) []string {
	result := make([]string, 0, len(env)+1)
	for _, value := range env {
		if !stringslite.HasPrefix(value, aetherSelfExe) {
			result = append(result, value)
		}
	}
	if executable != "" {
		result = append(result, aetherSelfExe+executable)
	}
	return result
}

func aetherELFLinker(fd int, header []byte) (string, error) {
	// Only native ARM64 PIEs using Android's loader belong on this path.
	if header[4] != 2 || header[5] != 1 || byteorder.LEUint16(header[16:]) != 3 || byteorder.LEUint16(header[18:]) != 183 {
		return "", nil
	}
	offset := byteorder.LEUint64(header[32:])
	size, count := byteorder.LEUint16(header[54:]), byteorder.LEUint16(header[56:])
	if size != 56 || count > 1024 || offset > 1<<40 {
		return "", ENOEXEC
	}
	var ph [56]byte
	for i := uint16(0); i < count; i++ {
		n, err := Pread(fd, ph[:], int64(offset)+int64(i)*56)
		if err != nil {
			return "", err
		}
		if n != len(ph) {
			return "", ENOEXEC
		}
		if byteorder.LEUint32(ph[:]) != 3 { // PT_INTERP
			continue
		}
		pos, length := byteorder.LEUint64(ph[8:]), byteorder.LEUint64(ph[32:])
		if length < 2 || length > 128 || pos > 1<<40 {
			return "", ENOEXEC
		}
		var value [128]byte
		n, err = Pread(fd, value[:length], int64(pos))
		if err != nil {
			return "", err
		}
		if uint64(n) != length || value[n-1] != 0 {
			return "", ENOEXEC
		}
		linker := string(value[:n-1])
		if linker == "/system/bin/linker64" || linker == "/apex/com.android.runtime/bin/linker64" {
			return linker, nil
		}
		return "", nil
	}
	return "", nil
}
