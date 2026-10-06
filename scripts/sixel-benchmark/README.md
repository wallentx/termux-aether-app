# Sixel row-write benchmark

The normal Pixel APK CI build also emits a `sixel-benchmark` dex-jar artifact,
reusing its compiled emulator classes. It adds no APK or duplicate app build.
No benchmark or reference writer is included in the production app.

On the Pixel, extract the artifact into a private Termux directory, then run:

```sh
sha256sum -c SHA256SUMS
CLASSPATH="$PWD/sixel-benchmark.jar" /system/bin/app_process / com.termux.terminal.SixelBenchmark > result.json
```

Run from native Termux, not adb shell or PRoot. Allow approximately 15-30 seconds.
The jar runs Android's real Bitmap implementation with the current decoder and
a reference subclass retaining the former per-pixel writer. It checks every
bitmap pixel, dimensions and cursor state before timing. Inputs cover noisy
single columns, repeats of 2/16/1024/8192, transparent masks, color changes,
overpainting and bitmap growth. Each implementation is warmed, followed by five
alternating samples of at least 150 ms. Reports include all samples and medians.

Timing includes bitmap allocation, color selection and `readData`, but not the
outer terminal escape parser, PTY transport, Canvas rendering or GPU work. It is
not an end-to-end frame-rate measurement. CPU affinity, clocks and thermals are
uncontrolled; repeat a result if short-run noise affects a conclusion. The change
uses bulk Java/Bitmap operations, not hand-written NEON/SVE instructions. A
speedup does not establish which instructions ART or Android's graphics libraries
selected. Preserve the artifact commit and JSON alongside any measured claim.

Robolectric regression tests in `app/src/test/java/com/termux/terminal/TerminalSixelTest.java`
also check all 64 masks, maximum repeats, unchanged neighbors, cached-color
invalidation, transparent overpaint, resizing and invalid requests against
independent expected pixels. Run them in CI, not on the development phone.

## Full-size bitmap copy comparison

The same JAR also contains `com.termux.terminal.BitmapCopyBenchmark`. It compares
the prior full-image pixel array, default Canvas, unfiltered SRC Canvas, and
128 KiB pixel strips at small, 960x600-class and 1920x1080-class sizes.
Each method must match the reference pixels (including partial alpha and density)
before five alternating-order samples of at least 200 ms each. This is a resize
microbenchmark, not proof of an end-to-end frame-time improvement.
