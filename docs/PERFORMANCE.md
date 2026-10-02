# Measured terminal improvements

Measurements were collected on a Pixel 11 Pro XL running Android 17 / API 37 on
September 19, 2026, from native Termux. CI produced the Android benchmark code;
neither phone compiled it locally. The JSON files in
[benchmarks/2026-09-19](benchmarks/2026-09-19) retain the original samples.

## What these comparisons establish

The baselines are the previous implementations in this fork, or an explicitly
retained reference loop. They isolate changes to terminal operations. They are
**not a stock Termux APK versus Termux-Æther APK comparison**, do not measure
battery energy, and do not establish a whole-terminal speedup.

| Comparison | Baseline | Optimized version | Method |
| --- | --- | --- | --- |
| ASCII parsing | `7c2a9409` | `536f96b6` | Old/new/new/old; median of 10 samples per variant |
| Buffer fills and copies | `27cc183d` | `2c36f451` | Old/new/new/old; median of 10 samples per variant |
| Sixel row writes | Former per-pixel writer with the same bounds/cursor handling | `67218de5` | Five alternating samples per implementation |
| Bitmap resize copying | Default Canvas copy | SRC Canvas in `80cc1cdc` | Five samples per method in alternating order |

Each workload was warmed. Text, buffer, and sixel samples ran for at least 150 ms;
bitmap-copy samples ran for at least 200 ms. Clocks, CPU placement, and background
scheduling were uncontrolled. Correctness checks compare pixels or output/state
fingerprints, supplemented by CI regression tests. Small differences in unchanged
fallback paths should be treated as noise.

## Results

| Text workload | Before MiB/s | After MiB/s | Throughput ratio |
| --- | ---: | ---: | ---: |
| ASCII, 64 KiB chunks | 23.97 | 417.31 | 17.41x |
| ASCII, 4 KiB chunks | 23.98 | 416.98 | 17.39x |
| Log lines | 24.05 | 349.89 | 14.55x |
| Colored lines | 26.04 | 174.89 | 6.72x |
| Single-byte input (control) | 23.31 | 23.27 | 1.00x |

Text timing includes parsing, buffer writes, wrapping, and scrolling; it excludes
PTY I/O and display rendering. Mixed Unicode, insert mode, nowrap, and line-drawing
controls were within roughly 0–3% of the baseline. The plain ASCII payload is
65 KiB overall, delivered in the named chunk sizes.

| Buffer workload | Before us/op | After us/op | Speedup |
| --- | ---: | ---: | ---: |
| Clear 80x24 | 27.133 | 0.843 | 32.17x |
| Clear 160x48 | 107.996 | 2.706 | 39.90x |
| Copy 160-column row | 2.409 | 0.058 | 41.78x |
| Copy 160x48 scrolling region | 112.740 | 2.732 | 41.27x |
| Unicode row copy (control) | 72.019 | 71.908 | 1.00x |

Buffer timing excludes allocation, parsing, PTY I/O, and rendering. Some terminal
scrolls rotate row references instead of copying cells, so the region-copy result
does not describe every scroll. Unicode fallback was effectively unchanged.

| Sixel workload | Before ms/image | After ms/image | Speedup |
| --- | ---: | ---: | ---: |
| Single columns (control) | 6.005 | 5.925 | 1.01x |
| Repeats of 16 | 6.252 | 0.892 | 7.01x |
| Repeats of 1024 | 5.825 | 0.092 | 63.51x |
| Repeats of 8192 | 13.620 | 0.152 | 89.87x |
| Overpainting | 11.703 | 0.523 | 22.39x |

Sixel timing includes allocation, palette selection, and bitmap decoding. It
excludes the outer escape parser, PTY transport, and Canvas/GPU rendering. Every
workload passed pixel, dimensions, and cursor-state comparisons. Long repeat spans
benefit most because batching removes thousands of per-pixel calls.

| Bitmap size change | Default Canvas ms | SRC Canvas ms | Time reduction |
| --- | ---: | ---: | ---: |
| 1024x96 to 1124x196 | 0.238 | 0.182 | 24% |
| 1060x508 to 1060x610 | 1.142 | 0.701 | 39% |
| 1060x610 to 972x612 | 1.278 | 0.856 | 33% |
| 1920x1080 to 1920x1180 | 4.453 | 2.720 | 39% |

Bitmap timing includes destination allocation and copy, not terminal parsing or
UI drawing. Pixel equality includes partial alpha. These Java/Bitmap optimizations
do not establish which SIMD instructions Android's runtime or graphics libraries
selected.

## Reproduce and interpret

The unchanged harnesses and run instructions are in
[text-benchmark](../scripts/text-benchmark/README.md),
[buffer-benchmark](../scripts/buffer-benchmark/README.md), and
[sixel-benchmark](../scripts/sixel-benchmark/README.md) (including bitmap copies).
Compare the recorded baseline/candidate commits on the same device; keep all raw
samples and power/thermal metadata. The [raw sample directory](benchmarks/2026-09-19)
contains the four text runs, four buffer runs, sixel comparison, and bitmap comparison.
File names preserve candidate commit IDs and run order labels.

A native Aether Geekbench run demonstrates compatibility, not a measured gain over
stock Termux. Earlier native, shell, and Arch scores had different battery/thermal
conditions. A matched AVF-versus-PRoot comparison remains outstanding. Existing
frame traces also do not justify a general FPS, lower-memory-use, or battery-life
claim; some captures were affected by redraw/rotation problems.

## Installed native-package audit: September 21, 2026

These results audit libraries already installed on the Pixel; they are not gains
introduced by a new package build. Runs used the native Termux app UID 10445.
Raw samples and library identities are saved in
[SHA-256 dispatch](benchmarks/2026-09-21/pixel11-sha256-dispatch.json) and
[JPEG/compression audit](benchmarks/2026-09-21/pixel11-native-package-audit.json).

OpenSSL 3.6.3 SHA-256 measured 2382.33 MiB/s with normal dispatch versus 259.57
MiB/s with the child-only ARM capability mask set to zero: **9.18x**. Digests
matched. This exercises ARM cryptographic acceleration, not general-purpose SIMD.

Installed libjpeg-turbo 3.2.0 was compared with its SIMD modules disabled using
the [upstream-supported override](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/simd/README.md).
Five paired samples alternated normal/disabled order. Rates use uncompressed RGB
bytes for a synthetic 1023x769 image, quality 90, 4:2:0 subsampling and accurate DCT.
Both encoded bytes and decoded pixel hashes matched across all samples.

| JPEG operation | SIMD disabled MiB/s | Normal MiB/s | Throughput ratio |
| --- | ---: | ---: | ---: |
| Encode | 339.01 | 512.59 | 1.51x |
| Decode | 281.41 | 399.58 | 1.42x |

The separate compression comparison used installed zlib 1.3.2 and libdeflate 1.26,
with reusable output buffers and 1 MiB deterministic inputs. Compression used each
library's level 6; decompression used identical zlib-encoded input. All round trips
matched. This measures whole-buffer API implementations, not isolated SIMD dispatch.

| Input / operation | zlib MiB/s | libdeflate MiB/s | Throughput ratio |
| --- | ---: | ---: | ---: |
| Log-like records / compress | 110.34 | 124.84 | 1.13x |
| Log-like records / decompress | 978.86 | 3541.38 | 3.62x |
| Pseudorandom / compress | 50.24 | 103.65 | 2.06x |
| Pseudorandom / decompress | 2706.51 | 15567.43 | 5.75x |

For the records input, compressed sizes were 108591 bytes with zlib and 102036
with libdeflate. For pseudorandom input they were 1048902 and 1048672 bytes.
Compression levels are not equivalent across libraries. Libdeflate object setup
is outside timing; zlib's whole-buffer API includes its per-call internal setup.
These warm-buffer measurements exclude disk I/O and process startup.

The final JPEG/compression run took about 30 seconds. Android reported no thermal
throttling at either endpoint; battery temperature rose from 33.5 to 33.9 C while
unplugged. Clocks, scheduling and intermediate temperatures were not controlled.
There is no sustained-throughput or battery-energy conclusion.

**Decision:** keep the working crypto/JPEG dispatch. Libdeflate is a measured
candidate for bounded, whole-buffer compression/decompression integrations; this
does not justify replacing zlib globally or changing streaming/archive defaults.
No installed package or global runtime setting was changed by this audit.
Reproduction instructions are in the
[validation guide](PIXEL11_VALIDATION.md#jpeg-dispatch-and-whole-buffer-compression-audit).

## Single-pass release source verification

The next implemented optimization removes repeated gzip decompression from
`scripts/aether/verify-package.py`. Previously, locating members scanned the
archive, then individual reads could seek backwards and decompress earlier data
again. The new verifier hashes all regular files during one sequential pass,
using 1 MiB chunks for large sources. It retains the source-commit, required-file,
manifest-hash, runtime-provenance and checkout-source checks; it also rejects
duplicate members and validates the gzip trailer. No libdeflate dependency or
archive-format change is required.

Measured on the Pixel with the existing v1000.0.0 APK and its **23,797,649-byte**
source archive for commit `821b4f3f6526`. Five paired samples alternated old/new
order after warmup, using separate child processes. The baseline is the verifier
at `266b0cba`; the [raw report](benchmarks/2026-09-21/pixel11-source-verification.json)
records the exact script and artifact hashes.

| Measurement | Before | After | Improvement |
| --- | ---: | ---: | ---: |
| Median complete verification time | 200.45 ms | 88.91 ms | 2.25x throughput |
| Median peak process RSS | 50.14 MiB | 35.44 MiB | 29.3% lower |

This saves about **112 ms per check** for this archive, not seconds of terminal
startup. The benchmark reconstructs the release's source/generated-file fixture
and substitutes only its historical Git HEAD result. Timings exclude fixture
preparation, interpreter startup, downloads and builds. Filesystem caches were
warm; CPU clocks and thermals were uncontrolled. Peak RSS includes Python and
APK verification, not just decompression. No CI-runner speedup is claimed until
measured there. All 19 focused packaging regression tests pass locally.

Reproduction instructions are in the
[Aether runtime guide](../scripts/aether/README.md#source-verification-performance).


## Printable-ASCII width classification: September 22, 2026

Device frame captures on the installed `1000.0.0` APK showed activity drawing
cost more than GPU completion in the sampled workloads. This identifies CPU-side
drawing as a profiling target, not `WcWidth` as a proven dominant bottleneck.
Inspection found the renderer classifies each code point and checks the following
character for combining behavior. Printable ASCII can return width one before
Unicode range checks and table lookups, without changing redraw scheduling.

A standalone Android ART comparison against `dc24a918` verified identical widths
for **all 1,114,112 Unicode code points**, plus four out-of-range integer boundaries.
Five alternating pairs after warmup measured:

| Width-classification input | Before, million calls/s | After, million calls/s | Throughput ratio |
| --- | ---: | ---: | ---: |
| Printable ASCII | 300.99 | 427.53 | 1.42x |
| 75% ASCII / 25% Unicode | 188.58 | 230.42 | 1.22x |
| Unicode-only control | 77.35 | 77.38 | 1.00x |

[Raw samples and source hashes](benchmarks/2026-09-22/pixel11-width.json);
[reproduction harness](../scripts/width-benchmark/README.md). This is a scalar
Java fast path, not SIMD. Clocks and thermals were uncontrolled. The candidate has
**not been installed in the terminal APK**, so no frame-rate, battery, or
input-latency improvement is established.

### Installed-app frame characterization

[Archived captures](benchmarks/2026-09-22/README.md) retain frame timestamps,
Android aggregate counters and workload metadata. Both used 10-second phases at
60 producer updates/s. Run 1 used an 83x49 terminal; run 2 used 83x74 with sampled
focus/rotation guards. These are separate baselines, not before/after results.

| Workload | Run 1 frame p95 | Run 2 frame p95 |
| --- | ---: | ---: |
| Colored text scrolling | 11.119 ms | 11.051 ms |
| Existing sixel image redraw | 10.408 ms | 9.677 ms |
| Sixel image replacement | 9.846 ms | 8.399 ms |

The input-injection attempt failed in Android's shell service; no input-latency
result is claimed. The collector now checks that facility before attempting the
optional input phase. Neither frame run contained the new width fast path.
