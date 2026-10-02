# Validate the installed Termux fork

The user has installed the API 37 APK on the Pixel and reports that it works.
This establishes basic startup, not completion of every workload check below.

Run this script **inside that installed Termux app**, not through `adb shell`,
PRoot, SSH into another computer, or a VM. It needs Python 3 and uses only its
standard library. If Python is missing, install it using your package manager
before running the script; the script never installs anything itself.

From a checkout of this fork:

```sh
python3 scripts/pixel-validate/validate.py
python3 scripts/pixel-validate/validate.py --bench
python3 scripts/pixel-validate/validate.py --bench --sixel
```

Alternatively, download the single script and run it:

```sh
curl -fLo termux-validate.py https://raw.githubusercontent.com/wallentx/termux-aether-app/dev/scripts/pixel-validate/validate.py
python3 termux-validate.py --bench --sixel
```

Normal checks typically take 5-30 seconds with healthy tools. Each child command
has a 10-second timeout; an installed but broken tool can lengthen the run.
Benchmarks normally add 10-30 seconds. They use five timed batches after a
warm-up, targeting at least 0.5 seconds per batch, except for capped storage writes.
Child startup failures/timeouts can take longer. `--sixel` waits for your visual
confirmation; allow another 15 seconds to inspect the pattern.

## Reading the result

The script prints PASS/FAIL/SKIP and writes a new private
`~/termux-validation-<timestamp>.json`. Exit code 1 means at least one automated
check, benchmark, or operator-confirmed visual check failed. Existing report files are never overwritten. Set
`--output <new-path>` to choose the report location.

| Result | Meaning |
| --- | --- |
| PASS | The named operation completed successfully |
| FAIL | An installed command failed, timed out, or returned incorrect output |
| SKIP | An optional command is missing, or the probe does not apply |
| UNKNOWN | A capability query was unavailable or denied |
| NOT_RUN | A UI or external integration check still needs manual testing |

Checks cover private scripts with shebang arguments, shell pipelines, Python and
available Node/Bun child execution, tool startup, Pacman's local package database,
and private storage write/rename/symlink behavior. SHA-256 correctness is checked
against three fixed known answers, including a multi-block message, even without
`--bench`. Version checks for Git/SSH/tmux
and agent tools establish startup only. The script doesn't install/update packages,
change settings, alter the clipboard, compile code, or contact remote hosts itself.
It executes installed tools' version commands; custom wrappers may have their own
side effects. Temporary files are scoped to a new directory under `$TMPDIR` (or
the private home directory if unset) and cleaned up on normal completion.

Metadata includes the app version/target SDK exported by Termux, OS/model, Python,
OpenSSL, process UID/SELinux context, page size, and readable battery sysfs values.
Schema 3 also queries installed `termux-capabilities --json` and
`termux-shizuku --thermal` commands. Missing tools, denied authorization and a
disconnected Shizuku service remain explicit skips; a timeout, malformed response
or failed command is a failure. The runner never requests authorization or starts
Shizuku. An exit-zero API response is not enough to count a temperature reading as
passing: its operation/status and sensor arrays must also be valid.

Benchmarks collect snapshots before and after the run and, when a bridge is
available, in one background sampler. The sampler waits five seconds between
request pairs; request latency adds to that interval. Each API command has a
25-second deadline. Shutdown waits for an in-flight pair to finish (up to 50
seconds). `thermal_samples` retains timestamps and availability/error states;
`thermal_summary` reports observed temperature ranges and maximum Android
throttling status. Unknown readings remain missing, not zero. Sampling can miss
brief peaks and adds background work, so compare runs with the same sampler setup.
It does not dump all environment variables, credentials, serial numbers, or IMEI.
Review command output and paths in the report before sharing it.

## Benchmarks and SIMD

`--bench` measures SHA-256 throughput, a zlib compression/decompression round trip,
file write/fsync/readback, and ten shell launches. Input is fixed, synthetic, and
8 MiB in size. Each hashing operation processes it eight times and compares against
a pinned digest independently cross-checked with `sha256sum`. Operations repeat
until the batch duration target is reached. `seconds` and `median_seconds` report
latency **per operation**, not total batch time; `batches` records actual elapsed
time, iteration counts, and whether each duration target was reached. Schema 2
reports keep these timings distinct from the earlier one-operation samples.

Use `--bench --samples 7 --sample-seconds 1` for seven one-second batches. Accepted
ranges are 3-15 samples and 0.1-5 seconds. These are duration targets, not hard
timeouts; a running operation completes before the next duration check.
Storage is capped at eight operations per batch (328 MiB written including warm-up
at defaults, at most 968 MiB with 15 samples). A capped batch can be shorter than
the target; the report records that fact and the total workload bytes.

Storage numbers include
the page cache and Python overhead; they are not raw flash bandwidth. Compression
uses a highly compressible repeating input, not a representative file corpus.
Shell-launch timings include the runner's temporary output files, correctness
checks, and Python timeout polling, so they are not pure process-spawn latency.

ARM64 capability reporting uses `getauxval` and read-only SVE/SME `prctl` queries in
the Python process. Feature bits and vector lengths **do not prove that a benchmark
uses SVE2 or SME2**, or that it is faster than another build. No optional instructions
are issued by the capability probe. Inaccessible battery readings remain null;
SVE/SME query failures retain their errno instead of implying lack of hardware.

Compare reports using the same script hash, package versions, phone temperature,
power/charging state, and workload. Keep the app visible and avoid other heavy
work during comparison. These Python measurements still do not identify the
instruction paths used by installed libraries.

### Explicit scalar / NEON / SVE2 benchmark

The **Pixel SIMD benchmark** Actions workflow produces `pixel-simd-arm64`, a
standalone native executable plus this runner, checksums, compiler version,
source commit and disassembly. Build it in CI; no compiler is needed on the phone.
Extract the artifact into a private Termux directory, then run:

```sh
sha256sum -c SHA256SUMS
chmod 700 pixel-simd-bench
python3 validate.py --simd-binary ./pixel-simd-bench
```

Defaults run five 0.5-second batches per supported kernel (7.5 seconds of timed
work with all three available, plus correctness checks, tool startup and API
requests). For a sustained comparison, use `--samples 7 --sample-seconds 2`
(42 seconds of timed work). `--bench` optionally adds the existing Python/storage
benchmarks. Do not run through `adb shell`, QEMU or PRoot for device performance.

All three kernels compute the same exact sum of absolute differences between two
fixed 8 MiB byte arrays. `bytes_per_iteration` counts **both arrays**, 16 MiB total.
The expected sum is pinned at 705953792 and independently checked in Python.
Every process tests fixed answers, unaligned inputs, lengths 0-513 and tails
adjacent to unreadable guard pages before timing. Every timed iteration also
checks its result. The scalar translation unit disables loop/SLP vectorization;
NEON and SVE2 live in separate translation units, with LTO disabled. Runtime
HWCAP/thread-state checks gate optional kernels; unsupported paths emit SKIP.
The SVE2 kernel uses the actual thread vector length, without changing it.

Kernel order rotates each round. Reported native timing excludes process startup,
fixture allocation and self-tests. It includes kernel calls, result checks and
timer checks. Each process warms up once. Thermal sampling runs concurrently;
there are setup gaps between batches. These are bounded workload bouts, not a
continuous thermal-soak test. No core affinity, governor or thermal thresholds
are changed. Memory/cache bandwidth can limit results. Speedups apply only to
this byte-difference workload, not OpenSSL, Python, AI packages or the whole app.
SME/SME2 availability is still not an SME performance measurement.

CI checks Linux ARM64 kernels under QEMU at 16/32/64-byte vector lengths and with
SVE disabled, and inspects the shipped Android binary for scalar, NEON and SVE2
instruction paths. QEMU results are correctness evidence only; Android/Bionic
execution and performance must be checked on the Pixel.

Instruction reference: [Arm C Language Extensions](https://arm-software.github.io/acle/main/acle.html).

## Sixel rendering check

Run `python3 termux-validate.py --sixel` directly in the visible Termux session,
without piping or redirecting input/output. It generates its own 240x144 image;
no image download, Pillow, or `img2sixel` package is needed.

1. Check for three solid bars: red, green, blue from left to right.
2. Check for a black/white checkerboard beneath the bars, with no missing bands.
3. Check that `SIXEL END` appears below the image with no overlap or raw escape text.
4. Enter `pass` or `fail`; Enter alone leaves the result `NOT_RUN`.

The pattern exercises RGB palette definitions, repeat runs, carriage return,
six-pixel bands (including partial masks), raster dimensions, and DCS termination.
The result is stored in `visual_checks`, separately from the automated summary.
Writing sixel bytes successfully never counts as a rendering pass. Noninteractive
input/output yields `SKIP`; omission of `--sixel` leaves it `NOT_RUN`.
This is a visual smoke test, not a renderer throughput, large-image, or scrollback
regression test. It adds ordinary output to the current terminal without clearing
the screen or changing terminal modes.

## Selection-menu regression check

1. Run `printf 'Select this word and copy it.\n'` and long-press a word. Copy/Paste/More
   should appear on the initial selection without touching a handle again.
2. Drag each handle. The menu should hide while moving and return after release.
3. Exercise Copy, Paste, and More; cancel selection and repeat on a single character.
4. Repeat near the bottom row, with the keyboard open/closed, and after rotation.
5. Switch sessions or leave the app while selection is active; no stale menu should reappear.

The regression tests cover scheduling, selection state, handle-release refresh,
single-cell bounds, finished modes and detached views using an Android test runtime.
The Android 17 path also alternates the toolbar anchor by one pixel on refresh:
a live Pixel check found the remote implementation could claim it was shown
and suppress identical layout requests while its window was still absent.
The selected text and handle positions are unchanged by this workaround.
They do not reproduce Android 17's actual floating-toolbar implementation. Final
confirmation of the reported Android 17 symptom requires this device check.

Remaining manual checks are included in every JSON report: session persistence,
notification actions, shared storage, LAN SSH, package installation/update,
and normal agent workloads. AVF and other unimplemented
features are not counted as passing.

## Native package dispatch regression

Run `python scripts/pixel-validate/package_benchmark.py` in native Termux, or
`termux-package-benchmark` when installed on PATH. It checks known-answer SHA-256,
alternates normal OpenSSL dispatch with a capability-mask override confined to
child processes, verifies identical digests, and records raw samples, medians,
library identity, battery and thermal state. It also verifies a zlib round trip.
Reports are written under `~/benchmarks/` and survive reboot. The default uses five
pairs of 64 MiB inputs with eight hashes per child. No packages are rebuilt.

This checks one installed package operation. ARM SHA instructions are distinct
from general NEON/SVE SIMD; neither a CPU feature flag nor this result proves that
other packages dispatch to their optimized kernels. Use the rendering collector
for end-to-end terminal latency, with the terminal visible and consistent input.

### JPEG dispatch and whole-buffer compression audit

Run from the app repository in native Termux:

```sh
python scripts/pixel-validate/native_package_audit.py
```

This takes about 30 seconds on the development Pixel with working capability
queries. It uses the installed `libjpeg-turbo`, `zlib` and `libdeflate` libraries
through Python's `ctypes`; it installs nothing, compiles nothing and requires no
screen interaction. JSON results are saved under `~/benchmarks/`. Missing libraries
or failed workloads produce a nonzero exit and a report containing the error.

Five paired samples alternate execution order, with at least 0.3 seconds per
operation after warmup. Each sample runs in a fresh child process. JPEG compares
normal dispatch against the upstream-supported child-only `JSIMD_FORCENONE=1`
override, using a deterministic 1023x769 RGB fixture, quality 90, 4:2:0 subsampling
and accurate integer DCT. It requires identical encoded bytes and decoded pixels
across modes; it does not compare lossy decoded pixels to the original RGB input.
See the [upstream SIMD documentation](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/main/simd/README.md)
for the override's meaning.

Compression compares the C APIs of zlib and libdeflate on deterministic 1 MiB
log-like and pseudorandom inputs. Both use reusable output buffers, and both
decoders receive the same zlib stream. Encoding uses level 6 in each library,
which does not imply equivalent effort or compression ratio; compressed sizes
are recorded separately. Every sample must round-trip exactly. This comparison
measures library implementations, not an isolated SIMD speedup. Libdeflate's
reusable compressor/decompressor allocation is outside the timed region; zlib's
whole-buffer API performs its internal setup per call. It does not model streaming
compression, disk I/O or process startup.

Reports include library paths and SHA-256 identities, package versions, benchmark
parameters, script identity, app UID, and capability/thermal/battery snapshots
before and after the run. They do not continuously sample temperature, control CPU
clocks or establish battery savings. Existing shell dispatch settings are preserved;
only benchmark children receive normalized dispatch settings.
