# Installed-app rendering measurement

No build required. `workload.py` runs in a foreground Termux terminal; `capture.py`
runs on an ADB-connected host using Python 3. The installed APK must allow
`run-as com.termux` (the fork's debug APK does).

1. Copy `workload.py` into the Pixel's Termux home.
2. On the host, start the collector with fresh output and remote directories:
   ```sh
   python scripts/render-benchmark/capture.py --serial IP:PORT \
     --remote-dir /data/data/com.termux/files/home/render-run-1 \
     --output "$HOME/reports/render-run-1"
   ```
3. Within 120 seconds, run in the Pixel's visible Termux terminal:
   ```sh
   python ~/workload.py ~/render-run-1
   ```

Default workload: three 20-second phases paced at 60 updates/second: colored text
scrolling, redraws of a pre-existing 960x600 sixel image, and replacement of that
image without declared raster dimensions (includes bitmap growth/decoding).
Use `--seconds 5` for a shorter probe; `--fps 30` reduces load. Keep the window,
font size, keyboard, other apps, and power conditions consistent between runs.

The alternate screen protects existing terminal contents and is restored on normal
exit or Ctrl-C. Each run requires a new remote directory to prevent stale signals.
The collector does not install APKs, change device settings, or stop applications.

`summary.json` includes sampled frame, UI traversal, draw, and GPU-completion
interval percentiles. Draw time covers the activity's draw stage, not exclusively
TerminalRenderer. Raw `gfxinfo` retains Android's aggregate frame/deadline counters;
its histogram is bucketed, whereas percentiles in the summary use frame timestamps.
Frame samples are collected repeatedly to avoid losing the circular buffer. They
exclude flagged frames and may differ slightly in count from aggregate counters.
GPU-completion intervals are wall-clock intervals, not isolated GPU execution time.
`workload.json` records terminal size, producer updates, and late producer deadlines.
Produced updates are not guaranteed to become distinct displayed frames.
Any phase with zero sampled frames rejects the run without writing `summary.json`;
raw evidence remains available. This catches fully hidden phases, but does not
prove continuous visibility: keep Termux in front throughout the measurement.

Memory snapshots are process PSS/heap snapshots, not peak-memory or allocation
profiles. Thermal state is saved after each phase. Wireless ADB, background apps,
JIT compilation, refresh rate and CPU/GPU frequency add noise: repeat comparisons
and do not interpret these numbers as a universal FPS or SIMD speedup.

On older builds, rapid image replacement can retain gigabytes until the periodic
image sweep. Start with a shorter run when testing such builds.

## On-device collection through Shizuku

An authorized bundled `rish` can collect on the same phone, without wireless ADB.
Run from this repository in a visible Termux session, using new directories:

```sh
run="$HOME/.local/state/termux-aether/render-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$run"
python scripts/render-benchmark/capture.py --local-rish \
  --remote-dir "$run/workload" --output "$run/report" > "$run/collector.log" 2>&1 &
python scripts/render-benchmark/workload.py "$run/workload" --seconds 10
wait
```

Local collection reads handshake files directly as Termux. Shell output is staged
in a unique temporary Downloads directory and removed after each command, because
rish pipe output was incomplete on the tested preview. This needs Android shared
storage access. The collector does not change screen-lock or display settings.
It checks focus, unlocked state and rotation between samples; the workload rejects
terminal-size changes. Checks are sampled, not proof against a brief switch away
between checks. Keep the phone visible and untouched. A failed collector sends an
abort marker so the workload restores its terminal state promptly.

`partial-summary.json` retains completed phases if a later phase fails;
`summary.json` is written only when all requested phases pass. Sampled deadline
misses compare each frame's completion timestamp with its own `FrameDeadline`,
not a fixed 60 Hz budget. They may differ from Android's aggregate jank counters.
The observed frame intervals are included to expose refresh-rate changes.

Optional `--input-phase` on **both** commands injects `a` keys while scrolling.
Do not type or switch sessions during it. The metric is command dispatch to PTY
receipt, including Shizuku and Android input-command startup; it is **not physical
keyboard latency or input-to-display latency**. A count mismatch rejects the run.
This option is unavailable on the tested September 22 Pixel firmware: Android's
input shell service returns `Failed transaction (2147483646)`. A preflight now
rejects that condition before the workload measurement begins. Input-to-display
latency still needs a working event injector and presentation tracing.
