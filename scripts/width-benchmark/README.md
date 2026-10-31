# Width classification benchmark

Run from the repository root in native Termux with `javac`, `dx`, and `dalvikvm`:

```sh
scripts/width-benchmark/run.sh dc24a918 \
  "$HOME/.local/state/termux-aether/width-$(date +%Y%m%d-%H%M%S)"
```

This compiles only three small standalone Java classes, not an APK or Gradle
workspace. The baseline is extracted from the named commit and renamed; the
candidate is the working tree's `WcWidth.java`. Both run under Android ART in the
same process. DEX is made read-only before loading, as modern Android requires.
Sources, their hashes, the baseline commit, and raw JSONL results are retained.

The harness first compares all 1,114,112 Unicode code points and four out-of-range
integer boundaries. It then warms both implementations for each workload and
alternates execution order across five pairs of at least 200 ms per variant.
Inputs are deterministic 8192-code-point buffers: printable ASCII, 75% ASCII mixed
with Unicode, and Unicode-only. A volatile checksum consumes each batch result.

Results measure width classification only. They exclude parsing, drawing, PTY
transport, process startup and display presentation. CPU clocks and thermals are
uncontrolled. Do not convert these ratios into terminal frame-rate claims.
