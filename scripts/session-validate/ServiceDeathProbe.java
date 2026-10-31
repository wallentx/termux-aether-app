package com.termux.app.session;

import android.os.ParcelFileDescriptor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/** Kills isolated launcher fixtures, never the live Shizuku service or a user session. */
public final class ServiceDeathProbe {
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("Run as Shizuku shell");
        if (args.length > 2 && args[2].equals("parent")) { parent(args); return; }
        checkNormalPipes(args[0]);
        AssertionError failure = null;
        for (String mode : new String[]{"pipes", "terminal"}) {
            try { checkCrash(args[0], args[1], mode); }
            catch (AssertionError error) { System.out.println("FAIL " + mode + ": " + error); failure = error; }
        }
        if (failure != null) throw failure;
        System.out.println("PASS: service death kills pipe/PTY leaders and SIGHUP-ignoring descendants");
    }

    private static void checkNormalPipes(String directory) throws Exception {
        SessionNative.load(directory);
        String[] environment = System.getenv().entrySet().stream()
            .filter(entry -> !entry.getKey().startsWith("LD_"))
            .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        int[] child = SessionNative.startPipes("com.termux", "/system/bin/cat; printf separate >&2; exit 23", environment);
        ParcelFileDescriptor.adoptFd(child[1]).close();
        try (InputStream out = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[2]));
             InputStream err = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(child[3]))) {
            if (out.read() != -1) throw new AssertionError("Empty stdin did not produce output EOF");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            int value;
            while ((value = err.read()) != -1) bytes.write(value);
            SessionNative.awaitExit(child[0]);
            if (SessionNative.finish(child[0]) != 23 || !bytes.toString("UTF-8").equals("separate"))
                throw new AssertionError("Pipe result/status changed");
        }
        System.out.println("PASS normal pipes: stdin EOF, separate stderr, exit 23");
    }

    private static void parent(String[] args) throws Exception {
        String shell = args[0], app = args[1], mode = args[3], name = args[4];
        SessionNative.load(shell);
        // The fake service loads the shell copy; its run-as helper needs the app copy.
        try {
            java.lang.reflect.Method configure = SessionNative.class.getDeclaredMethod("configureCleanup", byte[].class, byte[].class);
            configure.setAccessible(true);
            configure.invoke(null, app.getBytes(StandardCharsets.UTF_8), (app + "/classes.dex").getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchMethodException originalLauncher) {
            // Allows the same reproduction to check the pre-monitor implementation.
        }
        String[] environment = System.getenv().entrySet().stream()
            .filter(entry -> !entry.getKey().startsWith("LD_"))
            .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        String descendant = "trap '' HUP; echo $$ > " + SessionCommand.quote(app + "/" + name + ".descendant")
            + "; exec /system/bin/sleep 120";
        String script = "trap '' HUP; " + (mode.equals("terminal") ? "set -m; " : "")
            + "echo $$ > " + SessionCommand.quote(app + "/" + name + ".leader")
            + "; /system/bin/sh -c " + SessionCommand.quote(descendant) + " & printf R; wait";
        int[] child = mode.equals("terminal")
            ? SessionNative.start("com.termux", script, environment, 24, 80, 8, 16)
            : SessionNative.startPipes("com.termux", script, environment);
        int output = child[mode.equals("terminal") ? 1 : 2];
        // Keep the master/pipe open until this fixture is killed. PTY hangup is insufficient.
        InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(output));
        if (input.read() != 'R') throw new AssertionError("Fixture command did not start");
        Files.write(Paths.get(shell, name + ".ready"),
            (child[0] + "\n").getBytes(StandardCharsets.UTF_8));
        Thread.sleep(120000);
        throw new AssertionError("Controller did not kill fixture");
    }

    private static void checkCrash(String shell, String app, String mode) throws Exception {
        String name = "death-" + mode + "-" + System.nanoTime();
        Path ready = Paths.get(shell, name + ".ready");
        Process parent = new ProcessBuilder("/system/bin/app_process", "-Djava.class.path=" + shell + "/classes.dex",
            "/system/bin", ServiceDeathProbe.class.getName(), shell, app, "parent", mode, name)
            .inheritIO().start();
        int leader = -1;
        String leaderIdentity = null;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(ready) && System.nanoTime() < deadline && parent.isAlive()) Thread.sleep(25);
            if (!Files.exists(ready)) throw new AssertionError("Fixture startup failed: " + mode);
            leader = Integer.parseInt(new String(Files.readAllBytes(ready), StandardCharsets.UTF_8).trim());
            int descendant = -1;
            while (descendant < 0 && System.nanoTime() < deadline) {
                String text = appRead(app + "/" + name + ".descendant").trim();
                if (!text.isEmpty()) descendant = Integer.parseInt(text);
                else Thread.sleep(25);
            }
            if (descendant < 2) throw new AssertionError("Descendant did not start");
            leaderIdentity = stat(leader);
            String descendantIdentity = stat(descendant);
            if (!running(leaderIdentity) || !running(descendantIdentity)) throw new AssertionError("Fixture exited early");
            parent.destroyForcibly();
            if (!parent.waitFor(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture parent did not die");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while ((sameProcessRunning(leader, leaderIdentity) || sameProcessRunning(descendant, descendantIdentity))
                && System.nanoTime() < deadline) Thread.sleep(25);
            if (sameProcessRunning(leader, leaderIdentity) || sameProcessRunning(descendant, descendantIdentity))
                throw new AssertionError("Service death left an orphan: " + mode);
            System.out.println("PASS " + mode + " service death: leader=" + leader + " descendant=" + descendant);
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
            // Failure cleanup is limited to this fixture's unreaped leader/session.
            if (leader > 1 && leaderIdentity != null && sameProcessRunning(leader, leaderIdentity)) {
                Process cleanup = new ProcessBuilder("/system/bin/run-as", "com.termux", "/system/bin/app_process",
                    "-Djava.class.path=" + app + "/classes.dex", "/system/bin", SessionSignals.class.getName(),
                    app, Integer.toString(leader), "stop").inheritIO().start();
                cleanup.waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(ready);
        }
    }

    private static String stat(int pid) throws Exception { return appRead("/proc/" + pid + "/stat").trim(); }
    private static boolean running(String stat) {
        return !stat.isEmpty() && !stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z ");
    }
    private static boolean sameProcessRunning(int pid, String original) throws Exception {
        String current = stat(pid);
        return running(current) && current.substring(current.lastIndexOf(')') + 2).split(" ")[19]
            .equals(original.substring(original.lastIndexOf(')') + 2).split(" ")[19]);
    }
    private static String appRead(String path) throws Exception {
        Process read = new ProcessBuilder("/system/bin/run-as", "com.termux", "/system/bin/cat", path)
            .redirectError(new java.io.File("/dev/null")).start();
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try (InputStream input = read.getInputStream()) {
            int count;
            while ((count = input.read(buffer)) != -1) result.write(buffer, 0, count);
        }
        read.waitFor();
        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }
}
