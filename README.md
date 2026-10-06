# Termux-Æther

[![Build](https://github.com/wallentx/termux-aether-app/actions/workflows/debug_build.yml/badge.svg?branch=dev)](https://github.com/wallentx/termux-aether-app/actions/workflows/debug_build.yml?query=branch%3Adev)
[![Tests](https://github.com/wallentx/termux-aether-app/actions/workflows/run_tests.yml/badge.svg?branch=dev)](https://github.com/wallentx/termux-aether-app/actions/workflows/run_tests.yml?query=branch%3Adev)

**A Linux development environment built for modern Android.** Termux-Æther combines
Pacman packages, native Linux binary compatibility, and an optional
hardware-virtualized Arch workspace with a faster, more convenient terminal.
Development and device testing focus on the **Pixel 11 Pro XL running Android 17**.

[Install or upgrade](docs/RELEASES.md) · [Downloads](https://github.com/wallentx/termux-aether-app/releases/latest) · [Report an issue](https://github.com/wallentx/termux-aether-app/issues)

## What sets it apart

- **More Linux tools, directly on Android.** The bundled Aether runtime runs
  supported Linux ARM64/glibc programs without PRoot or a VM, with Android-aware
  DNS and certificate handling. [Runtime guide](scripts/aether/README.md)
- **A real Arch workspace when you need one.** Open Arch Linux ARM with `Æ`, or
  run a command with `æ`. Hardware virtualization, networking, directory sharing,
  persistent storage, and adjustable resources are available through the optional
  [API companion](https://github.com/wallentx/termux-aether-api).
- **Pacman by default.** Fresh installations use Android-compatible Termux
  packages managed by Pacman. Existing APT environments stay APT.
- **A terminal that gets out of your way.** Faster text and sixel image handling,
  Monet theming, configurable voice-input and session-menu keys, Ctrl+tap links,
  **Copy as one line**, and an in-app **Keep screen on** option.
- **Android integration without root.** Bundled Shizuku integration supports
  native command execution and shared-storage access. Inspect device capabilities,
  temperatures, and thermal limits with the companion diagnostic tools.
  [Device tools](docs/PIXEL11_VALIDATION.md) · [Shizuku shell](docs/RISH.md)

## Performance highlights

Targeted terminal benchmarks on the Pixel 11 Pro XL show substantial gains:

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

## What comes with it

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

## Try it

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
