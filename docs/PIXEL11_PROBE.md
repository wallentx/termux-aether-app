# Pixel SDK and SIMD diagnostic build

Status: source and CI workflow prepared; APK build and device execution pending.
No local Android or native builds have been run.

## What this first slice establishes

The opt-in `-PpixelProbe=true` build uses the Pacman bootstrap, compiles against SDK 37.2, targets API 37,
requires API 37, packages ARM64 only, and adds a diagnostic Activity to the real
`com.termux` APK. The regular build also targets 37 and compiles against 37.2, without the probes. The probe
uses the same JNI PTY launcher as `TerminalSession`, rather than executing its
checks under the more privileged ADB shell UID.

| Probe | Evidence |
| --- | --- |
| CPU capabilities | `getauxval` feature bits, SVE/SME vector lengths through `prctl`, errors and page size |
| System control | The JNI launcher can execute `/system/bin/toybox true` |
| Private direct execution | The same ELF copied to app-private writable storage is rejected by the target-37 sandbox |
| Private linker execution | `/system/bin/linker64` can execute that copied dynamic ELF |
| Existing bootstrap | If installed, launch its shell directly, through the linker, and a child shell with the installed termux-exec preload |

Each command has a 10-second timeout and a 16 KiB output cap. The report records
exit status, signal, timeout, truncated output and collection errors. The copied
fixture is deleted after use. Diagnostic output stays in private app storage;
it contains the device fingerprint and paths and should be reviewed before sharing.
Native output bytes outside printable ASCII are escaped individually in JSON.

This does not execute optional SIMD instructions, benchmark kernels, install or
update packages, test raw execve/static binaries, install a bootstrap, or prove
that normal terminal sessions work at target 37. It explicitly reports a missing
bootstrap. The normal launcher now uses the system linker for app-private dynamic
programs; these diagnostic cases intentionally also retain direct execution as a
negative control. One existing JNI error
is fixed: release the cwd UTF string with its matching Java string handle.

## Identity and repository decisions

- App identity remains `com.termux`, preserving package-prefix assumptions. This
  diagnostic APK shares the normal app's identity; it is not a side-by-side app.
- Diagnostic builds use the repository's existing public, untrusted debug key.
  They are temporary test artifacts, not the private signing identity for daily use.
  Do not uninstall an existing installation to bypass a signature mismatch.
- Daily builds need a persistent private key shared with signature-authenticated
  companion apps. Existing sharedUserId is retained in this probe; migration to
  separate UIDs needs a deliberate install/backup strategy before daily use.
- Android bridge source: [wallentx/termux-api](https://github.com/wallentx/termux-aether-api),
  forked from current upstream. CLI source:
  [wallentx/termux-aether-api-package](https://github.com/wallentx/termux-aether-api-package), also
  forked from upstream. Neither has bridge implementation changes yet.
- Planned transport: signature-protected Android IPC, with separately authenticated
  guest requests. AVF and Shizuku are later adapters, not required for this probe.

## Build and collect evidence

1. Publish the reviewed change and run **Pixel SDK and SIMD probe** in GitHub
   Actions. Download `pixel-probe-arm64-target37`. Only CI runs the Gradle build:

   ```sh
   ./gradlew --no-daemon :app:assembleDebug -PpixelProbe=true
   ```

   The workflow installs `platforms;android-37.2`, build-tools 37.0.0 and NDK
   29.0.14206865, verifies the APK's target/min SDK and ARM64-only native libraries,
   and uploads a SHA-256 alongside it. SDK 37.2 availability was confirmed from
   Google's package catalog; successful Gradle/NDK integration still needs CI.

2. Verify the connected device and any existing install before installing the
   downloaded APK. Use the current wireless-debugging connection port, not its
   pairing port. The last connection was `192.168.1.203:41561`:

   ```sh
   adb -s 192.168.1.203:41561 shell getprop ro.product.model
   adb -s 192.168.1.203:41561 shell pm list packages com.termux
   ```

   Install only after checking whether the APK would replace a current Termux
   installation and whether its signature matches. A fresh test installation
   will report missing bootstrap until the normal installer has populated it.

3. Launch the fixed diagnostic as the authorized ADB shell. No caller-supplied
   commands or filesystem paths are accepted:

   ```sh
   adb -s 192.168.1.203:41561 shell am start -W -n com.termux/com.termux.app.pixel.PixelProbeActivity
   ```

   The Activity requires Android's `DUMP` permission, which the ADB shell has.
   Wait for the JSON report to appear on screen, at most about 60 seconds for six
   timed-out commands. A new run removes the previous report before collecting
   results. If the screen says the probe failed, inspect that error instead of
   reusing an earlier downloaded report; check its `collected_at_ms` timestamp.

4. Collect and validate the report, without running builds on the device:

   ```sh
   adb -s 192.168.1.203:41561 exec-out run-as com.termux cat files/pixel-probe/report.json > "$TMPDIR/pixel-probe-report.json"
   python scripts/pixel-probe/check_report.py "$TMPDIR/pixel-probe-report.json"
   python scripts/pixel-probe/check_report.py --require-bootstrap "$TMPDIR/pixel-probe-report.json"
   ```

   The first check covers capabilities and the controlled execution fixture.
   The stricter check also requires successful bootstrap shell and child execution.
   Neither is a claim of complete F1 workload validation or SIMD acceleration.

5. Use the first failing gate to choose the next change. If the private linker
   fixture succeeds, continue through bootstrap/preload configuration and the F1
   workload inventory. If SVE/SME is advertised but its vector-length query fails,
   retain that error and investigate before enabling optional kernels. Repeat CPU
   detection inside the future AVF guest before selecting guest kernels.

## Lightweight local validation

These commands do not compile the app or native code:

```sh
PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s scripts/pixel-probe -p 'test_*.py'
actionlint .github/workflows/pixel_probe.yml
git diff --check
```

References: [SDK minor-version DSL](https://developer.android.com/reference/tools/gradle-api/8.13/com/android/build/api/dsl/CompileSdkSpec),
[Android runtime CPU detection](https://developer.android.com/ndk/guides/cpu-features),
[SVE ABI](https://docs.kernel.org/arch/arm64/sve.html),
[SME ABI](https://docs.kernel.org/arch/arm64/sme.html).
