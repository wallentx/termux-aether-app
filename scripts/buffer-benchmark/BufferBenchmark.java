package com.termux.terminal;

import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;

/** Runs unchanged against old and new emulator jars; no production benchmark code. */
public final class BufferBenchmark {
    private static volatile int sink;

    private static final class Work {
        final String name;
        final int kind, columns, rows;
        final boolean unicode;
        Work(String name, int kind, int columns, int rows, boolean unicode) {
            this.name = name; this.kind = kind; this.columns = columns;
            this.rows = rows; this.unicode = unicode;
        }
        TerminalBuffer create() {
            TerminalBuffer buffer = new TerminalBuffer(columns, rows * 2, rows);
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < columns; x++)
                    buffer.setChar(x, y, '!' + (x + y) % 90, TextStyle.NORMAL);
                if (unicode) {
                    buffer.setChar(4, y, 0x754c, TextStyle.NORMAL);
                    buffer.setChar(8, y, 0x0301, TextStyle.NORMAL);
                    buffer.setChar(12, y, 0x1f600, TextStyle.NORMAL);
                }
                buffer.setLineWrap(y);
            }
            return buffer;
        }
        void run(TerminalBuffer b) {
            switch (kind) {
                case 0: b.blockSet(0, 0, columns, rows, ' ', TextStyle.NORMAL); break;
                case 1: b.blockSet(3, 0, columns / 2, rows, 'x', TextStyle.NORMAL); break;
                case 2: b.blockSet(3, 0, 1, 1, ' ', TextStyle.NORMAL); break;
                case 3: b.blockCopy(0, 0, columns, 1, 0, 1); break;
                case 4: b.blockCopy(1, 0, columns - 1, 1, 0, 0); break;
                case 5: b.blockCopy(0, 0, columns - 1, 1, 1, 0); break;
                case 6: b.blockCopy(0, 1, columns, rows - 1, 0, 0); break;
                default: throw new AssertionError(kind);
            }
        }
    }

    private static long fingerprint(TerminalBuffer b) {
        long hash = 1;
        for (int y = 0; y < b.mScreenRows; y++) {
            TerminalRow row = b.mLines[b.externalToInternalRow(y)];
            for (int i = 0; i < row.getSpaceUsed(); i++) hash = hash * 31 + row.mText[i];
            for (long style : row.mStyle) hash = hash * 31 + style;
            hash = hash * 31 + (row.mLineWrap ? 1 : 0);
        }
        return hash;
    }

    private static double sample(Work work, TerminalBuffer buffer) {
        long start = System.nanoTime(), elapsed;
        int operations = 0;
        do {
            for (int i = 0; i < 64; i++) work.run(buffer);
            sink ^= buffer.mLines[buffer.externalToInternalRow(0)].mText[0];
            operations += 64;
            elapsed = System.nanoTime() - start;
        } while (elapsed < 150_000_000L);
        return elapsed / (1000.0 * operations);
    }

    public static void main(String[] args) throws Exception {
        Work[] workloads = {
            new Work("clear_80x24", 0, 80, 24, false),
            new Work("clear_160x48", 0, 160, 48, false),
            new Work("partial_fill_160x48", 1, 160, 48, false),
            new Work("single_cell", 2, 160, 48, false),
            new Work("copy_row_160", 3, 160, 48, false),
            new Work("overlap_left_160", 4, 160, 48, false),
            new Work("overlap_right_160", 5, 160, 48, false),
            new Work("copy_scroll_region_160x48", 6, 160, 48, false),
            new Work("unicode_clear_80x24", 0, 80, 24, true),
            new Work("unicode_copy_row_80", 3, 80, 24, true)
        };
        JSONObject report = new JSONObject().put("sdk", Build.VERSION.SDK_INT)
            .put("device", Build.MODEL).put("fingerprint", Build.FINGERPRINT)
            .put("label", args.length == 0 ? "unspecified" : args[0])
            .put("samples", 5).put("minimum_sample_ms", 150)
            .put("unit", "microseconds per operation")
            .put("scope", "Existing blockSet/blockCopy APIs; excludes buffer creation, PTY, parsing and rendering")
            .put("limitations", "Unpinned CPU and uncontrolled clocks; Unicode clear timing uses previously complex rows after clearing; no instruction-level SIMD claim");
        JSONArray results = new JSONArray();
        for (Work work : workloads) {
            TerminalBuffer check = work.create();
            work.run(check);
            long expectedFingerprint = fingerprint(check);
            TerminalBuffer buffer = work.create();
            sample(work, buffer); // Warm the same code and buffers before timing.
            double[] times = new double[5];
            for (int i = 0; i < times.length; i++) times[i] = sample(work, buffer);
            double[] sorted = times.clone(); Arrays.sort(sorted);
            results.put(new JSONObject().put("name", work.name)
                .put("one_operation_fingerprint", Long.toHexString(expectedFingerprint))
                .put("microseconds", new JSONArray(times)).put("median_us", sorted[2]));
        }
        System.out.println(report.put("results", results).toString(2));
    }
}
