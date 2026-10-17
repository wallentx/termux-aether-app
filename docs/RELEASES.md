# Coordinated Aether releases

## Install with Pacman

Download **`termux-aether-suite-aarch64.pkg.tar.xz`** and its `.sha256` file from
[the app release](https://github.com/wallentx/termux-aether-app/releases/latest).
The `.pkg` in the filename distinguishes the real Pacman package from the old
extract-and-run bundle, `termux-aether-suite-aarch64.tar.xz`.

```sh
sha256sum -c termux-aether-suite-aarch64.pkg.tar.xz.sha256
pacman -U ./termux-aether-suite-aarch64.pkg.tar.xz
```

On a new device, install the main app APK and finish its Pacman bootstrap first.
The package targets native aarch64 Termux with the `com.termux` Pacman prefix.
Pacman resolves missing Bash, Python, OpenSSH, util-linux and termux-am dependencies
from configured repositories; use `pacman -Syu` first if those repositories are stale.
It does not convert an APT environment.

The suite directly owns the native exec libraries, API commands and updater.
Its `provides`, `conflict` and `replaces` metadata covers both upstream and Aether
exec/API packages, so Pacman transfers file ownership in one transaction. Use
this suite or the standalone native packages, not both. No package script starts
a nested Pacman transaction. XZ is decoded by Termux's archive library without
spawning an external decompressor during replacement of the preload library.

The package also places the two APKs and matching glibc sources under
`$PREFIX/share/termux-aether-suite`. Android still confirms APK updates:

```sh
aether-apks api
# Finish Android's confirmation, then update the terminal last:
aether-apks app
```

Finish active terminal jobs and shut Arch down cleanly before updating the app
APKs. Updating a running app can close its sessions.

## Migrating manually installed commands

Older installations may have nine unowned Aether wrappers in `$PREFIX/bin`:
`termux-arch`, `termux-arch-resources`, `termux-arch-share`, `termux-arch-vm`,
`termux-capabilities`, `termux-shizuku`, `termux-virtualization`, `Æ` and `æ`.
Package replacement metadata cannot take ownership of an unowned file silently.

Download `aether-install.py` and its `.sha256` from the same release, beside the
new Pacman package and checksum. Verify the helper, then run the one-time migration:

```sh
sha256sum -c aether-install.py.sha256
python aether-install.py --adopt-legacy ./termux-aether-suite-aarch64.pkg.tar.xz
```

Add `--check` to inspect without moving or installing anything. The helper checks
package integrity and current ownership, saves affected files and the package list
to `~/.local/state/termux-aether/upgrade-*`, and moves aside only the nine known
unowned commands. Unknown files and files owned by unrelated packages are refused.
If installation fails, absent legacy paths are restored; newly installed files
are never overwritten during recovery. The retained backup is a recovery aid,
not a complete user-data backup or automatic package rollback.

An external preload copy keeps subprocess execution available throughout this
transaction. No `--overwrite '*'` is needed. Do not edit the old `install.sh`:
its checksum intentionally fails after edits. Use this supported migration path.

## Subsequent upgrades

Run `aether-update` to download the latest suite and perform a protected native
upgrade, or use `pacman -U` on the new package directly. `aether-update --check`
validates without installation; `--adopt-legacy` is also available for migration.
After the native upgrade, use `aether-apks api` and `aether-apks app` when new APKs
are supplied. Installing an APK alone does not upgrade native Pacman packages.

Shizuku, its authorization, Arch guest images and rclone sharing remain optional.
The suite does not initialize, replace or delete guest disks, home directories,
credentials, app settings or package-managed glibc installations.

## Versions and upgrade identity

| Component | Release baseline |
| --- | --- |
| Android app and API companion | Version `1000.0.0`, versionCode `1000000000` |
| Pacman suite | `termux-aether-suite 1:1000.0.0-2` |
| Native API commands | `termux-aether-api 1:1000.0.0-2`, provided by the suite |
| Native execution | `termux-aether-exec 1:1000.0.0-1`, provided by the suite |

Packaging revision 2 reuses the unchanged APKs and exec library from v1000.0.0.
It changes the distribution format, updater and migration handling. It does not
needlessly bump the Android versionCode or rebuild those APKs. Future changed
APKs must increment the code. High versions reserve space above ordinary upstream
versions; no finite number guarantees upstream can never overtake them.

Android package IDs (`com.termux` and `com.termux.api`), shared UID and signing key
remain unchanged. Matching signatures support updates in place. If Android reports
a signature conflict, stop rather than uninstalling or clearing data to bypass it.
Back up irreplaceable data before upgrading. These builds retain the public Termux
debug test key; download only from the fork releases.

## Release inputs

Keep `master` untouched for upstream syncs; publish fork changes from `dev`.
Build standalone native packages using their documented native packaging scripts.
`scripts/suite/package.py` merges their payloads into one tracked package and records
the package versions and suite source commit alongside the original APK commits.
Provide verified APKs, matching glibc sources, the native packages and `release.json`
as inputs. Build after committing; publish package-only source tags for packaging
revisions rather than moving existing release tags.

The builder publishes `termux-aether-suite-aarch64.pkg.tar.xz` and its checksum.
Also publish the matching `aether-install.py` and checksum; `aether-update` verifies
both before executing the installer. Keep the old v1000.0.0 extract-and-run bundle
unchanged for historical reproducibility; it is superseded by the `.pkg.tar.xz` asset.

Run `python scripts/suite/validate-transaction.py PACKAGE` on native Termux to
exercise a real Pacman replacement inside a temporary root/database, with the
preload library itself included in the replacement. This test disables scriptlets
in that isolated root and verifies file ownership, dependencies and file presence.
It does not install into the live prefix. `strace` is required for its decompressor check.
