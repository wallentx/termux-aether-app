package com.termux.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Random;

/** Compare resize copies at the image sizes used by the installed-app frame test. */
public final class BitmapCopyBenchmark {
    private static final Paint COPY_PAINT = new Paint();
    static {
        COPY_PAINT.setFilterBitmap(false);
        COPY_PAINT.setDither(false);
        COPY_PAINT.setAntiAlias(false);
        COPY_PAINT.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
    }
    private static volatile int sink;
    private static final String[] NAMES = {"pixel_array", "canvas_default", "canvas_src", "pixel_strips"};
    private static Bitmap copy(Bitmap source, int width, int height, int method) {
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int w = Math.min(width, source.getWidth()), h = Math.min(height, source.getHeight());
        if (method == 0) {
            int[] pixels = new int[source.getWidth() * source.getHeight()];
            source.getPixels(pixels, 0, source.getWidth(), 0, 0, source.getWidth(), source.getHeight());
            out.setPixels(pixels, 0, source.getWidth(), 0, 0, w, h);
        } else if (method == 3) {
            int rows = Math.min(h, Math.max(1, 32768 / w));
            int[] pixels = new int[w * rows];
            for (int y = 0; y < h; y += rows) {
                int count = Math.min(rows, h - y);
                source.getPixels(pixels, 0, w, 0, y, w, count);
                out.setPixels(pixels, 0, w, 0, y, w, count);
            }
        } else {
            Rect rect = new Rect(0, 0, w, h);
            new Canvas(out).drawBitmap(source, rect, rect, method == 1 ? null : COPY_PAINT);
        }
        return out;
    }
    private static double sample(Bitmap source, int width, int height, int method) {
        long start = System.nanoTime(), elapsed;
        int count = 0;
        do {
            Bitmap out = copy(source, width, height, method);
            sink ^= out.getWidth();
            out.recycle();
            count++;
            elapsed = System.nanoTime() - start;
        } while (elapsed < 200_000_000L);
        return elapsed / (1_000_000.0 * count);
    }
    public static void main(String[] args) throws Exception {
        JSONArray results = new JSONArray();
        for (int[] shape : new int[][]{{1024, 96, 1124, 196}, {1060, 508, 1060, 610}, {1060, 610, 972, 612}, {1920, 1080, 1920, 1180}}) {
            Bitmap source = Bitmap.createBitmap(shape[0], shape[1], Bitmap.Config.ARGB_8888);
            int[] pixels = new int[shape[0] * shape[1]];
            Random random = new Random(42);
            for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
            source.setPixels(pixels, 0, shape[0], 0, 0, shape[0], shape[1]);
            source.setDensity(640);
            Bitmap reference = copy(source, shape[2], shape[3], 0);
            for (int method = 0; method < 4; method++) {
                Bitmap actual = copy(source, shape[2], shape[3], method);
                if (!reference.sameAs(actual)) throw new AssertionError("Pixels differ: " + NAMES[method]);
                actual.recycle(); sample(source, shape[2], shape[3], method);
            }
            reference.recycle();
            double[][] samples = new double[4][5];
            // Alternate order to reduce clock/temperature bias.
            for (int round = 0; round < 5; round++) {
                for (int n = 0; n < 4; n++) {
                    int method = (round % 2 == 0) ? n : 3 - n;
                    samples[method][round] = sample(source, shape[2], shape[3], method);
                }
            }
            JSONArray methods = new JSONArray();
            for (int method = 0; method < 4; method++) {
                double[] sorted = samples[method].clone(); Arrays.sort(sorted);
                methods.put(new JSONObject().put("name", NAMES[method]).put("milliseconds",new JSONArray(samples[method])).put("median_ms",sorted[2]));
            }
            results.put(new JSONObject().put("source_width",shape[0]).put("source_height",shape[1]).put("dest_width",shape[2]).put("dest_height",shape[3]).put("pixels_identical",true).put("methods",methods));
            source.recycle();
        }
        System.out.println(new JSONObject().put("scope","Resize allocation and copy; excludes terminal parsing and UI drawing").put("results",results).toString(2));
    }
}
