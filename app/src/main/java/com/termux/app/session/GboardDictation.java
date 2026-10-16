package com.termux.app.session;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Dispatches one Android dictation key from the shell-UID service; never retries. */
final class GboardDictation {
    private static final String GBOARD_IME_PREFIX = "com.google.android.inputmethod.latin/";
    private static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    private static final int COMMAND_TIMEOUT_SECONDS = 3;

    private GboardDictation() {}

    interface CommandRunner {
        /** Returns stdout for a successful command, or null on failure. */
        String run(String... argv) throws Exception;
    }

    static boolean start(String ownerPackage, IGboardTapGuard guard) {
        return start(ownerPackage, guard, Build.VERSION.SDK_INT, GboardDictation::run);
    }

    static boolean start(String ownerPackage, IGboardTapGuard guard, int sdk, CommandRunner runner) {
        if (sdk < 36 || ownerPackage == null || ownerPackage.isEmpty() || guard == null) return false;
        try {
            if (!guard.isAllowed()) return false;
            String ime = runner.run("/system/bin/settings", "--user", "current", "get", "secure", "default_input_method");
            if (ime == null || !ime.trim().startsWith(GBOARD_IME_PREFIX)) return false;
            // Check actual foreground ownership immediately before injection, even if the UI
            // has not received its focus-loss callback yet.
            String windows = runner.run("/system/bin/dumpsys", "window", "displays");
            if (!ownerFocused(ownerPackage, windows) || !guard.isAllowed()) return false;
            // KEYCODE_DICTATE was added in API 36. Use its value to retain older SDK builds.
            return runner.run("/system/bin/input", "keyevent", "319") != null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static boolean ownerFocused(String ownerPackage, String windows) {
        if (windows == null || windows.length() > MAX_OUTPUT_BYTES) return false;
        boolean found = false;
        for (String line : windows.split("\n")) {
            if (!line.trim().startsWith("mCurrentFocus=")) continue;
            // Fail closed on ambiguous display focus rather than injecting into another app.
            if (found || !line.contains(" " + ownerPackage + "/")) return false;
            found = true;
        }
        return found;
    }

    private static String run(String... argv) throws Exception {
        java.lang.Process process = new ProcessBuilder(argv)
            .redirectError(new File("/dev/null")).start();
        FutureTask<String> output = new FutureTask<>(() -> readBounded(process.getInputStream()));
        Thread reader = new Thread(output, "aether-dictation-output");
        reader.setDaemon(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS);
        try {
            process.getOutputStream().close();
            reader.start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            return output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            output.cancel(true);
            process.getInputStream().close();
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_OUTPUT_BYTES) throw new IOException("Command output exceeds limit");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
