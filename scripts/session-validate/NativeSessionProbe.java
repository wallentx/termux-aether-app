package com.termux.app.session;

import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Small device probe, run as shell via Shizuku; never installs or changes the user's shell. */
public final class NativeSessionProbe {
    private static java.nio.file.Path reportPath;
    public static void main(String[] args) throws Exception {
        reportPath = java.nio.file.Paths.get(args[0], "probe.log");
        try { run(args); }
        catch (Throwable error) { report("FAIL: " + error); error.printStackTrace(System.err); System.exit(1); }
    }

    private static void report(String text) throws Exception {
        java.nio.file.Files.write(reportPath, (text + "\n").getBytes(StandardCharsets.UTF_8),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        System.out.println(text);
    }

    private static void run(String[] args) throws Exception {
        report("NATIVE_PROBE_STARTED");
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("Run probe through Shizuku shell");
        SessionNative.load(args[0]);
        String[] environment = System.getenv().entrySet().stream()
            .filter(e -> !e.getKey().startsWith("LD_"))
            .map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        for (int i = 0; i < 3; i++) {
            String script = SessionCommand.script("/system/bin/sh", "/data/data/com.termux/files/home",
                new String[]{"aether-test", "-c", "printf 'NATIVE_OK æ 🚀\\n'; exit 23"}, environment);
            int[] child = SessionNative.start("com.termux", script, environment, 24, 80, 8, 16);
            report("START pid=" + child[0] + " fd=" + child[1]);
            SessionNative.awaitExit(child[0]);
            int status = SessionNative.finish(child[0]);
            String output = read(child[1]);
            if (status != 23 || !output.contains("NATIVE_OK æ 🚀"))
                throw new AssertionError("Exit/UTF-8 mismatch: " + status + " " + output);
            report("EXIT PASS " + i);
        }
        long stopStarted = System.nanoTime();
        int[] child = SessionNative.start("com.termux", "echo STOP_READY; sleep 10 & wait", environment, 24, 80, 8, 16);
        Thread.sleep(250);
        Process helper = new ProcessBuilder("/system/bin/run-as", "com.termux", "/system/bin/app_process",
            "-Djava.class.path=" + args[1] + "/classes.dex", "/system/bin", SessionSignals.class.getName(),
            args[1], Integer.toString(child[0]), "stop").inheritIO().start();
        if (helper.waitFor() != 0) throw new AssertionError("Signal helper failed");
        SessionNative.awaitExit(child[0]);
        int status = SessionNative.finish(child[0]);
        String output = read(child[1]);
        if (status != -9 || !output.contains("STOP_READY")) throw new AssertionError("Stop failed: " + status);
        if (System.nanoTime() - stopStarted > 5_000_000_000L)
            throw new AssertionError("Cleanup took too long; background job may have exited naturally");
        report("PASS: repeated exit=23, UTF-8, PTY EOF, stop=-9 and background cleanup");
        System.exit(0);
    }

    private static String read(int fd) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))) {
            byte[] buffer = new byte[1024];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            } catch (java.io.IOException e) {
                // Linux PTY masters report EIO after the last slave closes.
                if (!e.toString().contains("EIO")) throw e;
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
