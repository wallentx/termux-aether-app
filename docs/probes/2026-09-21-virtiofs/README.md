# Diskless Virtio-FS host probe

Measured on 2026-09-21 local time / 2026-09-22 UTC. These are diagnostic artifacts,
not an installed feature. See [the feasibility report](../../VIRTIO_FS_FEASIBILITY.md).

## What passed

With a newly created directory under `/storage/emulated/0/Download`, AVF launched
`crosvm_vu_fs`, created its Unix socket and attached it through
`--vhost-user fs,socket=...`. The diskless guest executed the existing kernel.
Both runs (CIDs 2099 and 2101) reached the expected missing-root-device panic and
reset: no root disk or initramfs was supplied. The backend exited successfully
when the VMM disconnected. This is host attachment evidence, **not** a successful
Virtio-FS mount, file-I/O test or benchmark.

## What failed

The same service-owned backend could not resolve a disposable directory inside
Termux private storage. `private-log.txt` records an SELinux directory-search
denial for `u:r:crosvm_vu_fs:s0`, followed by `Failed to canonicalize root_dir`.
AVF waited five seconds for the backend socket and failed VM startup (CID 2100).
A shell-owned `/data/local/tmp` directory also failed, including with readable
and searchable test-directory modes. No existing private-directory permissions
were changed.

Running the extracted firmware crosvm filesystem backend as Termux UID 10445
created a listening socket but failed `unshare(CLONE_NEWNS)` with EPERM before
answering GET_FEATURES. Its flags already included `--disable-sandbox` and
`--skip-pivot-root=true`; those flags do not eliminate its mount-namespace step.
See `termux-backend-probe.json`. The app sandbox and Android SELinux policy were
unchanged.

## Artifacts and interpretation

| File | Meaning |
| --- | --- |
| `VirtioFsProbe.java` | Exact small reflection probe compiled and executed through Shizuku's shell context. It asserts zero disks, limits RAM to 256 MiB and has a 20-second owner watchdog. |
| `external-result.txt`, `external-confirm-result.txt`, `external-log.txt`, `console.txt` | Successful host attachment, repeat run and intentional diskless kernel panic. Console belongs to the repeat run. |
| `private-result.txt`, `private-log.txt` | Private-directory failure and its SELinux evidence. |
| `termux-backend-probe.json` | Separate app-UID backend attempt and crash. The referenced syscall traces remain in local diagnostic storage. |
| `result.json` | Outcome summary and SHA-256 hashes of the other archived artifacts. |

`STATE=6` is the installed interface's dead VM state. `STOP` exceptions occurred
because the guest had already exited or startup had failed. Service logs confirm
VM teardown, and no probe/backend processes remained afterward. The diagnostic
source prints caught exceptions and exits zero; **do not use its process exit
status as a success check**. Read the AVF and guest logs together.

The tested SharedPath constructor uses argument eight (`socket`) as the relative
service socket name, here `fs.sock`; argument nine (`socketPath`) is not the
parcel's socket name. An initial empty socket name was a probe error, corrected
before these archived results.

The probe used local `javac --release 8` and `dx --dex`, with no APK or guest-kernel
build. Firmware extraction and package download happened in private diagnostic
storage; no package was installed. The disposable Downloads directory and transfer
files were removed after copying and verifying the evidence. Existing Arch was
left stopped, its root disk unattached, and its clean shutdown status preserved.

## Next gate

For **Android shared storage**, the host path is established. A future opt-in
implementation can add guest-kernel FUSE/Virtio-FS support and a disposable
initramfs that mounts the share, tests a sentinel file and powers off. Verify
UID/GID mapping and Android shared-storage limitations before exposing the feature.

For **Termux-private projects**, keep SSHFS. A different backend and permitted
transport are still required; the Downloads result does not solve that boundary.
