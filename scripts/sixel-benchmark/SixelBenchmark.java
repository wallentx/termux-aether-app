package com.termux.terminal;

import android.graphics.Bitmap;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;

/** Real Android Bitmap benchmark; run the CI-built dex jar through Termux app_process. */
public final class SixelBenchmark {
    private static volatile int sink;

    private static final class PixelWriter extends TerminalSixel {
        PixelWriter(Bitmap bitmap) { super(null, bitmap); }
        @Override void paintRun(int bits, int repeat) {
            for (int column = 0; column < repeat; column++) {
                for (int row = 0; row < SIXEL__LINE_LEN; row++) {
                    if ((bits & (1 << row)) != 0)
                        mBitmap.setPixel(mCurX + column, mCurY + row, mColor);
                }
            }
        }
    }

    private static final class Workload {
        final String name;
        final int width, height, repeat;
        final boolean resize, overpaint;
        Workload(String name, int width, int height, int repeat, boolean resize, boolean overpaint) {
            this.name = name; this.width = width; this.height = height; this.repeat = repeat;
            this.resize = resize; this.overpaint = overpaint;
        }
    }

    private static TerminalSixel render(Workload work, boolean batched) {
        Bitmap bitmap = Bitmap.createBitmap(work.resize ? 1 : work.width,
                work.resize ? 1 : work.height, Bitmap.Config.ARGB_8888);
        TerminalSixel sixel = batched ? new TerminalSixel(null, bitmap) : new PixelWriter(bitmap);
        try {
            int sequence = 0;
            for (int y = 0; y < work.height; y += 6) {
                for (int pass = 0; pass < (work.overpaint ? 2 : 1); pass++) {
                    if (pass != 0) require(sixel.readData('$', 1));
                    for (int x = 0; x < work.width; x += work.repeat) {
                        sixel.setColor((sequence / 7) % 16);
                        // Includes every mask, transparent columns, and repeated colors.
                        int mask = (sequence++ * 37 + 63) & 63;
                        require(sixel.readData('?' + mask, Math.min(work.repeat, work.width - x)));
                    }
                }
                if (y + 6 < work.height) require(sixel.readData('-', 1));
            }
            return sixel;
        } catch (RuntimeException | Error error) {
            if (sixel.getBitmap() != null) sixel.getBitmap().recycle();
            throw error;
        }
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("Sixel operation failed");
    }

    private static int[] pixels(TerminalSixel sixel) {
        Bitmap bitmap = sixel.getBitmap();
        int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return pixels;
    }

    private static int verify(Workload work) {
        TerminalSixel reference = render(work, false), candidate = render(work, true);
        try {
            require(reference.getWidth() == candidate.getWidth());
            require(reference.getHeight() == candidate.getHeight());
            require(reference.getCurX() == candidate.getCurX());
            require(reference.getCurY() == candidate.getCurY());
            require(reference.getBitmap().getWidth() == candidate.getBitmap().getWidth());
            require(reference.getBitmap().getHeight() == candidate.getBitmap().getHeight());
            int[] actual = pixels(candidate);
            require(Arrays.equals(pixels(reference), actual));
            return Arrays.hashCode(actual);
        } finally {
            reference.getBitmap().recycle(); candidate.getBitmap().recycle();
        }
    }

    private static double sample(Workload work, boolean batched) {
        long start = System.nanoTime(), elapsed;
        int frames = 0;
        do {
            TerminalSixel image = render(work, batched);
            sink ^= image.getWidth();
            image.getBitmap().recycle();
            frames++;
            elapsed = System.nanoTime() - start;
        } while (elapsed < 150_000_000L);
        return elapsed / (1_000_000.0 * frames);
    }

    private static double median(double[] values) {
        double[] copy = values.clone(); Arrays.sort(copy); return copy[copy.length / 2];
    }

    public static void main(String[] args) throws Exception {
        Workload[] workloads = {
            new Workload("single_columns", 1024, 96, 1, false, false),
            new Workload("repeat_2", 1024, 96, 2, false, false),
            new Workload("repeat_16", 1024, 96, 16, false, false),
            new Workload("repeat_1024", 1024, 96, 1024, false, false),
            new Workload("repeat_8192", 8192, 24, 8192, false, false),
            new Workload("overpaint", 1024, 96, 64, false, true),
            new Workload("resize", 1024, 96, 128, true, false)
        };
        JSONObject report = new JSONObject().put("sdk", Build.VERSION.SDK_INT)
                .put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
                .put("samples_per_variant", 5).put("minimum_sample_ms", 150)
                .put("scope", "Bitmap allocation and readData decoding; excludes escape parsing, UI rendering and PTY transport")
                .put("baseline", "Former per-pixel writer with the same bounds/cursor handling")
                .put("limitations", "Unpinned CPU, uncontrolled thermals; alternating order; no explicit SIMD instructions added");
        JSONArray results = new JSONArray();
        for (Workload work : workloads) {
            int checksum = verify(work);
            // Warm both implementations, including the Bitmap/ART paths, before timing.
            sample(work, false); sample(work, true);
            double[] before = new double[5], after = new double[5];
            for (int i = 0; i < 5; i++) {
                if ((i & 1) == 0) { before[i] = sample(work, false); after[i] = sample(work, true); }
                else { after[i] = sample(work, true); before[i] = sample(work, false); }
            }
            results.put(new JSONObject().put("name", work.name).put("pixels_identical", true)
                    .put("pixel_checksum", checksum).put("per_pixel_ms", new JSONArray(before))
                    .put("batched_ms", new JSONArray(after)).put("per_pixel_median_ms", median(before))
                    .put("batched_median_ms", median(after)).put("speedup", median(before) / median(after)));
        }
        report.put("results", results).put("correctness", "PASS");
        System.out.println(report.toString(2));
    }
}
