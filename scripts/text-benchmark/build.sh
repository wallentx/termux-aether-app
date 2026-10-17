#!/usr/bin/env bash
# CI only: reuse classes from the existing APK build.
set -euo pipefail
out=${1:?output directory required}
classes=terminal-emulator/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes
android_jar="$ANDROID_HOME/platforms/android-37.2/android.jar"
mkdir -p "$out/classes" "$out/dex" "$out/artifact"
jar cf "$out/emulator.jar" -C "$classes" .
javac -source 8 -target 8 -cp "$android_jar:$out/emulator.jar" \
    -d "$out/classes" scripts/text-benchmark/TextBenchmark.java
jar cf "$out/benchmark.jar" -C "$out/classes" .
"$ANDROID_HOME/build-tools/37.0.0/d8" --min-api 37 --lib "$android_jar" \
    --output "$out/dex" "$out/emulator.jar" "$out/benchmark.jar"
jar cf "$out/artifact/text-benchmark.jar" -C "$out/dex" .
git rev-parse HEAD > "$out/artifact/commit.txt"
cp scripts/text-benchmark/README.md "$out/artifact/README.md"
(cd "$out/artifact" && sha256sum text-benchmark.jar commit.txt README.md > SHA256SUMS)
