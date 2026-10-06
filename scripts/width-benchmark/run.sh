#!/usr/bin/env bash
# Small standalone ART benchmark; no APK, Gradle, or guest-kernel build.
set -euo pipefail
baseline=${1:?usage: run.sh BASELINE_COMMIT NEW_OUTPUT_DIRECTORY}
out=${2:?new output directory required}
mkdir "$out"
out=$(cd "$out" && pwd)
mkdir -p "$out/src/com/termux/terminal" "$out/classes"
src=terminal-emulator/src/main/java/com/termux/terminal/WcWidth.java
git show "$baseline:$src" | sed 's/public final class WcWidth/public final class WcWidthBaseline/' \
    > "$out/src/com/termux/terminal/WcWidthBaseline.java"
cp "$src" "$out/src/com/termux/terminal/WcWidth.java"
cp scripts/width-benchmark/WidthBenchmark.java "$out/src/"
javac --release 8 -d "$out/classes" "$out/src/com/termux/terminal/"*.java "$out/src/WidthBenchmark.java"
dx --dex --output="$out/width-benchmark.jar" "$out/classes"
chmod 0400 "$out/width-benchmark.jar"
git rev-parse "$baseline" > "$out/baseline-commit.txt"
sha256sum "$out/src/com/termux/terminal/"*.java "$out/src/WidthBenchmark.java" \
    "$out/width-benchmark.jar" > "$out/SHA256SUMS"
dalvikvm -cp "$out/width-benchmark.jar" WidthBenchmark > "$out/results.jsonl"
cat "$out/results.jsonl"
