# Aether native glibc runtime

The normal Pixel build includes Aether by default: one ARM64 APK, target SDK 37.
Build only in CI. `Build` workflow pushes need no special input; manual runs
include Aether unless the retained `aether_prototype` checkbox is disabled.
Existing rish support remains available. This runtime is not a Linux distribution
or a sandbox.

| Build selection | Aether |
| --- | --- |
| Default Pixel Gradle/CI build | Included |
| `-PaetherEnabled=false` | Omitted |
| CI repository variable `TERMUX_DISABLE_AETHER=true` | Omitted from Build and release APKs |
| `all_apks` / `TERMUX_BUILD_ALL_APKS=true` | Existing multi-ABI builds, Aether omitted |
| Diagnostic `-PpixelProbe=true` | Aether omitted unless explicitly enabled |

`-PaetherPrototype=true/false` remains a legacy alias; `aetherEnabled` takes
precedence. CI builds with Aether need `gcc-aarch64-linux-gnu` plus the Android
NDK. Opting out removes the APK's loader/helper, so existing Aether commands
will be unavailable until an enabled APK is installed and Termux is reopened.

The APK contains an executable glibc loader in Android's installed
native-library directory, matching Android-adapted glibc 2.44-0 libraries, and a
Bionic helper. `aether-run PROGRAM [arguments]`
uses the installed loader directly, bypassing the Bionic termux-exec interceptor.
The runtime is installed privately at `$HOME/../aether`; package-managed files
under `$PREFIX/glibc` are unchanged and supply optional dependencies such as
libgcc_s. No Geekbench binary is redistributed.

The glibc preload delegates getaddrinfo to a separate Bionic process, translating
flags and result/error structures. Android resolves these queries for Termux's
UID on its current default network. VPN/Private DNS policy is delegated to the
platform; a VPN transition has not yet been tested. The two libcs never coexist
in one process. File wrappers map `/etc/resolv.conf` to Termux's existing resolver
file and common certificate paths to its CA bundle. Programs with independent
DNS implementations can still use the static resolv.conf contents; they are not
claimed to use Android Private DNS. Reads of `/sys/class/dmi/id/sys_vendor` and
`/sys/class/dmi/id/product_name` map to private files populated from Android's
`Build.MANUFACTURER` and `Build.MODEL` at app startup. This lets Linux programs
report the real device make/model without inventing DMI serial numbers or
motherboard information. It does not change the system's sysfs or CPU features. Static binaries/direct syscalls are outside
this preload's coverage. Do not claim universal `/etc` virtualization.

The preload also makes readlink(/proc/self/exe) report the requested program,
so programs such as Geekbench locate sibling assets. execve, execv, and
posix_spawn of dynamic Linux/AArch64 ELF children are routed through the same
loader. PATH-based `execvp`/`execvpe`/`posix_spawnp`, variadic exec calls,
shebang scripts, and glibc `system()` use the same dispatcher. `/bin/sh`,
`/bin/bash`, and `/usr/bin/env` shebangs map to Termux tools; other interpreters
must exist at their stated paths. `env` shebangs can find an interpreter on PATH.
The launcher itself accepts PATH names and scripts as well as ELF paths.

Android children run with a separate Bionic execution shim plus the launcher's
original Termux preload. Their children can switch back to glibc. The glibc
preload never enters a Bionic process. Child environments preserve ordinary
caller-supplied variables; reserved `AETHER_*` runtime settings and ABI-specific
`LD_PRELOAD`/`LD_LIBRARY_PATH` values are supplied by the dispatcher even with
an explicit custom environment. Runtime variables are not a security boundary.

`execvp`/`execvpe` implement shell fallback for executable text without a
shebang; `posix_spawnp` reports `ENOEXEC` instead. PATH comes from the calling
process (including for `execvpe`), with empty entries denoting its current
working directory. Execution mode bits are checked before invoking a loader.
`system()` preserves wait status, ignores SIGINT/SIGQUIT while waiting, and
handles concurrent calls and deferred thread cancellation.

Limits: spawn executable resolution currently occurs before file actions, so
relative executable/PATH lookup combined with spawn chdir/fchdir actions is not
supported. Use an absolute executable path for such calls. Direct syscalls,
`execveat`/`fexecve`, libc-internal launches such as `popen()`, static executables,
async-signal-safe post-fork execution, and programs that deliberately remove or
replace the shim are not covered. Existing Android syscall and filesystem
constraints still apply. This is not general `/usr` or `/bin` virtualization.

`aether-probe` checks app UID, executable identity, resolv.conf visibility,
Android manufacturer/model files, Android-backed DNS success/failure/numeric cases, PATH/variadic exec and spawn
children, mixed glibc/Bionic scripts, permission/recursion errors, and system()
exit/signal/concurrency behavior.
Run it in a native Termux session, not ADB shell or run-as, to validate SDK-37
execution restrictions. Then validate Geekbench --sysinfo and a real HTTPS
client. A successful probe is not a benchmark or evidence of a performance gain.

## Runtime provenance and source

The binaries were copied from the Pixel's Pacman-installed glibc 2.44-0 package,
whose local package database records PGP validation. `provenance.json` pins every
included ELF SHA-256 and the package recipe revision. CI checks those hashes
before building. The APK retains the loader unstripped so its hash is preserved.

Every Aether-enabled build publishes an accompanying source archive with
its APK: exact GNU glibc 2.44 source tarball, Termux package recipes/build scripts
at the recorded commit, compatibility sources, copyright/license notices, and
provenance. The archive also includes the complete downstream Git tree and a
manifest recording its commit and member hashes. CI verifies the packaged
runtime, bundled probe, licenses, and corresponding sources before publishing
APKs. The `aether-source` CI artifact contains `aether-source.tar.gz`; release
builds upload a version-and-commit-named source archive before uploading APKs.
A publication failure preserves the release and tag for retry.
The preload and launcher sources in this directory use the repository's license;
the glibc libraries retain their upstream licenses. See COPYING.LIB and LICENSES.

### Source-verification performance

`verify-package.py` reads the gzip source archive once, hashes regular members in
1 MiB chunks, and compares the resulting hashes with the manifest, runtime
provenance and checkout sources. The manifest may appear anywhere in the archive.
It rejects duplicate or non-regular file entries and drains the gzip stream to
validate its trailer. APK, source-commit and required-member checks remain active.
It does not extract source files or require an additional compression library.

To compare verifiers with an existing matching release APK/source pair:

```sh
git show 266b0cba:scripts/aether/verify-package.py > "$TMPDIR/aether-verify-before.py"
python3 -B scripts/aether/benchmark_package.py \
  --baseline "$TMPDIR/aether-verify-before.py" \
  --apk /path/to/release.apk --source /path/to/aether-source.tar.gz
```

The benchmark reconstructs the historical checkout inputs and generated payload
paths in a temporary fixture. Only the fixture's Git HEAD lookup is substituted
with the archive's recorded source commit; this is a benchmark, not a substitute
for release verification against an actual checkout. It does not build or install
anything. Five paired samples alternate implementations after warmup, each in a
fresh process. JSON results under `~/benchmarks/` contain input hashes, raw times
and peak process RSS (Linux/Android KiB). Fixture preparation and interpreter
startup are excluded from timing; peak RSS includes the whole child process.

See [measured verification results](../../docs/PERFORMANCE.md#single-pass-release-source-verification)
for the observed speed and memory improvement, separate from terminal runtime.

## Repeatable installed-runtime validation

Run `python scripts/aether/validate.py --cpu` from a native Termux session, or
`aether-validate --cpu` when the script is installed on PATH. `--probe` selects the
CI-built probe binary; `--geekbench` selects an existing Geekbench binary. The
probe and Geekbench are not downloaded by the runner. Omit `--cpu` for the short
identity/DNS/child-process checks and Geekbench system information.

Reports and logs default to a fresh `~/benchmarks/aether-YYYYMMDD-HHMMSS/`
directory. They include actual UID/SELinux context, exit codes, wall time, and
battery/thermal snapshots before and after the run. Geekbench preview uploads
its CPU results. Run comparisons under matched charging and thermal conditions.

Pixel validation on 2026-09-19: all probes, system information and the full CPU
benchmark passed under app UID 10445 (`untrusted_app`), without rish or an Arch VM.
The CPU run took 414.5 seconds: https://browser.geekbench.com/v7/cpu/402547 . The
phone was charging; battery temperature went from 37.5 C to 39.3 C and Android
reported light throttling at the end. This establishes compatibility, not an
apples-to-apples performance comparison with earlier VM or shell runs.

Expanded child-process validation: CI `35477346922` built `36e9fbd1`; the full
execution probe passed on the development Pixel 8 Pro (Android API 37, UID 10514)
using extracted CI binaries, without local compilation or installing an APK.
This includes glibc shebang optional arguments, preserved argv0, and cancellation
that reaps the system() shell. Antigravity's installed `agy.va39` 1.2.0 passed
`--version` and `--help` through the earlier `7853f2fa` launcher; this does not
validate an authenticated agent workload or its updater. Pixel 11 verification subsequently passed with `8f56aa0d` (CI `35478113494`),
under native app UID 10445 and target SDK 37. Its Pacman `env -> coreutils`
symlink exposed an Android handoff bug: resolving Bionic paths to their final
binary lost the applet name. The dispatcher now preserves the invoked Bionic
path. Both native `env sh -c 'exit 43'` and the Aether equivalent return 43,
and the full execution probe reports `AETHER_PROBE_PASS`.
The Geekbench result above validates the earlier explicit execve/posix_spawn path.

Default-package validation: the ordinary push build of `5e2f349c`
([CI 35480561298](https://github.com/wallentx/termux-aether-app/actions/runs/35480561298))
produced one ARM64 APK and a matching source archive. After installing that APK
on the Pixel 11, its bundled probe passed under native UID 10445 in
`untrusted_app`, with target SDK 37; the wrapped `env` check returned 43.
The previous benchmark report survived the update byte-for-byte. Results are
saved on-device at `~/benchmarks/aether-default-5e2f349c/probe.json`.
The explicit opt-out build
([CI 35480576689](https://github.com/wallentx/termux-aether-app/actions/runs/35480576689))
also passed and verified that its APK contains no Aether payload. Unit tests
passed in CI `35480561311`. Release-source publication is configured and
statically checked; no release was published for this validation.

The APK also installs its matching probe with executable permissions.
After updating and reopening Termux, run:

```sh
aether-run "$HOME/../aether/aether-probe"
```

This needs no separate probe download and performs no CPU benchmark or upload.
The full validation runner can select it with
`aether-validate --probe "$HOME/../aether/aether-probe"`.
