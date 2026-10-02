package com.termux.app.session;

import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Device component probe: real run-as children and pipes, not the installed AppShell integration. */
public final class PipeSessionProbe {
    private static String shellDirectory, appDirectory;
    private static String[] environment;

    public static void main(String[] args) throws Exception {
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(45000); Runtime.getRuntime().halt(124); }
            catch (InterruptedException ignored) {}
        });
        deadline.setDaemon(true);
        deadline.start();
        if (android.os.Process.myUid() != 2000) throw new AssertionError("Requires Shizuku shell UID");
        shellDirectory = args[0];
        appDirectory = args[1];
        SessionNative.load(shellDirectory);
        Map<String, String> vars = new HashMap<>(System.getenv());
        vars.remove("LD_LIBRARY_PATH");
        vars.remove("TERMUX_EXEC__PROC_SELF_EXE");
        String prefix = "/data/data/com.termux/files/usr";
        vars.put("HOME", "/data/data/com.termux/files/home");
        vars.put("PATH", prefix + "/bin:/system/bin");
        vars.put("TMPDIR", prefix + "/tmp");
        vars.put("LD_PRELOAD", prefix + "/lib/libtermux-exec-ld-preload.so");
        vars.put("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE", "disable");
        vars.put("GOROOT", args[2]);
        environment = vars.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);

        byte[] payload = new byte[262144];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        Result echo = run(new String[]{"/system/bin/sh", "-c",
            "if [ -t 0 ] || [ -t 1 ] || [ -t 2 ]; then exit 91; fi; cat; printf 'stderr-only\\n' >&2; exit 23"}, payload);
        check(echo.status == 23 && Arrays.equals(payload, echo.out)
            && echo.error().equals("stderr-only\n"), "large binary stdin/stdout, separate stderr, exit 23, no TTY");
        for (int i = 0; i < 3; i++) {
            Result empty = run(new String[]{"/system/bin/cat"}, new byte[0]);
            check(empty.status == 0 && empty.out.length == 0 && empty.err.length == 0, "empty stdin EOF " + i);
        }
        Result context = run(new String[]{"/system/bin/id", "-Z"}, new byte[0]);
        check(context.status == 0 && context.output().contains(":runas_app:"), "actual child domain " + context.output().trim());
        Result go = run(new String[]{args[2] + "/bin/go", "version"}, new byte[0]);
        check(go.status == 0 && go.output().startsWith("go version go"), "original Go: " + go.output().trim());
        Result gum = run(new String[]{args[3], "--version"}, new byte[0]);
        check(gum.status == 0 && gum.output().startsWith("gum version"), "original Gum: " + gum.output().trim());
        Result spawn = run(new String[]{args[4], "spawn"}, new byte[0]);
        check(spawn.status == 0 && spawn.output().contains("child-alias")
            && !spawn.output().contains("linker64"), "stock Go child process and executable identity");
        Result missing = run(new String[]{"/nonexistent/aether-probe"}, new byte[0]);
        check(missing.status == 127 && missing.err.length > 0, "failed exec reports exit/stderr");
        Result descendant = run(new String[]{"/system/bin/sh", "-c", "sleep 60 & printf leader; exit 7"}, new byte[0]);
        check(descendant.status == 7 && descendant.output().equals("leader"), "leader exit cleans descendants and closes pipes");

        int[] child = start(new String[]{"/system/bin/sh", "-c", "printf R; sleep 60 & wait"});
        try (OutputStream in = output(child[1]); InputStream out = input(child[2]); InputStream err = input(child[3])) {
            in.close();
            check(out.read() == 'R', "cancellation child ready");
            signal(child[0]);
            SessionNative.awaitExit(child[0]);
            check(SessionNative.finish(child[0]) == -9, "cancellation status -9");
            check(out.read() == -1 && err.read() == -1, "cancellation closes descendant pipes");
        }
        System.out.println("PASS: native background pipes");
        System.exit(0);
    }

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }

    private static int[] start(String[] command) {
        return SessionNative.startPipes("com.termux", SessionCommand.script(command[0],
            "/data/data/com.termux/files/home", command, environment), SessionCommand.bootstrapEnvironment(environment));
    }

    private static void signal(int pid) throws Exception {
        Process helper = new ProcessBuilder("/system/bin/run-as", "com.termux", "/system/bin/app_process",
            "-Djava.class.path=" + appDirectory + "/classes.dex", "/system/bin", SessionSignals.class.getName(),
            appDirectory, Integer.toString(pid), "stop").inheritIO().start();
        if (helper.waitFor() != 0) throw new AssertionError("Signal helper failed");
    }

    private static Result run(String[] command, byte[] data) throws Exception {
        int[] child = start(command);
        Drain out = new Drain(child[2]), err = new Drain(child[3]);
        out.start(); err.start();
        try (OutputStream in = output(child[1])) { in.write(data); }
        SessionNative.awaitExit(child[0]);
        signal(child[0]);
        int status = SessionNative.finish(child[0]);
        out.join(5000); err.join(5000);
        if (out.isAlive() || err.isAlive()) throw new AssertionError("Pipes failed to reach EOF");
        if (out.failure != null || err.failure != null) throw new AssertionError("Pipe read failed", out.failure != null ? out.failure : err.failure);
        return new Result(status, out.bytes.toByteArray(), err.bytes.toByteArray());
    }

    private static InputStream input(int fd) { return new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd)); }
    private static OutputStream output(int fd) { return new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(fd)); }

    private static final class Drain extends Thread {
        final int fd;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Throwable failure;
        Drain(int fd) { this.fd = fd; }
        @Override public void run() {
            try (InputStream stream = input(fd)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            } catch (Throwable error) { failure = error; }
        }
    }

    private static final class Result {
        final int status;
        final byte[] out, err;
        Result(int status, byte[] out, byte[] err) { this.status = status; this.out = out; this.err = err; }
        String output() { return new String(out, StandardCharsets.UTF_8); }
        String error() { return new String(err, StandardCharsets.UTF_8); }
    }
}
