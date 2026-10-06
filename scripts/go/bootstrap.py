#!/usr/bin/env python3
"""Stage the installed ARM64 Go toolchain without touching installed files.

The old pure-Go binaries cannot parse linker64's argv. Adjust only private
bootstrap copies, after checking their exact startup instructions. Every shipped
binary must instead be rebuilt from the patched Go source.
"""
import os
from pathlib import Path
import shutil
import struct
import sys


def bootstrap_binary(source, destination):
    data = bytearray(source.read_bytes())
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError(f"not an ARM64 little-endian ELF: {source}")
    entry, phoff = struct.unpack_from("<QQ", data, 24)
    phsize, phnum = struct.unpack_from("<HH", data, 54)
    segments = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize)
                for i in range(phnum)]

    def offset(address):
        for kind, _, pos, va, _, size, _, _ in segments:
            if kind == 1 and va <= address < va + size:
                return pos + address - va
        raise ValueError("entry point outside file segments")

    for _ in range(4):
        pos = offset(entry)
        word = struct.unpack_from("<I", data, pos)[0]
        if word >> 26 != 5:
            break
        displacement = word & 0x3ffffff
        if displacement & (1 << 25):
            displacement -= 1 << 26
        entry += displacement * 4
    words = struct.unpack_from("<IIII", data, pos)
    # ldr x0,[sp]; add x1,sp,#8; b runtime.rt0_go; zero padding.
    if words[:2] != (0xf94003e0, 0x910023e1) or words[2] >> 26 != 5 or words[3] != 0:
        raise ValueError(f"unrecognized Go startup; refusing to patch {source}")
    branch = 0x14000000 | (((words[2] & 0x3ffffff) - 1) & 0x3ffffff)
    # Preserve the environment position while removing the linker's argv[0].
    struct.pack_into("<IIII", data, pos, 0xf94003e0, 0xd1000400, 0x910043e1, branch)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    destination.chmod(0o700)


def main():
    root = Path(sys.argv[1]).resolve()
    source = Path(os.environ["PREFIX"]) / "lib/go"
    if root.exists():
        raise SystemExit(f"refusing to overwrite existing stage: {root}")
    shutil.copytree(source, root / "go", symlinks=True)
    for directory in ("bin", "pkg/tool/android_arm64"):
        for binary in (source / directory).iterdir():
            if binary.is_file():
                bootstrap_binary(binary, root / "bootstrap" / binary.name)
    (root / "toolexec.sh").write_text(
        '#!/system/bin/sh\n'
        'tool="$1"; shift\n'
        'exec /system/bin/linker64 "$AETHER_GO_BOOTSTRAP/${tool##*/}" "$@"\n')
    print(root)


if __name__ == "__main__":
    main()
