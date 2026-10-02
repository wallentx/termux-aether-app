# Pixel 11 feature integration plan

Latest status: see the September 19 checkpoint below; older unchecked entries are
original acceptance criteria, not a current completion checklist.

Initial status: API 37 default APK built successfully in CI and installed by the user;
basic use works. Broader workload checks remain open. Run the
[device validation script](PIXEL11_VALIDATION.md) to collect results.
Device baseline verified on 2026-09-16; install confirmed on 2026-09-17.
See also [probe instructions](PIXEL11_PROBE.md).

## Device and scope

| Item | Baseline |
| --- | --- |
| Device | Pixel 11 Pro XL, ARM64 |
| SoC | Tensor G6, reported by the connected device |
| Installed OS | Android 17 QPR2 Beta 4, CP41.260814.003.C2 |
| Device API reports | SDK 37; full SDK 37.2; preview SDK 0; codename REL |
| Virtualization | AVF feature present; non-protected and protected VM support advertised; guest boot untested |
| Page size | Currently 4096 bytes; retain 16 KB-compatible native packaging without changing device mode |

Scope is this device and its current Android generation. ADB/Shizuku is allowed;
root, custom kernels, and support for older Android releases are not requirements.
Build APKs and native artifacts in CI or another build host. Run installed-artifact
checks on the Pixel. Do not run builds in this Termux workspace.

The default build profile now selects one ARM64 APK with a pinned Pacman bootstrap.
APT and other architectures remain opt-in; see [build profiles](BUILD_PROFILES.md).
The Pacman feed initially comes from Termux-Pacman. Fresh-install package setup
still needs device validation; existing APT prefixes are not converted by updating
the APK. Bootstrap package compatibility (Android 7+) is distinct from targetSdk.

The normal build now targets API 37 and compiles against SDK 37.2, as requested.
The minimum SDK is 24 to satisfy the bundled Shizuku API; the optional diagnostic
requires 37. Normal modern-SDK sessions now require Shizuku plus `run-as` for
direct execution. Legacy recovery and non-terminal background tasks retain linker
handling. Service declarations, notification intents, receiver
flags and notification/LAN permission requests have been updated. This establishes
the migration code, not proof that all terminal workloads pass on the Pixel.

The OS/build mapping is corroborated by the [QPR2 release notes](https://developer.android.com/about/versions/17/qpr2/release-notes).
SDK 37.2 was read from the Pixel and its build-host package availability verified
in Google's SDK catalog. CI compilation and device acceptance remain separate gates.

## Component ownership

| Component | Responsibility |
| --- | --- |
| termux-app | Terminal UI, native PTYs, session/workspace management, private home and prefix |
| Termux:API Android app | Android bridge, permission UI, optional Shizuku adapter, modular AVF management service |
| termux-api-package | CLI clients, JSON/stream transport, consistent exit codes, cancellation |
| Package/bootstrap integration | Modern native execution, initial shell launch, runtime-specific compatibility fixes |
| Linux guest agent | Guest terminal sessions, host bridge client, selected file sharing and port forwarding |

```text
Termux app
  +-- Native PTY --> Termux packages and private home
  +-- VM session --> guest agent --> Linux tools and containers
  +-- Authenticated IPC --> Termux:API app
                            +-- Android public APIs
                            +-- Optional Shizuku operations
                            +-- AVF manager --> crosvm / hypervisor --> VM

Native CLI --> app-side client/broker --> Termux:API app
Guest CLI  --> authenticated vsock channel --> Termux:API app
```

Keep public Android operations and privileged operations separate internally.
Shizuku provides shell-level capabilities when started through ADB; it does not
give ordinary app processes root access or unrestricted access to app-private data.

The Android bridge now has the fork `wallentx/termux-aether-api`, based on current upstream
`termux/termux-api`. The matching CLI fork is `wallentx/termux-aether-api-package`.
`wallentx/Termux-api-bluetooth` is an older CLI-package fork, not the proposed
Android bridge base. Both new forks were created when implementation began.

## 1. Foundation: SDK, native execution, storage, installation

API 37 is now the build default, and the user has confirmed the installed app works.
The remaining work below covers specific workloads, storage access, signing and
updates; it does not mean the app is unusable until every item is complete.

- [ ] **F1 - Modern native execution spike.** Evaluate current upstream
  `termux-exec-package` system-linker execution, including bootstrap and the first
  shell launched by JNI. Audit interactive sessions, SSH, RUN_COMMAND, background
  commands, scripts and child-process launch. Preserve native Termux as a first-class
  backend if this passes; do not silently move all workloads into a VM.
- [ ] **F2 - Explicit storage model.** Keep executable packages and Unix projects in
  private internal storage. Offer all-files access for direct shared-storage paths;
  use SAF/content URIs for document providers and selected external files. Add
  import/export and file-descriptor transfer instead of pretending every URI is a
  POSIX path. Storage access and executable-file restrictions are separate issues.
- [ ] **F3 - App identity and bridge transport.** Preserve the `com.termux` prefix
  unless rebuilding packages for a new prefix is intentional. Use a persistent
  private signing identity for fork releases. Prefer separate app UIDs with
  signature-protected Binder/service IPC; audit existing API/plugin transport before
  removing sharedUserId. Plan backup and clean-install migration where necessary.
- [ ] **F4 - Android 17 lifecycle and permissions.** Audit foreground-service
  types/start rules, notifications, background activity launches, boot behavior,
  package visibility, LAN permission and clipboard limitations. Keep UI/window
  closure distinct from session shutdown; expose permission failures explicitly.
- [ ] **F5 - Reproducible release baseline.** ARM64-only artifacts, compatible JNI
  libraries, coordinated bootstrap/package versions, CI tests and signed APKs.
  Verify an in-place upgrade preserves sessions/settings/home where supported.

F1 acceptance: launch the shell, install/update packages, run Python subprocesses,
Node/npm and Bun child processes, Go/gh, Git/SSH, tmux, and the user's selected
Codex/Claude/OpenCode/Agy builds. Include scripts, direct syscall paths, static
binaries, process identification, and self-updaters in the inventory. Test under
the actual target-37 app UID/SELinux context; success in `adb shell` is not proof.
Use off-device-built fixtures for any native compile/run coverage.

The documented linker workaround has limits: static executables, direct execve
syscalls, preload propagation and `/proc/self/exe` behavior need explicit attention.
If a critical workload fails, record the failure and decide between a package fix,
a VM backend for that workload, or a temporary legacy native runner. Target 37 is
configured; full workload compatibility remains unverified.

Sources: [Android executable restrictions](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission),
[termux-exec technical documentation](https://github.com/termux/termux-exec-package/blob/master/site/pages/en/projects/docs/technical/index.md),
[all-files access](https://developer.android.com/training/data-storage/manage-all-files),
[shared UID migration constraints](https://developer.android.com/guide/topics/manifest/manifest-element#uid),
[Android 17 target-dependent changes](https://developer.android.com/about/versions/17/behavior-changes-17).

## 2. Termux:API bridge foundation

Can begin alongside F1; do not couple its design to the old shared UID.

- [ ] **B1 - Capability discovery.** A proposed `termux-capabilities --json` reports
  device/API version, public API availability, permissions, Shizuku status, AVF
  capabilities and ML model readiness. Distinguish unavailable from denied.
- [ ] **B2 - Versioned request protocol.** Explicit caller authentication, operation
  names, request IDs, streaming, timeouts, cancellation and stable errors. Preserve
  existing useful CLI names through adapters. A same-signature APK check must not
  be replaced by an unauthenticated localhost endpoint.
- [ ] **B3 - Files and Android UI.** Selected-file import/export, share/open intents,
  notifications and clipboard actions within Android's lifecycle rules. Reuse
  existing Termux:API functionality before introducing new commands.
- [ ] **B4 - Optional Shizuku adapter.** User-visible authorization and a narrow set
  of shell-privileged operations. Handle service loss and reboot/re-authorization.
  Ordinary API operations continue working when Shizuku is unavailable.
- [ ] **B5 - Device I/O and discovery.** Modern BLE scan/connect/GATT operations,
  richer USB descriptors, NFC sessions, LAN service advertisement/discovery and
  a storage picker. Port useful existing patches onto current Android permission
  and lifecycle handling. Advertisement and discovery are distinct operations.

Acceptance: one native command and one authorized guest command call the same
bridge operation; cancellation, permission denial, bridge restart and an
unauthorized caller produce the expected behavior. Camera/microphone and other
sensitive operations retain their required consent and foreground UI flows.

## 3. AVF Linux workspace

Depends on B1/B2 and an actual custom guest boot, not just the advertised feature.

- [ ] **V1 - Hardware-backed boot.** Declare and obtain the two development VM
  permissions, probe hidden API compatibility, boot one known-good ARM64 distro,
  and explicitly show the active backend. Do not silently substitute QEMU emulation.
- [ ] **V2 - Session integration.** Native/VM/SSH session picker, PTY resize, UTF-8,
  signals, exit codes, attach/detach and guest tmux support. SSH is a useful first
  integration; a versioned vsock guest agent is the planned direct transport.
- [ ] **V3 - Resource and lifecycle controls.** User-set memory/vCPU/disk limits,
  start/stop/status, clean shutdown and reconnect after UI restart. Validate the
  Android service lifecycle and VM owner-process death behavior.
- [ ] **V4 - Project and network bridge.** Selected shared directories, port
  forwarding, guest-to-Android API requests and file exchange. Keep VM build trees
  on the guest filesystem when POSIX permissions or performance require it.
- [ ] **V5 - Developer workloads.** Validate Podman/Docker with the guest's kernel
  configuration, standard distro tools, agent CLIs and disk export/import while
  stopped. Live snapshots, GPU acceleration and arbitrary distro images are later
  work, not assumed AVF features available to this app.

Use a non-protected VM for the initial custom Linux environment. A separate kernel
is not the same guarantee as protected-VM isolation against the Android host.
Ordinary Termux already executes ARM64 instructions natively; the VM adds Linux
kernel/ABI compatibility and avoids PRoot syscall translation for guest workloads.

### Storage optimization in progress: Virtio-FS

- [ ] **V6 - Direct Termux/Arch directory sharing (separate future PR).** Evaluate
  Virtio-FS as an alternative to the working SSHFS-over-SSH/vsock share. First
  verify that the Pixel's AVF/crosvm implementation exposes a usable host-side
  filesystem backend and can access explicitly selected Termux directories under
  the existing permission model. Guest-kernel `CONFIG_VIRTIO_FS` support is also
  required; enabling that option alone is insufficient. If the host integration
  is feasible, add guest support and validate bidirectional visibility, permissions,
  symlinks, locking, reconnects and VM session lifetime. Compare small-file metadata
  operations and representative project builds against SSHFS and guest-local ext4.
  Keep SSHFS available until the new path is verified; lower overhead is a goal,
  not an established performance result. Prototype work, the reversible
  guest-kernel upgrade and opt-in normal-session Android shared-storage mounts
  are complete. Private-home access and comparative benchmarks remain open.

  **2026-09-21 prototype checkpoint:** two disposable diskless VMs successfully
  attached the AVF filesystem backend for a new Android Downloads directory.
  The same backend was denied Termux-private directory traversal by SELinux.
  Running an extracted backend as the app instead failed mount-namespace creation.
  A subsequent CI-built kernel and diskless initramfs passed Pixel mount, read,
  write, rename and unmount tests on 2026-09-22 UTC (API commit `e909c7e`). No
  speedup is established. The subsequent kernel upgrade/rollback passed on the
  existing Arch disk (API commit `a7ac531`): new-kernel boot, live-owner lock
  rejection, old-kernel rollback boot, reinstall and clean shutdown. The new
  kernel is installed. API `adc3dc2` and CLI `8096892`/`5c831d7` subsequently
  passed normal-session file I/O, rename, suspend/resume, automatic unmount and
  shutdown, restart persistence and disable/re-enable on the Pixel.
  `termux-arch-vm --share-enable [ANDROID_FOLDER]` selects the folder; this
  Pixel uses `Download/AetherShared` at `/mnt/android`. A status-query hang
  found during suspension was fixed and the approved VM recovery completed.
  A guest-kernel toggle alone cannot enable private-home sharing. See the
  [device findings and next implementation gate](VIRTIO_FS_FEASIBILITY.md).

Sources: [Podroid AVF setup](https://extv.github.io/Podroid/guide/backends.html),
[Podroid AVF implementation](https://github.com/ExTV/Podroid/tree/main/app/src/main/java/com/excp/podroid/engine/avf),
[AOSP Linux development environment](https://source.android.com/docs/core/virtualization/usecases#linux-development-environment).

## 4. Terminal and desktop experience

UI work can proceed independently after the foundation branch is stable.

- [ ] **U1 - Tabs, panes and workspace persistence.** Named sessions, saved working
  directories and layout restoration. Distinguish restored layout from a process
  that actually survived; reconnect through tmux where appropriate.
- [ ] **U2 - Modern terminal protocols.** Kitty graphics alongside sixel/iTerm,
  OSC 8 links, shell integration and modern keyboard reporting. Test image lifetime,
  alternate-screen behavior, tmux placeholders, Unicode width and paste handling.
- [ ] **U3 - External-display ergonomics.** Multiple independent session windows,
  keyboard shortcuts, mouse capture/selection, drag/drop and per-display density.
  Preserve sessions while docking, undocking and resizing.
- [ ] **U4 - Renderer performance.** Measure frame time, input latency and allocations
  before adopting a glyph atlas or OpenGL renderer. Evaluate libghostty-vt separately
  from renderer replacement. Support high-refresh scrolling without busy rendering
  when idle.
- [ ] **U5 - Session handoff.** Share remote-session restoration metadata between
  Android devices. Reconnect to an existing SSH/tmux endpoint; do not claim local
  process or VM memory migration.

Reference implementations: [TermuxEnhanced](https://github.com/NIK2703/termux-enhanced),
[termux-ghostty](https://github.com/mrndstvndv/termux-ghostty),
[MagicDesk](https://github.com/mekhontsev/magicdesk),
[Kitty graphics specification](https://sw.kovidgoyal.net/kitty/graphics-protocol/).

## 5. Pixel hardware and reliability features

- [ ] **H1 - On-device assistance.** Selected-output explanation, log summaries and
  screenshot/OCR input. Put ML Kit integration in the bridge, but provide a visible
  bridge Activity or an app-hosted integration for APIs requiring the calling app
  to be top foreground. A background Termux:API service cannot satisfy that rule.
- [ ] **H2 - Voice input.** On-device speech transcription into an editable terminal
  input panel. Dictation prepares text; it does not automatically execute it.
- [ ] **H3 - Diagnostics.** App exit reasons, Android 17 profiling triggers, image
  cache/VM memory use, thermal state and opt-in diagnostic exports. Do not promise
  that Java heap reports explain every native child-process termination.
- [ ] **H4 - Sustained workload controls.** Battery/thermal-aware limits for workloads
  managed by this app, renderer throttling and optional VM resource presets. Avoid
  claiming app-level controls override Android scheduling or process limits.
- [ ] **H5 - Hardware-backed credentials.** Extend the existing Termux:API Keystore
  support with encryption/decryption and an SSH-agent bridge, advertising only
  algorithms and hardware backing actually available on this device. Never require
  exporting a hardware-protected private key.

Sources: [ML Kit device support and foreground limits](https://developers.google.com/ml-kit/genai),
[Android 17 features](https://developer.android.com/about/versions/17/features),
[Thermal API](https://developer.android.com/games/optimize/adpf/thermal),
[Android Keystore](https://developer.android.com/privacy-and-security/keystore).

## SIMD requirement across app, native packages and VM workloads

Read directly through ADB from this Pixel's `/proc/cpuinfo` on 2026-09-16.
All seven listed processors advertised the following selected features. These
are capability observations, not evidence that any installed workload uses them.

| Capability | Observed CPU flags | Candidate workloads |
| --- | --- | --- |
| NEON / Advanced SIMD, FP16 and dot products | `asimd`, `asimdhp`, `asimddp`, `asimdfhm` | Image conversion, audio, quantized CPU inference and vectorized native loops |
| Scalable vectors | `sve`, `sve2` | Libraries with SVE/SVE2 kernels for bulk data processing and numeric workloads |
| Integer and BF16 matrix operations | `i8mm`, `bf16`, `svei8mm`, `svebf16` | Supported INT8/BF16 inference and matrix kernels |
| Scalable matrix extensions | `sme`, `sme2`, `smei8i32`, `smef16f32`, `smeb16f32`, `smef32f32`, `smei16i32`, `smebi32i32` | Experimental CPU matrix kernels after compiler, ABI and runtime validation |
| Crypto and checksum instructions | `aes`, `pmull`, `sha1`, `sha2`, `sha3`, `sha512`, `crc32` | Existing optimized crypto/checksum libraries used by SSH, TLS and file tools |

The default SVE/SME vector-length reads returned no value in the ADB check.
Do not infer a vector width or lack of support from that result. CPU SIMD/matrix
extensions are distinct from GPU or Tensor accelerator access.

- [ ] **S1 - Runtime capability probe.** Extend B1 with a small CI-built native
  helper using `getauxval(AT_HWCAP)` / `getauxval(AT_HWCAP2)` and supported
  `PR_SVE_GET_VL` / `PR_SME_GET_VL` queries. Report feature availability, vector
  lengths and errors from the actual app/package process. Repeat inside the AVF
  guest; host flags do not establish the guest's available instruction set.
- [ ] **S2 - Audit actual binary dispatch.** Inventory the highest-use image,
  audio/video, compression, crypto and inference libraries. Record build options,
  included kernels and the path selected at runtime. ARM64 Android already has
  NEON enabled by default; compiled support does not prove hot code uses it.
  Package binaries have their own build systems, separate from the APK's JNI flags.
- [ ] **S3 - Optimize measured workloads.** Prefer upstream optimized library
  kernels. Compare NEON against supported SVE2/I8MM/BF16 paths, introducing SME2
  only where a maintained implementation and toolchain support exist. Keep a
  known-correct baseline and runtime dispatch for optional extensions. Scope
  explicit target flags to eligible kernels; do not use host `-march=native`
  during cross-compilation or globally assume a CPU name from the phone model.
- [ ] **S4 - Verify execution and correctness.** Combine selected-backend reporting,
  artifact disassembly and profiles with reference-output checks. Use tolerance
  checks for floating-point kernels and edge cases for vector tails/alignment.
  SVE code must respect the executing thread's vector length; SME requires correct
  streaming-mode/ZA handling and ABI support. Baseline dispatch must itself run
  without the optional instructions it is selecting.
- [ ] **S5 - Benchmark on the Pixel.** Compare identical inputs and versions, both
  short runs and sustained workloads, recording throughput, latency, temperature
  and available power measurements. Optimize terminal parsing/render preparation
  only when profiles identify a native hotspot. Accept gains without correctness,
  input-latency or sustained thermal regressions; do not promise a speedup from
  instruction availability alone.

First targets are whichever installed packages profiling identifies as expensive;
image codecs, media processing and local CPU inference are candidates, not a claim
that each currently ships SVE2 or SME2 kernels. Retain established cryptographic
implementations rather than writing custom crypto. Evaluate LTO/PGO separately
after obtaining a reproducible baseline. Do not globally replace `-Os` with `-O3`
and treat that as SIMD validation; Java/Kotlin code uses ART's compilation path.

Acceptance: capability JSON from native and guest contexts; recorded selected
backends and correctness results; reproducible before/after measurements for at
least one real user workload. All compilation happens off-device; only installed
probes and benchmarks run on the Pixel. This was the initial acceptance plan;
later measurements and implementation checkpoints below supersede its original
unmeasured status. The user subsequently authorized native compilation on the
Pixel 11; off-device compilation is not a current restriction for that device.

Sources: [Android NEON defaults](https://developer.android.com/ndk/guides/cpu-arm-neon),
[Android runtime CPU detection](https://developer.android.com/ndk/guides/cpu-features),
[Linux SVE userspace ABI](https://docs.kernel.org/arch/arm64/sve.html),
[Linux SME userspace ABI](https://docs.kernel.org/arch/arm64/sme.html).

## Termux:API fork and pull-request candidates

Surveyed leading forks and the current open upstream PR list on 2026-09-16.
The entries below are all open proposals at the time of inspection. Metadata,
descriptions, and selected source hunks were inspected; none has received a full
code review or Android 17 device validation here. Fork popularity and recent push
dates are not proof that a feature is usable. Start the new fork from upstream,
then adapt individual reviewed changes with their matching CLI changes.

### First candidates

| Feature | Fork / proposal | Proposed use and integration work |
| --- | --- | --- |
| USB device and configuration descriptors | Grimler91, [PR 759](https://github.com/termux/termux-api/pull/759) | USB diagnostics and device tooling; includes protobuf transport and needs API-package PR 204 |
| NFC technology APIs | champignoom, [PR 796](https://github.com/termux/termux-api/pull/796) | Stateful tag sessions and technology-specific requests; preserve compatibility with existing NDEF commands and inspect actual Pixel tag support |
| Keystore encryption/decryption | EduardDurech, [PR 556](https://github.com/termux/termux-api/pull/556) | Local secrets and credential workflows; old cross-repository patch needs a fresh design/security review, including authentication and ciphertext format |
| Network service advertisement | sarg, [PR 774](https://github.com/termux/termux-api/pull/774) | Advertise guest/host services on LAN; inspected code adds NsdManager registration, so separately design browse/resolve rather than assuming the title proves discovery support |
| Sensor capability metadata | Biswa96, [PR 785](https://github.com/termux/termux-api/pull/785) | Populate capability reports with vendor, range, resolution and power information; a narrowly scoped candidate |

### Optional feature candidates

| Feature | Fork / proposal | Proposed use and integration work |
| --- | --- | --- |
| Bluetooth scanning | nullablepointer, [PR 686](https://github.com/termux/termux-api/pull/686) | Existing classic discovery implementation; uses legacy BLUETOOTH/BLUETOOTH_ADMIN permissions, so modernize for API 37 and add BLE/GATT separately |
| Bluetooth microphone routing | 1d10t, [PR 857](https://github.com/termux/termux-api/pull/857) | Headset dictation/recording; validate routing, disconnect cleanup and microphone foreground rules |
| Camera video recording | james28909, [PR 904](https://github.com/termux/termux-api/pull/904) | Camera2 video plus audio; author documents missing foreground handling and no physical-lens selection, both relevant to the Pixel |
| Android settings access | john-peterson, [PR 875](https://github.com/termux/termux-api/pull/875) | Selected read/write operations with permission discovery; validate settings namespaces, deletion semantics and modern restrictions |
| Accessibility operations | Benjamin-Loison, [PR 840](https://github.com/termux/termux-api/pull/840) | Optional user-enabled Android UI automation; investigate scope, lifecycle, encoding and caller authorization before adoption |

Existing upstream Keystore signing/verification is visible in
[KeystoreAPI.java](https://github.com/termux/termux-api/blob/master/app/src/main/java/com/termux/api/apis/KeystoreAPI.java).
The candidate feature is an extension, not a claim that upstream lacks Keystore.
AVF management and the modern authenticated bridge are separate planned work; this
fork survey did not establish a drop-in implementation for those requirements.

## First implementation slice

1. Prepare the fork of current upstream Termux:API and matching CLI ownership;
   record app identity, signing and IPC decisions before the first daily-use install.
2. Produce CI-built target-37 native-execution and SIMD capability probes using the
   real bootstrap paths; report CPU features from the actual app context.
3. Run the F1 workload checks on this Pixel and document failures before migration.
4. Establish private/shared/guest storage behavior and authenticated bridge transport.
5. Boot one AVF guest and attach it as a Termux session.

## Implementation checkpoint: 2026-09-18

The installed app defaults to target SDK 37, an ARM64-only APK and the Pacman
bootstrap. The Pixel has passed native shell/Python execution, private storage,
known-answer SHA-256, five-sample benchmarks and visual sixel rendering. The
Android 17 selection-menu fix was also verified on the device. Optional tools
absent from PATH remain skipped; F1's full developer-workload matrix is unfinished.

The validation runner is published on `dev` in `bf719c3c`; the updated protocol and
manual rendering check are documented in [PIXEL11_VALIDATION.md](PIXEL11_VALIDATION.md).
HWCAP/SVE/SME results establish availability and current-thread vector lengths,
not optimized library dispatch or a measured SIMD speedup.

The next bridge slice is implemented on `wallentx/capabilities` in both
[termux-api](https://github.com/wallentx/termux-aether-api/tree/wallentx/capabilities) and
[termux-api-package](https://github.com/wallentx/termux-aether-api-package/tree/dev).
`termux-capabilities --json` combines Android permissions, ARM64 capabilities,
battery/thermal observations and Shizuku connection/authorization state. It does
not request privileges or execute privileged operations. See the
[schema and identity decisions](https://github.com/wallentx/termux-aether-api/blob/wallentx/capabilities/docs/CAPABILITIES.md).

This slice retains compatible debug signing, the shared UID and existing
authenticated transport. It does not complete B1's AVF/ML inventory, B2's new
protocol or a private-signing migration. API 37 build/device validation and the
older API operations must be assessed separately.

B4 now has a validated first operation: `termux-shizuku --thermal`, with explicit
foreground authorization and a fixed thermal-service read running as shell UID
2000. Pixel tests verified denied/granted states, service loss, recovery after
Binder delivery, retained authorization and helper cleanup. API commit `237b480`
also fixes the access screen's Android 17 system-bar overlap. No arbitrary shell
endpoint is exposed; further privileged operations require individual scoping.

The next measurement slice adds API/thermal snapshots to the validator and a
CI-built scalar/NEON/SVE2 byte-difference benchmark. See
[the validation guide](PIXEL11_VALIDATION.md#explicit-scalar--neon--sve2-benchmark)
for correctness checks and measurement limits. This measures one explicit kernel,
not optimized dispatch throughout the installed package collection.

## Implementation checkpoint: 2026-09-19

The terminal redraw regression is fixed in `ab792707`, with callback-ownership
regressions passing in CI and live typing/counter/rotation checks passing on the
Pixel. Sixel row batching, text-buffer bulk operations, printable ASCII batching,
image draw batching and native bitmap-copy improvements are already implemented.

| Current work | State |
| --- | --- |
| Arch session lifetime | API `c49ea73`, CLI `157bfa6`: last-session clean shutdown and optional `--keep-memory` suspension verified on Pixel, including overlapping sessions, RAM-state retention, unchanged VM ID on resume, and HTTPS after resume. |
| Native glibc compatibility | Full Geekbench CPU run completed as native app UID 10445 without rish/Arch; result 402547. Identity, DNS, execve and posix_spawn probes pass. Expanded PATH exec, scripts, mixed glibc/Bionic children, and system() pass on both the development Pixel 8 Pro and target-SDK-37 Pixel 11. Runtime `8f56aa0d` fixes Bionic multicall symlink names; the bundled execution probe and native/wrapped env comparison pass under Pixel app UID 10445. General Linux compatibility is not claimed. |
| Default Aether packaging | `5e2f349c`: ordinary Pixel builds include the runtime, launcher, and probe; `-PaetherEnabled=false` remains available. Default CI `35480561298` produced one ARM64 APK plus matching sources and passed the on-device probe after update, preserving the earlier benchmark report. Opt-out CI `35480576689` verified absence of Aether payload. Release source uploads are configured but were not exercised by publishing a release. |
| Live Termux project sharing | CLI `3a95078`: `termux-arch-share LOCAL /mnt/project` uses rclone SFTP stdio and guest SSHFS over the existing authenticated SSH/vsock channel. Pixel read/write/new-file visibility/unmount tests pass. A share counts as active use and prevents idle shutdown. |
| Sparse disk growth and live RAM | API `60018b9`, CLI `3a95078`: Pixel live balloon test reclaimed 2 GiB from an 8 GiB guest and restored it without restarting. Disk grew from 6 to 16 GiB with saved data and SSH identity intact; shrinking was rejected and shutdown was clean. The offline 6 GiB backup is retained. No automatic pressure-based RAM policy is enabled. |
| Repeatable performance checks | Native Geekbench validation and package-dispatch runners save persistent results and thermal/power metadata. Pixel SHA-256 comparison passed (matching results; normal dispatch 1297 MiB/s vs disabled 213 MiB/s in this run). Visible text/sixel redraw/sixel replacement checks passed on `ab792707`; the collector now rejects zero-frame phases. Phone rotation during capture prevents treating these timings as a controlled performance comparison. |

Live sharing requires Termux `rclone` and guest `sshfs`, now installed on this
Pixel. Symlinks are omitted and Unix metadata/locking are not fully native; keep
build trees on guest ext4. The share serves the selected directory without a new
listening socket. See the CLI fork README for lifetime and caching semantics.

The September 19 native Geekbench run was charging and ended with light thermal
throttling. Its score cannot isolate glibc, AVF or rish overhead relative to the
earlier runs. A matched comparison still needs the user's planned PRoot install.

## Native-package optimization checkpoint: 2026-09-21

S2/S4/S5 now have a scoped installed-library audit, not a complete package audit.
The native Termux run confirms existing OpenSSL SHA-256 acceleration (9.18x
normal versus disabled dispatch) and libjpeg-turbo SIMD benefit (1.51x encode,
1.42x decode). JPEG bytes/pixels match across modes. Installed libdeflate also
outperformed zlib on the audited whole-buffer compression APIs, with exact
round trips; this library comparison does not isolate SIMD instruction usage.
The repeatable runner, raw samples, caveats and measurements are documented in
[PERFORMANCE.md](PERFORMANCE.md#installed-native-package-audit-september-21-2026).

Keep the working crypto/JPEG dispatch. The next S3 candidate is a concrete
bounded-buffer decompression consumer that can use libdeflate: first measure
that application's end-to-end workload before changing it. No global zlib
replacement or streaming/archive default change is justified by this audit.
Media, inference, SVE2/SME kernels and guest-side dispatch remain unaudited.

The first follow-up found avoidable repeated decompression in release source
verification. A single-pass hash verifier now takes 88.91 ms versus 200.45 ms
on the existing release fixture (2.25x throughput), with 29.3% lower peak process
RSS. This removes redundant work without adding libdeflate or changing archive
formats; it is a release-tool improvement, not a terminal-runtime speedup. See
[the measured comparison](PERFORMANCE.md#single-pass-release-source-verification).


## Rendering checkpoint: 2026-09-22

Two installed-app frame captures now record scrolling and sixel redraw/replacement,
with raw frame timestamps and separate terminal geometry. The collector supports
on-device Shizuku, sampled focus/rotation guards, per-frame deadline counters and
prompt workload cancellation. Synthetic input is unavailable through this preview's
input shell service; input-to-display latency remains unmeasured.

A narrow scalar printable-ASCII fast path in `WcWidth` passed exhaustive equivalence
across all Unicode code points. Paired ART measurements show 1.42x ASCII and 1.22x
mixed-text classification throughput, with Unicode-only unchanged. It is local
source, not installed in the APK; a matched APK frame comparison remains required.
See [performance evidence](PERFORMANCE.md#printable-ascii-width-classification-september-22-2026).
