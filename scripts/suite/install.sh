#!/data/data/com.termux/files/usr/bin/sh
# Run inside native Termux. Android APK installation remains user-confirmed.
set -eu
case "${1-}" in ''|--check) ;; *) echo 'Usage: sh install.sh [--check]' >&2; exit 2;; esac
if [ "${1-}" = --check ]; then AETHER_SETUP_CHECK_ONLY=1; else AETHER_SETUP_CHECK_ONLY=0; fi
prefix=/data/data/com.termux/files/usr
[ "${PREFIX-}" = "$prefix" ] || { echo 'Run this inside Termux-Aether (com.termux).' >&2; exit 1; }
[ "$(uname -m)" = aarch64 ] || { echo 'This bundle requires aarch64.' >&2; exit 1; }
command -v pacman >/dev/null || { echo 'This bundle requires the Pacman prefix. Do not replace an APT prefix.' >&2; exit 1; }
cd -- "$(dirname -- "$0")"
sha256sum --check SHA256SUMS
set -- ./termux-aether-exec-*.pkg.tar.zst ./termux-aether-api-*.pkg.tar.xz
for package do
    [ -f "$package" ] || { echo "Missing package: $package" >&2; exit 1; }
    pacman -Qip "$package"
done
# CHECK_ONLY is set by the argument before positional parameters are replaced.
if [ "${AETHER_SETUP_CHECK_ONLY-0}" = 1 ]; then exit 0; fi
umask 077
state="$HOME/.local/state/termux-aether"
mkdir -p "$state"
recovery=$(mktemp -d "$state/upgrade-XXXXXXXX")
# Never preload a file that Pacman can remove or replace during the transaction.
cp recovery-preload.so "$recovery/preload.so"
chmod 700 "$recovery/preload.so"
export LD_PRELOAD="$recovery/preload.so"
pacman -Q > "$recovery/packages-before.txt"
# Preserve existing command/library files, including locally modified versions.
for package do tar -tf "$package"; done | while IFS= read -r path; do
    case "$path" in
        data/data/com.termux/files/usr/*)
            case "$path" in */) continue;; esac
            if [ -f "/$path" ] || [ -L "/$path" ]; then printf '%s\n' "${path#data/data/com.termux/files/usr/}"; fi;;
    esac
done | sort -u > "$recovery/files-before.txt"
tar -cf "$recovery/files-before.tar" -C "$prefix" -T "$recovery/files-before.txt"
printf 'Recovery files: %s\n' "$recovery"
# Let Pacman check dependencies and ownership. Never force-overwrite user files.
if ! pacman -U --needed "$@"; then
    printf 'Package installation stopped. Recovery files remain at %s\n' "$recovery" >&2
    printf 'Do not uninstall the apps or delete their data. Resolve the reported package conflict and retry.\n' >&2
    exit 1
fi
printf '\nNative packages installed. Keep this bundle until both APK updates finish.\n'
printf 'Install the API APK first, then the app APK last (updating Termux may close sessions):\n'
printf '  termux-open "%s/termux-aether-api.apk"\n' "$PWD"
printf '  termux-open "%s/termux-aether-app.apk"\n' "$PWD"
printf 'Android must offer Update. If it reports a signature conflict, stop; do not uninstall.\n'
