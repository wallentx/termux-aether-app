#!/usr/bin/env bash
set -euo pipefail
out=$1
ndk=$2
mkdir -p "$out/assets/aether" "$out/jniLibs/arm64-v8a" "$out/probes"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang" \
  -std=c11 -O2 -Wall -Wextra -Werror -fPIE -pie -DAETHER_BIONIC scripts/aether/launcher.c scripts/aether/exec.c -ldl \
  -o "$out/jniLibs/arm64-v8a/libaether-run.so"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang" \
  -std=c11 -O2 -Wall -Wextra -Werror -DAETHER_BIONIC -fPIC -shared \
  scripts/aether/exec.c -ldl -o "$out/jniLibs/arm64-v8a/libaether-exec.so"
aarch64-linux-gnu-gcc -std=c11 -O2 -Wall -Wextra -Werror -Wno-nonnull-compare \
  -fPIC -shared scripts/aether/compat.c scripts/aether/exec.c scripts/aether/system.c -pthread -ldl -o "$out/assets/aether/libaether-compat.so"
aarch64-linux-gnu-gcc -std=c11 -O2 -Wall -Wextra -Werror scripts/aether/probe.c -pthread -o "$out/probes/aether-probe"
cp "$out/probes/aether-probe" "$out/assets/aether/aether-probe"
python3 - "$out" <<'PY'
import hashlib,json,pathlib,sys
source=pathlib.Path('app/src/aether/assets/aether')
meta=json.loads((source/'provenance.json').read_text())
for name,expected in meta.items():
    if name.endswith(('.so.1','.so.2','.so.6','.so.0')):
        path=pathlib.Path('app/src/aether/jniLibs/arm64-v8a/libaether-loader.so') if name.startswith('ld-') else source/name
        assert hashlib.sha256(path.read_bytes()).hexdigest()==expected,name
PY
