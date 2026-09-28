# Termux-Æther

Coordinated suite releases: [installation, upgrades and component dependencies](https://github.com/wallentx/termux-aether-app/blob/dev/docs/RELEASES.md). The `v1000.0.0` baseline keeps existing app IDs and data paths.

[![Build](https://github.com/wallentx/termux-aether-app/actions/workflows/debug_build.yml/badge.svg?branch=dev)](https://github.com/wallentx/termux-aether-app/actions/workflows/debug_build.yml?query=branch%3Adev)
[![Tests](https://github.com/wallentx/termux-aether-app/actions/workflows/run_tests.yml/badge.svg?branch=dev)](https://github.com/wallentx/termux-aether-app/actions/workflows/run_tests.yml?query=branch%3Adev)

A Termux fork for modern Android: a Pacman-based terminal, a bundled Linux/glibc
compatibility runtime, and an optional hardware-virtualized Arch workspace.
Development and device testing focus on the **Pixel 11 Pro XL running Android 17**.

Built on [Termux](https://github.com/termux/termux-app) and
[Termux Monet](https://github.com/HardcodedCat/termux-monet), with downstream
compatibility fixes and measured terminal optimizations. This is an independent
fork; report fork-specific issues [here](https://github.com/wallentx/termux-aether-app/issues).

## Performance highlights

Measured on a **Pixel 11 Pro XL running Android 17**, September 19, 2026.
These are improvements to individual operations against this fork's previous
implementations, not a whole-app comparison with stock Termux.

| Optimized operation | Before | After | Improvement |
| --- | ---: | ---: | ---: |
| Sixel decode, long repeats of 8192 pixels | 13.620 ms/image | 0.152 ms/image | **89.9x faster** |
| Copy a 160-column simple-text row | 2.409 us | 0.058 us | **41.8x faster** |
| Clear a 160x48 simple-text buffer | 108.0 us | 2.71 us | **39.9x faster** |
| Bulk ASCII parsing and buffer updates | 24 MiB/s | 417 MiB/s | **17.4x throughput** |
| Bitmap growth, 1920x1080 to 1920x1180 | 4.45 ms | 2.72 ms | **39% less time** |

The largest sixel gain is a synthetic long-repeat case; repeats of 16 pixels
improved **7.0x**, and overpainting improved **22.4x**. Colored-text throughput
improved **6.7x**. Single-column sixel, single-byte input, and Unicode fallback
controls were broadly unchanged. These results do not establish matching gains
in frame rate, battery life, or arbitrary shell programs. The Java/Bitmap changes
are batching and copying optimizations; no hand-written SIMD is claimed.

A further scalar width-classification fast path measured **1.42x throughput for
ASCII** and **1.22x for mixed text** under Android ART. All Unicode code-point
widths matched the baseline. This isolated routine result still needs an APK
frame comparison; see the [width benchmark](docs/PERFORMANCE.md#printable-ascii-width-classification-september-22-2026).

Release tooling also improved: single-pass source verification took **88.91 ms
instead of 200.45 ms**, with **29.3% lower peak process memory**, in a September 21
archive benchmark. This is a packaging-tool improvement, not terminal startup time.

[Methods, baseline commits, limitations, and raw results](docs/PERFORMANCE.md).

## What sets it apart

- **Modern Android support.** Target SDK 37, compiled against SDK 37.2, with
  app-private command execution adapted to modern Android restrictions. Includes
  Android 17 text-selection fixes, terminal redraw and session-drawer fixes,
  keyboard adjustments, and an in-app **Keep screen on** option.
- **Pacman by default.** Fresh installations use the Termux-Pacman bootstrap and
  Android-compatible package feed. Existing APT installations are not silently
  converted. Pacman here manages Termux packages; it does not turn Android into Arch.
- **Native Linux binary compatibility.** The bundled **Aether** runtime runs
  supported dynamically linked Linux ARM64/glibc programs directly from Termux,
  without PRoot or Arch. The compatibility runtime itself does not need Shizuku;
  normal terminal sessions use the Shizuku execution service. It provides Android-backed DNS, common
  certificate-path mappings, device identity, and mixed Linux/Android child-process
  handling. A complete Geekbench CPU run has passed in the native app context.
- **An optional real Arch VM.** The matching API companion runs Arch Linux ARM
  through Android's virtualization framework. Open it with `Æ`, or run commands
  with `æ`. Persistent storage, networking, selected-directory sharing, on-demand
  startup, optional suspension, disk growth, and live memory adjustment are available.
- **Faster terminal hot paths.** Bulk ASCII parsing, buffer fills/copies, sixel
  decoding, and bitmap copying reduce work in common text and image operations.
  Sixel support is inherited and extended; it is not exclusive to this fork.

## What comes with it

| Component | Included or separate? |
| --- | --- |
| Terminal app, Monet theming, Pacman bootstrap | Included in the default ARM64 APK |
| Shizuku-backed terminal/background commands and shared-storage bridge | Included; requires the separately installed, running Shizuku service |
| Aether glibc 2.44 runtime, `aether-run`, and execution probe | Included and installed when Termux opens |
| Device capabilities, thermal diagnostics, Shizuku access, and Arch VM control | Separate [Termux-Æther:API companion](https://github.com/wallentx/termux-aether-api) and [CLI package](https://github.com/wallentx/termux-aether-api-package/tree/dev) |
| Arch kernel/root filesystem and networking helper | Separate guest artifact and setup; not embedded in the terminal APK |
| Validation scripts and benchmark harnesses | In this repository and CI artifacts; optional tools and Geekbench are not bundled |

Aether leaves package-managed glibc files alone. Some Linux programs need additional
libraries or encounter Android filesystem/syscall restrictions; this is not universal
Linux compatibility. See the [runtime guide](scripts/aether/README.md).

## Verified SIMD and native-library acceleration

On the same Pixel, a September 21 audit confirmed that installed native libraries
already use hardware acceleration. These comparisons enable versus disable
acceleration in the **same library**; they are not fork-versus-stock Termux gains
or new optimizations added by this fork.

| Workload | Acceleration disabled | Normal dispatch | Throughput gain |
| --- | ---: | ---: | ---: |
| libjpeg-turbo 3.2.0 JPEG encode | 339.01 MiB/s | 512.59 MiB/s | 1.51x |
| libjpeg-turbo 3.2.0 JPEG decode | 281.41 MiB/s | 399.58 MiB/s | 1.42x |
| OpenSSL 3.6.3 SHA-256 | 259.57 MiB/s | 2382.33 MiB/s | 9.18x |

The JPEG results compare normal SIMD dispatch with SIMD disabled; SHA-256 uses
ARM cryptographic acceleration, rather than general-purpose SIMD. JPEG output
and SHA-256 digests matched between modes.

A separate comparison found **3.62x to 5.75x faster whole-buffer decompression**
with libdeflate than zlib on two synthetic inputs. This compares library
implementations, not SIMD on/off, and remains a candidate for targeted integration;
it does not change the default compression library. These short, warm-buffer
benchmarks do not establish whole-app speedups or battery savings. A matched
AVF-versus-PRoot speedup has not been established.

[Native-package audit, raw results, and reproduction instructions](docs/PERFORMANCE.md#installed-native-package-audit-september-21-2026).

## Install

1. For normal installation and upgrades, use the [Pacman suite release](https://github.com/wallentx/termux-aether-app/releases/latest) and [upgrade guide](docs/RELEASES.md). For development snapshots, download the ARM64 APK from a successful
   [Build run on `dev`](https://github.com/wallentx/termux-aether-app/actions/workflows/debug_build.yml?query=branch%3Adev+event%3Apush).
   GitHub requires sign-in to download Actions artifacts. Normal builds produce
   one `pacman-android-7` ARM64 APK, checksums, and matching Aether sources.
2. Install the APK. The Android package remains `com.termux`, so an update with a
   compatible signature preserves the existing environment. An incompatible
   installation needs a backup and a planned migration; do not uninstall it just
   to try this fork. APK updates do not convert an APT prefix to Pacman.
3. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/), then authorize
   Aether's connection prompt. Normal sessions on modern Android run through
   `run-as` with the Termux UID, allowing ordinary native execution without
   rebuilding every Go program. If Shizuku is unavailable, setup is shown instead
   of silently changing execution mode. A recovery shell remains explicitly
   available. Background commands also require the service and report an error
   when it is unavailable. On an unrooted device, restart Shizuku after reboot.
   If Shizuku is already running but Termux is not connected, **Connect** requests
   a fresh connection from Shizuku and returns to Termux. **Open Shizuku** opens
   its management screen when you need to start the service.
4. For device integration or Arch, follow the
   [API companion setup](https://github.com/wallentx/termux-aether-api#setup).
   The app and companion must use matching signing certificates. Arch additionally
   needs a supported Android virtualization build, an authorized Shizuku service,
   the CLI wrappers, and the separately staged guest image.

These are development builds, currently using Termux's public debug test key,
not a private release-signing identity. Obtain both APKs from these repositories.
The project name does not change Android package IDs, data paths, or CLI names.

`run-as` requires a debuggable APK, so the release build type also keeps that
flag. Sessions preserve Android's runtime environment and use a dedicated PTY
service for exit status and cleanup. Background commands use separate stdin,
stdout and stderr pipes through the same service. Shared storage is exposed through
`~/storage` shortcuts and `$EXTERNAL_STORAGE`; custom shortcuts are preserved.
Literal `/sdcard` and `/storage/emulated/0` paths are not transparently remapped.
See [session validation and limitations](scripts/session-validate/README.md).

The default build is tailored to ARM64 Pixel testing; other ABIs and APT bootstraps
remain available through [explicit build profiles](docs/BUILD_PROFILES.md).
They are not covered by the Pixel validation results. APKs are built in CI; native companion packages can be built on aarch64 Termux.

## Try it

Run the included Aether compatibility check from the installed Termux app:

```sh
aether-run "$HOME/../aether/aether-probe"
```

Place `DRAWER` and `VOICE` anywhere in the `extra-keys` layout in
`~/.termux/termux.properties`. `DRAWER` opens the session panel without an edge
swipe. `VOICE` opens Android's speech prompt, inserts the recognized text into
the same running session without pressing Enter, and leaves the extra keys
visible. Canceling recognition or changing sessions inserts nothing. The speech
UI comes from the installed Android recognizer; the app does not control
Gboard's microphone button. Ctrl+tap on a terminal URL opens it in the default
browser, including when ordinary tap-to-open is disabled.

When selecting text from an app that draws each screen row separately, use
**Copy as one line** in the selection menu (or its overflow) to remove row breaks.
Regular **Copy** preserves real newlines. The one-line choice keeps true terminal
auto-wraps joined, removes repeated indentation from continuation rows, and
replaces other row breaks with a space.

For broader command, storage, and visual sixel checks, see
[device validation](docs/PIXEL11_VALIDATION.md). For implemented features and
remaining experiments, see the [feature plan](docs/PIXEL11_FEATURE_PLAN.md).

## Upstream and licensing

Termux-Æther retains the Termux/Monet foundation and credits its upstream
contributors. The original [upstream README](README.upstream.md) is preserved as
reference; its download links describe upstream builds, not this fork.
See [LICENSE.md](LICENSE.md) for GPLv3 and component exceptions. Aether includes
its runtime license notices; enabled CI builds publish matching source archives.
