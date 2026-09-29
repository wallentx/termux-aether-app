# Shizuku session validation

Normal SDK-29+ Aether sessions and TermuxService background commands require the
session UserService. Recovery sessions retain the legacy launcher. Do not replay
failed commands through another backend.

Run `./gradlew test` and build the APK in CI. Focused tests are
`SessionCommandTest`, `SessionStorageTest` and `AppShellBackgroundTest`. `NativeSessionProbe.java` can be
compiled alongside `SessionNative`, `SessionSignals`, and `SessionCommand`, converted to DEX, and
run through Shizuku's shell UID with the new `libaether-session.so` in a private
temporary directory under `/data/local/tmp`. Its first argument is that directory;
its second is a Termux-private directory containing the same DEX and library for
the app-UID signaling helper. Preserve Android runtime environment variables,
including `BOOTCLASSPATH`. DEX and native libraries must be read-only before loading. Remove only
the probe's own directory afterward. It tests the actual native implementation,
but does not substitute for APK/Binder integration testing. Require the final
`PASS` in `probe.log`, not merely the rish transport exit code.

`StorageProbe.java` exercises the actual Java storage bridge without modifying
the user's shortcuts. Compile it with `SessionStorage` and a fixture-only
`TermuxConstants.TERMUX_HOME_DIR_PATH` under
`$TMPDIR/aether-session-check/storage-home`, then convert it to DEX. Run it in
the normal app context, leave stdin open, and wait for `STORAGE_READY <path>`.
Through Shizuku + `run-as com.termux`, create/read/delete a uniquely named file
under `<path>/Download`. Send a newline to the probe to finish. Use a fresh
fixture home on each run; never compile this probe with the real HOME constant.

Pixel validation on 2026-09-25 (Android 17 / API 37):

| Check | Result |
| --- | --- |
| Focused command/storage JUnit tests | 9 passed |
| New session Java, generated AIDL, terminal-emulator compilation | Passed against Android SDK stubs and Shizuku 13.1.5 |
| Actual native PTY + run-as launcher | Repeated exit 23, UTF-8, PTY EOF, forced exit -9 and background cleanup passed |
| Actual Java storage bridge | Repeat preparation, custom shortcut preservation, run-as create/read/delete passed |
| Installed preload with linker execution disabled | Original Go/Gum and stock-Go-built self-exec/child-process probe passed |

These initial results are component/device probes. No new performance claim is
established by these probes.

Installed APK `1000.0.0+8012086` was checked later on 2026-09-25 after its build
and unit-test workflows passed. The active zsh/Codex process ancestry reports
the Termux UID in `runas_app`, `AETHER_SESSION_BACKEND=shizuku-runas`, and linker
execution disabled. This exercises the installed session path and Binder PTY
transfer, rather than only a separately launched shell probe.

| Installed-session check | Result |
| --- | --- |
| Original Go 1.27.1, Gum and gh executables | Start successfully; interactive Gum selected Beta and exited 0 |
| Original Go compiler/runtime | Pure-Go and cgo builds, argv/identity, spawn/re-exec, native children, prefix shebangs, relative cwd, `go run`, and `go test` passed |
| Storage and API | `~/storage/downloads` and `$EXTERNAL_STORAGE/Download` write/read/delete and `termux-battery-status` passed |
| Temporary directory | Installed launcher lost `TMPDIR`; corrected command builder passed eight unit tests and a real run-as control/fix comparison; installed APK `8a64440` restored `$PREFIX/tmp` and passed write/read/delete on 2026-09-26 |
| Foreign script interpreter paths | Stock Go raw exec of `#!/usr/bin/env` failed; rewriting installed scripts with `termux-fix-shebang` passed, including `env -S` |

Go's missing-TMPDIR diagnostic still built successfully with the stock package's
default temporary directory. Subsequent compiler probes explicitly supplied
temporary directories; they do not prove the installed environment bug fixed.
The first `go test` harness invocation used an invalid absolute package import;
rerunning `go test .` from the package directory passed. Evidence is retained in
`~/.local/state/aether-session-implementation/installed-8012086/` on the Pixel.

Rotation/resize, service loss, explicit recovery, forced session closure,
app restart and reboot still require the remaining installed-APK checks below.
Do not stop Shizuku while the active development session depends on it.

### Background execution retirement gate

On 2026-09-26, installed APK `1000.0.0+8a64440` was tested through the actual
`RunCommandService` with `RUN_COMMAND_BACKGROUND=true`. The intent was sent
using Termux's `$PREFIX/bin/am` under its own UID, without granting permissions.
Shizuku's shell UID lacks `com.termux.permission.RUN_COMMAND`; `/system/bin/am`
under the app UID also fails its calling-package check. Neither failed launch
was counted as a runtime test.

The service launched a Python probe that recorded its UID, SELinux context,
selected execution environment, and each command's stdout/stderr and exit status.
The same probe ran in the normal installed terminal as the control. Both used
UID 10445; their SELinux domains and launch environments differed.

| Check | Normal terminal (`runas_app`) | Background task (`untrusted_app`) |
| --- | --- | --- |
| Original Go 1.27.1, explicit stock `GOROOT`, `go version` | Exit 0, expected version | Exit 2, executable path interpreted as an unknown command |
| Original Gum, `--version` | Exit 0 | Exit 80, executable path interpreted as an unexpected argument |
| Stock-built pure-Go identity/argument probe | Correct argv and executable path | Exit 0 but duplicated executable argument and `os.Executable()` returns Android's linker |
| Stock-built cgo child-process probe | Child ran with expected arguments | Exit 2 after child launch failure |
| Installed Aether Go and Gum version commands | Both exit 0 | Both exit 0 |

The standalone copied `stock-go` without `GOROOT` also failed in the normal
terminal because its trimmed build cannot locate the moved toolchain. That is
not evidence of a launcher regression; the explicit-root comparison above
isolates the background failure. Likewise, the pure-Go probe's zero exit status
does not mean success: the shifted arguments prevented its requested spawn mode
from running.

Evidence and the probe script are saved on the Pixel at
`~/.local/state/aether-session-implementation/retirement-8a644402/`
(`normal-results.json`, `background-results.json`, `background_probe.py`).
The background report confirms the linker preload and no normal-session backend
marker. No installed packages were replaced and no custom toolchain machinery
was removed. Recovery, service-loss and the other remaining UI/lifecycle checks
were not validated by this probe.

Retirement remains blocked until background execution supports the original
binaries. A Shizuku + `run-as` background runner must preserve non-terminal stdin,
separate stdout/stderr, exit status, cancellation and plugin result delivery;
using the terminal PTY path alone is not an equivalent replacement. Explicit
recovery behavior must also be settled before removing its compatibility tools.

### Installed-script shebang repair

The bundled `termux-fix-shebang /path/to/installed-script` rewrites the interpreter
path to the Termux prefix. For example, `#!/usr/bin/env -S sh -e` becomes
`#!/data/data/com.termux/files/usr/bin/env -S sh -e`, preserving `env`'s argument
splitting and PATH lookup. Original Go programs can then launch the script
through their raw exec syscalls without a custom Go runtime.

On 2026-09-25, 45 standalone personal scripts and five installed package commands
were repaired on the Pixel. Backups, hashes and modes are recorded in
`~/.local/state/aether-session-implementation/shebang-repair-20260925T205510Z/manifest.json`.
Every change was checked to affect only the first interpreter path and preserve
the file mode. Personal symlink targets, including source repositories, were
left unchanged; the `npm`/`npx` package symlinks still point to their original
installed targets.

`npm`, `npx`, `gdbus-codegen`, `glib-genmarshal`, and `glib-mkenums` all failed
through the original Go probe before repair and passed afterward, using both
pure-Go and cgo probes. A separate `env -S` fixture preserved spaced and empty
arguments. Package ownership remains unchanged, but these installed file contents
now differ from their package archives; an upgrade can restore the old shebang.
Apply the repair to installed copies after such upgrades. This is not a kernel
path alias or an automatic global interceptor. The tool follows symlinks, so do
not pass source-linked commands unless editing the source file is intended.

On the installed APK, validate:

1. Stop/start and revoke/authorize Shizuku access. New normal sessions must show
   setup when unavailable; only an explicit Recovery shell selection may use the
   legacy context. Existing commands must never be replayed.
2. Confirm `id -Z` reports `runas_app`, then run original Termux Go/Gum, a small
   `go build`/`go run`, and a self-spawning executable. `/proc/self/exe` must name
   the executable, with `TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=disable`.
3. Check typing, Home/End, Ctrl-C, Ctrl-Z, `bg`, `fg`, rotation/resizing, exit
   status and forced session closure. Other sessions must remain running.
4. Check `termux-battery-status`, HOME/TMPDIR, `$EXTERNAL_STORAGE`, and
   `~/storage/{shared,downloads,documents}` across shell children. Custom storage
   links must be preserved. Literal `/sdcard` paths are not remapped.
5. Restart the app and reconnect Shizuku, checking that storage shortcuts refresh
   and that exited sessions leave no launcher process or held PTY. Verify plugin
   terminal requests fail clearly when the required backend is unavailable.

The storage bridge holds a directory FD in the app process and publishes a
`/proc/<app-pid>/fd/<fd>` link beneath `~/.termux/aether-storage`. This preserves
the app's storage mount across subprocesses that close inherited descriptors.
It does not grant new Android storage permissions or mount over `/sdcard`.

This change routes terminal sessions and TermuxService background jobs, including
plugin commands, through Shizuku. App-internal callers that invoke `AppShell`
directly without a process factory retain their existing launcher (for example,
setup operations that precede the session service). Keep the installed
compatibility packages until the required workloads have also been validated.

### Background pipe implementation (installed validation pending)

The background runner uses the same owner-checked UserService and run-as command
transport as terminal sessions, with three independent pipes instead of a PTY.
`BackgroundProcess` adapts the Binder handle/callback to Java's `Process` API;
`AppShell` keeps its existing result collectors and plugin delivery. Empty stdin
now closes immediately to deliver EOF; supplied stdin retains AppShell's existing
trailing newline. Signals use the service's same-UID helper, with signal 9
reported as exit 137. The service kills remaining members of the background
job's session when its leader exits so they cannot keep its pipes open; processes
that deliberately create a separate session are outside that cleanup scope.
There is no local execution fallback or retry if Shizuku is unavailable.

Component validation on the Pixel, 2026-09-26:

| Check | Result |
| --- | --- |
| Native C, Java service/adapter, generated AIDL | Strict C compilation and focused Java compilation passed |
| Actual native background launcher | 256 KiB binary stdin/stdout, separate stderr, no TTY, empty-input EOF, exit 23, missing-command exit 127, runas_app identity, original Go/Gum and stock-built Go child launch passed |
| Native lifecycle | Cancellation -9, same-session descendant cleanup and output EOF passed |
| Java Process adapter through generated AIDL Proxy/Stub parcels | Literal arguments, descriptor ownership/EOF, timed wait, early exit, signal-to-137 mapping, service-death wait/read unblocking, completed output preservation passed |
| Terminal regression | Existing native PTY exit, UTF-8, EOF, cancellation and descendant cleanup probe passed |

`PipeSessionProbe.java` takes five arguments: shell staging directory, app staging
directory, original Go root, original Gum executable, and the stock-built
`scripts/go/probe.go` executable. Stage read-only DEX and `libaether-session.so`
in both directories, as for `NativeSessionProbe`; execute as Shizuku shell. It
uses real native children but does not start the installed app's background runner.
`BackgroundProcessProbe.java` uses a deterministic fake service with real pipes
and generated AIDL parcel transport to exercise the adapter on Android. Compile
it with the service/adapter classes and `AppShellProcess`, convert to read-only
DEX, and run via `app_process`. It neither stops nor restarts the live Shizuku
service. Require each probe's final `PASS` marker, not the rish transport status.

Logs are saved on the Pixel in
`~/.local/state/aether-session-implementation/background-pipes-20260926/`.
The new Robolectric `AppShellBackgroundTest` covers factory routing, stdin EOF,
separate results, supplied-input compatibility, failed-backend handling, and the
cancel-after-marking-failed regression; it still needs the CI test run. The full
APK build and installed RUN_COMMAND/plugin validation remain pending. Do not
replace the installed Go/Gum packages based on these component probes alone.

### Service-death cleanup

Each native launch has a monitor that owns the run-as child and an IPC socket
whose other end belongs to the UserService. The monitor blocks in `poll()`;
UserService death closes the socket and triggers the existing app-UID signal
helper. It kills the leader and remaining members of its Linux session,
including descendants that ignore SIGHUP or belong to another process group.
Descendants that deliberately create a separate Linux session remain outside
this cleanup scope. The monitor holds the child unreaped throughout cleanup,
so its PID/session ID cannot be recycled and accidentally target another job.

On normal exit, the monitor reports the original command status and waits for
`finish()` before reaping. Cancellation and normal descendant cleanup retain
the service's existing signaling path. No command is replayed after service
loss, and no per-job Java VM is kept running by the monitor. UserService version
3 ensures an APK upgrade does not retain a launcher loaded from older code.

`ServiceDeathProbe.java` starts isolated launcher fixture processes through the
Shizuku shell, then kills only those fixtures. Run it with shell and app staging
directories containing the read-only DEX/native library, as for the probes
above. It tests pipe and PTY leaders plus SIGHUP-ignoring descendants, compares
process start times to avoid mistaking PID reuse for liveness, and requires the
final `PASS` marker. It never stops the live Shizuku service or user sessions.
The same probe can run against the previous launcher as a failure control.

On the Pixel on 2026-09-26, both crash-cleanup cases and the existing native
terminal exit/UTF-8/EOF/cancellation checks passed with the monitor. These are
actual native component checks; full APK/Binder lifecycle validation remains
separate. No performance improvement is claimed from this change.

### Extra keys, voice input, and links

`DRAWER` is an existing extra key that opens the session panel directly. The
`VOICE` key starts the installed Android speech recognition activity and writes
its returned text only to the still-running session that launched it. It never
sends Enter; control characters in a recognition result are removed or replaced
with spaces. The toolbar returns to extra keys after a successful result.
Cancellation, no recognizer, or a changed/exited session leaves the terminal
unchanged. These behaviors still need installed-APK checks; the `VoiceInputText`
unit test only checks the transcription boundary.

With Ctrl held on a physical keyboard or activated in extra keys, tapping a URL
opens it with Android's default URL handler. A one-shot extra-key Ctrl is consumed
when a link opens. Ordinary taps and taps on non-links keep their existing
behavior. While Ctrl is active, visible URLs are underlined, including URLs
wrapped across rows; the underlines disappear on Ctrl release or one-shot
consumption. Check this with the extra key, a physical Ctrl key, and the
volume-down Ctrl shortcut. Verify in a mouse-reporting terminal program that
Ctrl+tap does not also send a click to the program. The installed-APK UI checks
should cover the drawer button, canceled speech, recognizer absence, rotation during recognition,
session changes, and a link while `terminal-onclick-url-open` is disabled.
