# Terminal text throughput benchmark

CI emits a `text-benchmark` dex jar from the existing APK's emulator classes.
Keep both the baseline and candidate artifacts; the harness is unchanged across
the comparison. No extra APK or local build is required.

Run in native Termux on the Pixel:

```sh
sha256sum -c SHA256SUMS
chmod 400 text-benchmark.jar
CLASSPATH="$PWD/text-benchmark.jar" /system/bin/app_process / com.termux.terminal.TextBenchmark baseline > baseline.json
```

Allow 10-30 seconds per run. Compare old/new/new/old runs and retain thermal
snapshots and all raw samples. Each workload is warmed before five samples of
at least 150 ms. Throughput includes append parsing, buffer updates, wrapping
and scrolling; it excludes setup, PTY I/O and rendering. The Unicode workload
retains complex rows, deliberately measuring the fallback path.

Check one-batch fingerprints across artifacts; unit tests additionally compare
batched input against one-byte input including terminal state and callbacks.
Do not infer whole-app speed or particular SIMD instructions from these timings.
