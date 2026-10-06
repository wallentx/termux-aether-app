# Bundled Shizuku shell

Open Termux after installing/updating the APK. The fork installs `rish` and
`termux-rish` automatically for both fresh and existing package prefixes.
Install and start the separate Shizuku app, then run:

```sh
rish -c 'id'
rish                         # interactive Android shell
rish -c 'uname -r'
```

Approve Termux in Shizuku if prompted. With Shizuku started through ADB,
`id` should report `uid=2000(shell)`. Starting Shizuku again after a reboot
is still required. The APK does not grant itself permission, start a daemon,
change screen-lock settings, or turn ordinary Termux commands into shell commands.

The launcher sets the application's package ID automatically and defaults
`RISH_PRESERVE_ENV=0`. This keeps Termux's private PATH/preload libraries out of
the remote Android shell. An explicit `RISH_PRESERVE_ENV` value is respected.
Arguments and interactive terminal handling are delegated to the upstream
rish client. See the [upstream rish documentation](
https://github.com/RikkaApps/Shizuku-API/tree/master/rish).

The installed Shizuku 13.6.0 client returned status 0 for `rish -c 'exit 37'`
in the Pixel's noninteractive test, and separate stdout/stderr capture was not
reliable. Do not use its exit status or stream separation as the sole success
check in automation. Check the expected output/result file. This bundle does
not patch the client implementation loaded from the Shizuku app.

| Path | Purpose |
| --- | --- |
| `$PREFIX/bin/rish` | Convenient command; existing files/symlinks are preserved |
| `$PREFIX/bin/termux-rish` | Fork command; existing files/symlinks are preserved |
| `$PREFIX/libexec/termux-rish/` | APK-managed launcher, loader, license and provenance |

If a previous installation already owns `rish`, use `termux-rish` instead.
The managed directory is refreshed on startup; customize a separate wrapper,
not files inside that directory. The loader is installed read-only before it is
published, as required by Android 14+. Updates replace files by rename so a
running process retains its existing inode. Installation errors are logged and
do not reset the package prefix or prevent a normal Termux session.

The 59,672-byte loader is unmodified from the official Shizuku v13.6.0 release.
Its Apache-2.0 license, release URL and SHA-256 hashes are included beside it
in the APK and installed directory. The remainder of rish is loaded from the
installed Shizuku app; this does not bundle a second Shizuku daemon.

## Native Linux programs

`rish` supplies Android shell privileges; it does not translate glibc to Bionic.
A Linux/AArch64 program still needs a compatible glibc runtime and a directory
the shell UID can access. Termux's private home is not that directory.

On the Pixel, Geekbench 7's `--sysinfo` completed successfully using the glibc
loader and program files staged under `/data/local/tmp/termux-geekbench7-native`:

```sh
rish -c 'cd /data/local/tmp/termux-geekbench7-native &&
  exec ./ld-linux-aarch64.so.1 --library-path . ./geekbench_aarch64 --sysinfo'
```

This is a device-specific diagnostic example, not an installed Geekbench command.
It uses Android's host kernel without AVF or PRoot. A complete benchmark and
result upload remain separate validation steps. Geekbench and glibc are not
redistributed in this APK.
