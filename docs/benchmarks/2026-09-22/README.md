# Pixel rendering and width benchmarks - September 22, 2026

The installed terminal APK reported version `1000.0.0`; it did not contain the new
ASCII width fast path. Each frame phase produced 600 updates in 10 seconds.
Runs used different terminal geometry: 83x49 for run 1, 83x74 for run 2. Refresh
intervals also varied between 8.33 and 16.67 ms. Do not compare these as an A/B
optimization trial or treat producer updates as displayed frames.

| Run | Phase | Android rendered frames | Android janky frames | Sampled frame p95, ms |
| --- | --- | ---: | --- | ---: |
| 1 | text_scroll | 343 | 0 (0.00%) | 11.119 |
| 1 | image_redraw | 390 | 0 (0.00%) | 10.408 |
| 1 | image_replace | 600 | 4 (0.67%) | 9.846 |
| 2 | text_scroll | 599 | 1 (0.17%) | 11.051 |
| 2 | image_redraw | 599 | 0 (0.00%) | 9.677 |
| 2 | image_replace | 566 | 0 (0.00%) | 8.399 |

Run 1 used the original ADB collector through a temporary on-device transport.
Run 2 used direct local handshake reads and Shizuku collection, with sampled
focus/unlock/rotation checks. Its three rendering phases completed; the later
optional input phase failed at the Android input shell service. It has **no valid
input latency result** and is not a successful four-phase run. The current harness
preflights this command and preserves partial summaries on later failure.

Raw frame timestamps are gzip-compressed without modification. `phases.json`
contains the saved per-phase summaries; `workload.json` records the producer.
`*-gfxinfo.txt` retains Android aggregate counters, which differ from the sampled
ring-buffer frame counts. Memory/thermal snapshots remain in the local report at
`~/.local/state/termux-aether/render-20260922/`; clocks and thermals were uncontrolled.
No screen-lock settings were changed, and test sessions exited after measurement.

`pixel11-width.json` contains a separate, paired Android ART microbenchmark and
source hashes. Its 1.42x ASCII / 1.22x mixed-text gains are width-classification
throughput, not measured terminal frame-rate improvements. The full Unicode
range and four out-of-range integer boundaries matched the retained baseline.
See [methods and limits](../../PERFORMANCE.md#printable-ascii-width-classification-september-22-2026).
