import com.termux.terminal.WcWidth;
import com.termux.terminal.WcWidthBaseline;

/** Isolated width classification; does not measure terminal frames or rendering. */
public final class WidthBenchmark {
    private static volatile long sink;

    private static long scan(int[] input, boolean candidate) {
        long sum = 0;
        if (candidate) {
            for (int value : input) sum += WcWidth.width(value);
        } else {
            for (int value : input) sum += WcWidthBaseline.width(value);
        }
        return sum;
    }

    private static double measure(int[] input, boolean candidate, long nanos) {
        long start = System.nanoTime();
        long elapsed;
        long calls = 0;
        do {
            for (int i = 0; i < 32; i++) sink = scan(input, candidate);
            calls += (long) input.length * 32;
            elapsed = System.nanoTime() - start;
        } while (elapsed < nanos);
        return calls * 1e9 / elapsed;
    }

    public static void main(String[] args) {
        for (int cp = 0; cp <= 0x10ffff; cp++) {
            if (WcWidth.width(cp) != WcWidthBaseline.width(cp))
                throw new AssertionError("Width changed at " + cp);
        }
        for (int cp : new int[]{Integer.MIN_VALUE, -1, 0x110000, Integer.MAX_VALUE}) {
            if (WcWidth.width(cp) != WcWidthBaseline.width(cp))
                throw new AssertionError("Out-of-range width changed at " + cp);
        }
        System.out.println("{\"equivalent_codepoints\":1114112,\"boundary_checks\":4}");
        int[] unicode = {0x0302, 0x200d, 0x4e2d, 0x1f642, 0x00e5, 0x1d11e, 0xfe0f, 0x11a3};
        for (String workload : new String[]{"ascii", "mixed", "unicode"}) {
            int[] input = new int[8192];
            int seed = 12345;
            for (int i = 0; i < input.length; i++) {
                seed = seed * 1664525 + 1013904223;
                int value = seed >>> 1;
                input[i] = workload.equals("ascii") ? 32 + value % 95
                    : workload.equals("unicode") ? unicode[value % unicode.length]
                    : i % 4 == 0 ? unicode[value % unicode.length] : 32 + value % 95;
            }
            measure(input, false, 300000000L);
            measure(input, true, 300000000L);
            for (int pair = 0; pair < 5; pair++) {
                boolean first = pair % 2 != 0;
                double a = measure(input, first, 200000000L);
                double b = measure(input, !first, 200000000L);
                System.out.println("{\"workload\":\"" + workload + "\",\"pair\":" + pair
                    + ",\"baseline_calls_per_second\":" + (first ? b : a)
                    + ",\"candidate_calls_per_second\":" + (first ? a : b) + "}");
            }
        }
    }
}
