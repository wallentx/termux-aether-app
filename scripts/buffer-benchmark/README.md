# Terminal buffer benchmark

The normal Pixel CI build produces this standalone dex jar using the APK's
compiled emulator classes. No additional APK or local build is required.

Extract the artifact into a private native Termux directory, then run:

```sh
sha256sum -c SHA256SUMS
chmod 400 buffer-benchmark.jar
CLASSPATH="$PWD/buffer-benchmark.jar" /system/bin/app_process / com.termux.terminal.BufferBenchmark baseline > baseline.json
```

Allow 10-20 seconds per run. Keep the original jar when testing an optimization,
then run baseline and candidate jars in alternating order on the same device.
Record commit IDs, raw samples and thermal readings. Compare one-operation
fingerprints as an additional smoke check; unit tests establish correctness.

The harness calls the same blockSet/blockCopy APIs on old and new builds. It
warms each workload and collects five samples lasting at least 150 ms each.
Timing excludes buffer creation, escape parsing, PTY transport and rendering.
Workloads cover clears, partial fills, single cells, row copies, overlapping
copies, scrolling-region copies and Unicode fallback paths. Native scrolling
can rotate row references instead; a region-copy result does not measure every
scrolling operation. Unicode clear timings retain the row's complex-character
flag after its contents have been cleared, exercising that fallback cost.

These are microbenchmarks with uncontrolled CPU clocks and scheduling. Do not
infer end-to-end terminal speed or specific SIMD instructions from these results.
