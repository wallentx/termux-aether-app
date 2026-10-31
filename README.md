# Termux-Æther

[![Build](https://github.com/wallentx/termux-aether-app/actions/workflows/debug_build.yml/badge.svg?branch=dev)](https://github.com/wallentx/termux-aether-app/actions/workflows/debug_build.yml?query=branch%3Adev)
[![Tests](https://github.com/wallentx/termux-aether-app/actions/workflows/run_tests.yml/badge.svg?branch=dev)](https://github.com/wallentx/termux-aether-app/actions/workflows/run_tests.yml?query=branch%3Adev)

This is my personal Termux fork for the **Pixel 11 Pro XL running Android 17**.
I use it to test patches and experimental changes independently of upstream,
while keeping an upstream baseline available. `master` tracks upstream;
`dev` contains the fork's changes.

The focus is newer Android SDKs and dependencies, device-specific performance
work including SIMD experiments, an AVF alternative to my PRoot-based Arch setup,
and integrated Shizuku access. The compatibility work aims to reduce the per-tool
patches I've needed in [codex-termux](https://github.com/wallentx/codex-termux),
[antigravity-cli-termux](https://github.com/wallentx/antigravity-cli-termux),
[claude-code-termux](https://github.com/wallentx/claude-code-termux), and
[opencode-termux](https://github.com/wallentx/opencode-termux).

[Installation and upgrades](docs/RELEASES.md) · [Downloads](https://github.com/wallentx/termux-aether-app/releases/latest) · [Issues](https://github.com/wallentx/termux-aether-app/issues)

## Features

- **Native execution by default.** Android restricts execution from writable app
  directories for apps targeting Android 10 or later. Termux-Æther uses Shizuku
  and `run-as` for all normal terminal sessions and app-managed background jobs,
  permitting direct execution while retaining Termux's user ID and Android's
  Bionic C library. Commands and ordinary child processes use this context
  automatically, without a per-command wrapper. This avoids linker-based
  execution workarounds for build tools, task runners, and worker processes.
  Recovery shells retain the legacy launcher. The integration also includes
  `rish` access, a shared-storage bridge, and optional companion device and thermal
  diagnostics. [Execution](scripts/session-validate/README.md) · [Shizuku shell](docs/RISH.md) · [Device tools](docs/PIXEL11_VALIDATION.md)
- **Linux/glibc compatibility.** The separate `aether-run` command launches
  supported Linux ARM64/glibc binaries through a bundled loader and compatibility
  libraries, with Android-backed hostname lookup (`getaddrinfo`) and common
  certificate-path mappings. It runs on Android's kernel without PRoot or a VM
  and does not replace Bionic for normal commands. [Runtime guide](scripts/aether/README.md)
- **Arch through AVF.** The optional [API companion](https://github.com/wallentx/termux-aether-api)
  provides an Arch Linux ARM virtual machine using Android's virtualization
  framework. Open it with `Æ` or run commands with `æ`. It supports networking,
  directory sharing, persistent storage, and adjustable resources on supported
  Android builds.
- **Android SDK target and Pacman packages.** The default ARM64 build
  targets SDK 37. Fresh installations use Android-compatible Termux packages
  managed by Pacman; existing APT environments are not converted.
- **Terminal changes.** Text parsing, buffer operations, and sixel image handling
  include measured optimizations. The interface includes Monet theming,
  configurable voice-input and session-menu keys, Ctrl+tap links,
  **Copy as one line**, and **Keep screen on**.

## Performance measurements

Results from targeted terminal benchmarks on the Pixel 11 Pro XL:

| Operation | Measured improvement |
| --- | ---: |
| Sixel decoding, long repeated runs | **89.9x faster** |
| Simple-text row copying | **41.8x faster** |
| Simple-text buffer clearing | **39.9x faster** |
| Bulk ASCII parsing and buffer updates | **17.4x throughput** |
| Bitmap growth | **39% less time** |

These compare individual operations with this fork's earlier implementations,
not whole-app performance against stock Termux. The largest sixel result is a
synthetic long-repeat case; shorter repeats improved **7.0x**.
[Benchmarks, native-library acceleration, and reproduction instructions](docs/PERFORMANCE.md)

## Components

| Component | Availability |
| --- | --- |
| Terminal, Monet theming, Pacman bootstrap, Aether glibc runtime | Included in the ARM64 APK |
| Shizuku execution and storage integration | Included; requires the separately installed Shizuku service |
| Device diagnostics and Arch VM control | Optional [API app](https://github.com/wallentx/termux-aether-api) and [CLI package](https://github.com/wallentx/termux-aether-api-package/tree/dev) |
| Arch kernel, root filesystem, and networking helper | Separate guest download and setup |
| Validation tools and benchmarks | Available in this repository and CI artifacts |

Linux binary compatibility is not universal. Arch requires a supported Android
virtualization build. Device diagnostics depend on the phone and Android build.

## Install

1. Use the [suite release and upgrade guide](docs/RELEASES.md), or download the
   ARM64 APK from a successful [development build](https://github.com/wallentx/termux-aether-app/actions/workflows/debug_build.yml?query=branch%3Adev+event%3Apush).
   GitHub sign-in is required for CI artifact downloads.
2. Back up an existing Termux installation before changing builds. Aether keeps
   `com.termux` and its data paths; compatible signing allows an in-place update.
   If signatures differ, follow the migration guide rather than uninstalling to
   try the fork. Updating the APK does not convert APT to Pacman.
3. Install and start [Shizuku](https://shizuku.rikka.app/guide/setup/), then authorize
   Aether's connection prompt. Normal terminal and background sessions require it.
   On unrooted devices, restart Shizuku after reboot.
4. For diagnostics or Arch, follow the [API companion setup](https://github.com/wallentx/termux-aether-api#setup).
   The app and companion must use matching signing certificates.

Builds currently use Termux's public debug signing key and require a debuggable
APK for command execution. Obtain the APKs from these repositories.
The default build targets ARM64; [other build profiles](docs/BUILD_PROFILES.md)
remain available. [Session and storage limitations](scripts/session-validate/README.md)

## Usage

Check the bundled Linux compatibility runtime:

```sh
aether-run "$HOME/../aether/aether-probe"
```

Add `VOICE`, `GBOARD`, or `DRAWER` to your `extra-keys` layout in
`~/.termux/termux.properties` for speech input or one-tap session access.
[Gboard voice input](docs/GBOARD_VOICE.md) supports its selected dictation mode, including Rambler.
Hold Ctrl to underline and open links. Use **Copy as one line** to copy wrapped
prose without unwanted line breaks.

[Device checks](docs/PIXEL11_VALIDATION.md) · [Feature plan](docs/PIXEL11_FEATURE_PLAN.md)

## Upstream and licensing

Built on [Termux](https://github.com/termux/termux-app) and
[Termux Monet](https://github.com/HardcodedCat/termux-monet). Sixel support builds
on their work. This is an independent fork; please report fork-specific issues
[here](https://github.com/wallentx/termux-aether-app/issues).

See the [upstream README](README.upstream.md), [GPLv3 license and component exceptions](LICENSE.md),
and bundled runtime license notices. CI artifacts include matching Aether sources.
