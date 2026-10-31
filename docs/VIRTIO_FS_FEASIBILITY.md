# Virtio-FS feasibility checkpoint

Investigation on 2026-09-21 (2026-09-22 UTC). The initial read-only inspection was
followed by an authorized disposable, diskless VM probe. This records the V6
storage investigation and subsequent implementation checkpoints. Opt-in Android
shared-storage mounts are now verified; no performance advantage is established.

## Result

The Pixel's AVF backend successfully attached a disposable **Android Downloads
directory** to a diskless VM. The same backend was denied access to a disposable
**Termux-private directory**, with an SELinux directory-search denial in the log.
A guest kernel change alone therefore cannot enable private-home sharing.
Keep the existing `termux-arch-share` SSHFS-over-SSH/vsock implementation available.
Do not promise a Virtio-FS speedup until measured against it and guest-local ext4.

The [prototype source, logs and results](probes/2026-09-21-virtiofs/README.md)
record two successful host attachments, the private-directory failure and a
separate app-UID backend attempt. A later CI-built diskless mount probe also
passed Pixel file I/O; see the implementation checkpoint below.

| Layer | Evidence | Remaining work |
| --- | --- | --- |
| Host backend | Runtime logs show AVF launching `crosvm_vu_fs` and connecting crosvm to it; the later diskless probe passed mount and file I/O. | Benchmark the completed opt-in integration for selected shared-storage directories. |
| Framework | Installed DEX exposes `addSharedPath`, `getSharedPaths` and `VirtualMachineRawConfig.sharedPaths`. | Maintain compatibility checks; the integration uses the installed schema, which differs from current AOSP examples. |
| Permission boundary | Service-owned backend denied private-directory traversal; an extracted app-owned backend failed mount-namespace creation. | Establish a different backend/transport for private directories. Shared-storage success does not grant home access. |
| Guest | The verified kernel and updated guest helpers now mount selected Android shared storage during normal sessions. | Keep private-home sharing on SSHFS; benchmark and extend filesystem-semantics checks separately. |

During the initial host-attachment probe, Arch was stopped, with `clean_shutdown: true` and zero active sessions. The
disposable VMs used its existing kernel read-only and attached **zero disks**.
That initial probe did not boot Arch, change its disks, install a kernel, alter
SELinux, or change screen-lock settings. That stage did not perform a live mount or throughput test.

## Exact-device evidence

Android build fingerprint:

```text
google/kodiak_beta/kodiak:17/CP41.260828.004.A8/16319058:user/release-keys
```

Installed framework:

```text
/apex/com.android.virt/javalib/framework-virtualization.jar
SHA256: 5ad1433efa7bd0f17b598cf29847c5e292a777fc6a82addf3cfef78accfa6e1b
```

DEX inspection found this nine-argument constructor, with no `appDomain` boolean:

```text
VirtualMachineCustomImageConfig.SharedPath(
    String path, int hostUid, int hostGid, int guestUid, int guestGid,
    int mask, String tag, String socket, String socketPath)
```

The installed AIDL `SharedPath` fields are `guestGid`, `guestUid`, `hostGid`,
`hostUid`, `mask`, `sharedPath`, `socketPath` and `tag` (plus `CREATOR`). It has
neither `socketFd` nor `appDomain`. Do not copy code that depends on those fields
without probing for them.

The installed framework also contains a `startCrosvmVirtiofs` helper that constructs
`crosvm device fs` arguments and invokes `ProcessBuilder.start`. Inspection of
`toVsRawConfig` found shared-path parcel conversion, but no call to that launcher.
The existence of the helper therefore does not establish a working launch route.

Direct access to `/apex/com.android.virt/bin/crosvm` and `crosvm_vu_fs` failed from
both Termux (UID 10445) and Shizuku shell (UID 2000). These observations establish
that direct execution is unavailable to those callers; they do not prove that
the AVF service cannot start a backend on their behalf.

Local diagnostic outputs are retained under
`~/.local/state/termux-aether/virtiofs-probe-20260921/`, including the DEX inspection
script, selected framework metadata and method references. Downloaded upstream
sources in that directory are reference material, not the installed firmware.

## Guest and existing integration

The saved guest console identifies `6.18.52-termux-avf`. The API repository's
`guest/arch/build.sh` merges `guest/arch/kernel.config` into ARM64 defconfig. The
overlay has `CONFIG_MODULES=n`; adding only a loadable module will not work.
The final merged config was not available in the staged VM directory, and the
stopped guest was not booted to retrieve it. Absence from the overlay alone does
not establish the final config: the disabled-driver statement also relies on
the existing API virtualization documentation.

The subsequent kernel candidate enabled `CONFIG_FUSE_FS=y` and
`CONFIG_VIRTIO_FS=y`, checking dependencies and the final merged config against
the pinned kernel source. Keep the root disk on virtio-blk; this task concerns
selected directory shares.

At the initial checkpoint, `ArchVmInstance.java` created the raw AVF configuration through the
installed framework and Shizuku-owned service, with no shared paths configured.
That ownership does not automatically grant a filesystem backend permission to
walk Termux's private home directory.

## Upstream context and limits

Current AOSP main has an `appDomain` path that starts the filesystem backend in
the calling app and passes a connected socket FD to AVF. Its Terminal app uses
that path for app-private storage. The installed schema above lacks those fields.
Main's SELinux policy grants the privileged `vmlauncher_app` domain backend
execution rights; that is not evidence that an ordinary fork APK has those rights.
These sources explain the potential permission boundary, but are not an exact
policy dump of this Pixel:

- [AOSP Terminal shared-path configuration](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/java/com/android/virtualization/terminal/ConfigJson.kt)
- [AOSP framework backend launch and FD handoff](https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/src/android/system/virtualmachine/VirtualMachineConfig.java)
- [AOSP VM launcher SELinux policy](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/vmlauncher_app.te)
- [AOSP crosvm SELinux policy](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/crosvm.te)

## Next implementation gate (separate PR)

1. Benchmark the completed opt-in Android shared-storage integration. The
   installed service creates its own backend/socket and passes
   `--vhost-user fs,socket=...` to crosvm. Normal-session mount/unmount and
   suspend/resume are now verified; no speedup is established.
2. Separately prove a permitted backend can serve a selected Termux-private directory. A
   Termux-owned vhost-user-fs backend is a candidate, but this preview's missing
   socket-FD handoff leaves transport and SELinux compatibility unproven. A shared
   external-storage success alone would not prove private-home access.
3. Extend the passing normal-session I/O/lifecycle tests with detailed UID/GID
   mapping, symlink/locking behavior and backend-failure recovery checks.
4. Compare small-file metadata operations and representative project builds with
   SSHFS and guest-local ext4, including CPU cost. Preserve SSHFS as a fallback.

There is not yet enough evidence to require root, declare private sharing
impossible, or claim the custom backend approach will work.

## Mount-probe implementation checkpoint

The API checkout now contains `guest/arch/virtiofs-probe/`: built-in guest-driver
configuration, a static diskless test initramfs, a manual CI workflow, and a
checksum-verified Shizuku device runner. It tests a newly created Android shared
storage folder, not the Arch disk or private home. Commit
`e909c7ead7af1fcd8fdd2a7056f0082e5645c556` passed the kernel build, QEMU mount test
and Pixel mount/read/write/rename/unmount test on 2026-09-22 UTC. The test used a
separate kernel/initramfs and zero disks, then removed its temporary directories.
That probe left Arch unchanged. The subsequent production-kernel upgrade is
recorded below. No performance comparison has been made.

[CI run](https://github.com/wallentx/termux-aether-api/actions/runs/35681060964);
device evidence is recorded in the API checkout under
`docs/validation/virtiofs-2026-09-22/`. The subsequent product integration is recorded below.

## Reversible kernel upgrade checkpoint

API commit `a7ac531` adds `guest/arch/kernel-upgrade/`, which takes the existing
VM owner's POSIX record lock, verifies hashes, saves and syncs the old kernel,
then atomically installs the candidate. The Pixel passed upgrade boot, live-owner
rejection, rollback boot, reinstall boot and clean shutdown. The Arch disk kept
device/inode/size identity; the updater only stats it. The installed kernel now
lists `virtiofs` in `/proc/filesystems`. The kernel upgrade alone did not share a folder.

The helper remains available in private persistent storage for rollback, with
instructions and raw evidence in the API checkout at
`docs/validation/kernel-upgrade-2026-09-22/`. Arch was left cleanly stopped.

## Normal-session shared-storage checkpoint

API `adc3dc2` and API-package `8096892`/`5c831d7` are installed on the Pixel.
`termux-arch-vm --share-enable [ANDROID_FOLDER]` configures a persistent export,
off by default. This device now exports `Download/AetherShared` at `/mnt/android`
with `nodev,nosuid,noexec`. `Æ`/`æ` use it on subsequent boots; default last-session
exit flushes/unmounts it and releases VM resources. `--keep-memory` retains the
mount across suspend/resume. Disabling while stopped preserves shared files.

Device tests passed host/guest writes, rename, host edits after resume, restart
persistence, disable/re-enable, and clean idle shutdown. A status call initially
blocked on balloon statistics while suspended. After approved recovery, the API
was fixed to use cached capability status without polling guest statistics;
ext4 journal recovery and the full regression sequence passed. Evidence is in
the API checkout at `docs/validation/shared-storage-2026-09-22/`.

The API commits are pushed. API-package commits remain local on `dev`. No
private-home access or performance advantage is claimed by these results.
