#!/usr/bin/env bash
# CI-only build: Android NDK clang or Linux aarch64 GCC for QEMU correctness tests.
set -euo pipefail
: "${CC:?Set CC to the ARM64 compiler}"
out=${1:?Usage: build.sh OUTPUT_DIRECTORY}
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
mkdir -p "$out"
common=(-std=c11 -O3 -Wall -Wextra -Werror -fPIE -fno-lto)
scalar_flags=(-fno-tree-vectorize -fno-tree-slp-vectorize)
if "$CC" --version | head -1 | grep -qi clang; then
    scalar_flags=(-fno-vectorize -fno-slp-vectorize)
fi
"$CC" "${common[@]}" -march=armv8-a "${scalar_flags[@]}" -c "$source_dir/scalar.c" -o "$out/scalar.o"
"$CC" "${common[@]}" -march=armv8-a -c "$source_dir/main.c" -o "$out/main.o"
"$CC" "${common[@]}" -march=armv8-a+simd -c "$source_dir/neon.c" -o "$out/neon.o"
"$CC" "${common[@]}" -march=armv8-a+sve2 -c "$source_dir/sve2.c" -o "$out/sve2.o"
"$CC" -fno-lto -pie "$out/main.o" "$out/scalar.o" "$out/neon.o" "$out/sve2.o" -o "$out/pixel-simd-bench"
