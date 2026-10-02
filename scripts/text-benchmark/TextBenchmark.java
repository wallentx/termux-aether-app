package com.termux.terminal;

import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Same append workload for baseline and candidate, running on Android ART. */
public final class TextBenchmark {
    private static volatile int sink;
    private static final TerminalOutput OUTPUT = new TerminalOutput() {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    };
    private static final class Work {
        final String name, setup;
        final byte[][] chunks;
        final int bytes;
        Work(String name, String setup, String unit, int repeats, int chunkSize) {
            this.name = name; this.setup = setup;
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < repeats; i++) text.append(unit);
            byte[] payload = text.toString().getBytes(StandardCharsets.UTF_8);
            bytes = payload.length;
            chunks = new byte[(bytes + chunkSize - 1) / chunkSize][];
            for (int i = 0; i < chunks.length; i++)
                chunks[i] = Arrays.copyOfRange(payload, i * chunkSize, Math.min(bytes, (i + 1) * chunkSize));
        }
        TerminalEmulator create() {
            TerminalEmulator t = new TerminalEmulator(OUTPUT, 120, 40, 10, 20, 200, null);
            byte[] init = setup.getBytes(StandardCharsets.UTF_8);
            t.append(init, init.length);
            return t;
        }
        void run(TerminalEmulator t) { for (byte[] chunk : chunks) t.append(chunk, chunk.length); }
    }
    private static long fingerprint(TerminalEmulator t) {
        TerminalBuffer b = t.getScreen();
        long hash = t.getCursorCol() * 31L + t.getCursorRow();
        for (int y = -b.getActiveTranscriptRows(); y < b.mScreenRows; y++) {
            TerminalRow row = b.mLines[b.externalToInternalRow(y)];
            if (row == null) { hash *= 31; continue; }
            for (int i = 0; i < row.getSpaceUsed(); i++) hash = hash * 31 + row.mText[i];
            for (long style : row.mStyle) hash = hash * 31 + style;
            hash = hash * 31 + (row.mLineWrap ? 1 : 0);
        }
        return hash;
    }
    private static double sample(Work w, TerminalEmulator t) {
        long start = System.nanoTime(), elapsed;
        int batches = 0;
        do {
            w.run(t); sink ^= t.getCursorCol(); batches++;
            elapsed = System.nanoTime() - start;
        } while (elapsed < 150_000_000L);
        return elapsed / (1_000_000.0 * batches);
    }
    public static void main(String[] args) throws Exception {
        String ascii = "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789 ";
        Work[] workloads = {
            new Work("ascii_64k", "", ascii, 1024, 65536),
            new Work("ascii_4k_chunks", "", ascii, 1024, 4096),
            new Work("log_lines", "", "INFO worker=3 build completed successfully: 0123456789 abcdefghijklmnop\r\n", 512, 4096),
            new Work("colored_lines", "", "\033[32mINFO\033[0m worker build completed successfully: abcdefghijklmnop\r\n", 512, 4096),
            new Work("mixed_unicode", "", "ASCII text cafe\u0301 \u754c \ud83d\ude42 more printable text 0123456789\r\n", 128, 4096),
            new Work("single_byte_input", "", ascii, 32, 1),
            new Work("insert_mode", "\033[4h", "\rabcdefghij", 128, 4096),
            new Work("nowrap", "\033[?7l", ascii, 128, 4096),
            new Work("line_drawing", "\033(0", "lqqqqqqqqqqk\r\nx          x\r\nmqqqqqqqqqqj\r\n", 128, 4096)
        };
        JSONArray results = new JSONArray();
        for (Work w : workloads) {
            TerminalEmulator check = w.create(); w.run(check);
            TerminalEmulator t = w.create(); sample(w, t);
            double[] times = new double[5];
            for (int i = 0; i < times.length; i++) times[i] = sample(w, t);
            double[] sorted = times.clone(); Arrays.sort(sorted);
            results.put(new JSONObject().put("name", w.name).put("bytes_per_batch", w.bytes)
                .put("one_batch_fingerprint", Long.toHexString(fingerprint(check)))
                .put("milliseconds", new JSONArray(times)).put("median_ms", sorted[2])
                .put("mib_per_second", w.bytes / (sorted[2] * 1048.576)));
        }
        System.out.println(new JSONObject().put("sdk", Build.VERSION.SDK_INT)
            .put("device", Build.MODEL).put("label", args.length == 0 ? "unspecified" : args[0])
            .put("samples", 5).put("minimum_sample_ms", 150)
            .put("scope", "append decoding, buffer writes and scrolling; excludes allocation/setup, PTY and rendering")
            .put("limitations", "Unpinned CPU and uncontrolled clocks; no instruction-level SIMD claim")
            .put("results", results).toString(2));
    }
}
