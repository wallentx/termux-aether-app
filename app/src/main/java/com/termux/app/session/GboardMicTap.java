package com.termux.app.session;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** One calibrated, visually verified tap; never retries or logs screen contents. */
public final class GboardMicTap {
    private GboardMicTap() {}

    public static String fingerprint(Bitmap bitmap, int x, int y) throws Exception {
        if (x < 32 || y < 32 || x + 32 > bitmap.getWidth() || y + 32 > bitmap.getHeight())
            throw new IllegalArgumentException("Microphone point is outside the screen");
        int[] pixels = new int[64 * 64];
        bitmap.getPixels(pixels, 0, 64, x - 32, y - 32, 64, 64);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int pixel : pixels) {
            digest.update((byte) (pixel >>> 24));
            digest.update((byte) (pixel >>> 16));
            digest.update((byte) (pixel >>> 8));
            digest.update((byte) pixel);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        return hash.toString();
    }

    static boolean tap(String ownerPackage, int x, int y, int width, int height, String expected, IGboardTapGuard guard) {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192
            || x < 32 || y < 32 || x + 32 > width || y + 32 > height
            || expected == null || !expected.matches("[0-9a-f]{64}")) return false;
        File capture = null;
        Bitmap bitmap = null;
        try {
            if (guard == null || !guard.isAllowed()) return false;
            capture = File.createTempFile("aether-gboard-", ".png", new File("/data/local/tmp"));
            android.system.Os.chmod(capture.getPath(), 0600);
            if (!run(capture, "/system/bin/screencap", "-p")) return false;
            bitmap = BitmapFactory.decodeFile(capture.getPath());
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height
                || !expected.equals(fingerprint(bitmap, x, y))) return false;
            // Recheck focus after taking the screenshot, immediately before injection.
            if (!run(capture, "/system/bin/dumpsys", "window")
                || capture.length() > 2 * 1024 * 1024) return false;
            boolean ownerFocused = false;
            for (String line : new String(Files.readAllBytes(capture.toPath()), StandardCharsets.UTF_8).split("\n")) {
                if (line.trim().startsWith("mCurrentFocus=")) {
                    ownerFocused = line.contains(" " + ownerPackage + "/");
                    break;
                }
            }
            return ownerFocused && guard.isAllowed() && run(null, "/system/bin/input", "tap", Integer.toString(x), Integer.toString(y));
        } catch (Exception unavailable) {
            return false;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (capture != null) capture.delete();
        }
    }

    private static boolean run(File output, String... argv) throws Exception {
        java.lang.Process process = new ProcessBuilder(argv)
            .redirectOutput(output == null ? new File("/dev/null") : output)
            .redirectError(new File("/dev/null")).start();
        try {
            process.getOutputStream().close();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
