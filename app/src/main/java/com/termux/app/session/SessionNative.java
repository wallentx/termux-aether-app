package com.termux.app.session;

/** Loaded explicitly because a Shizuku UserService does not have an app's native search path. */
@androidx.annotation.Keep
final class SessionNative {
    static void load(String directory) { load(directory, System.getProperty("java.class.path")); }
    static synchronized void load(String directory, String classPath) {
        System.load(directory + "/libaether-session.so");
        configureCleanup(directory.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            classPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static native void configureCleanup(byte[] directory, byte[] classPath);
    static int[] start(String packageName, String script, String[] environment,
                       int rows, int columns, int cellWidth, int cellHeight) {
        return start(packageName, script, environment, rows, columns, cellWidth, cellHeight, true);
    }
    static int[] startPipes(String packageName, String script, String[] environment) {
        return start(packageName, script, environment, 0, 0, 0, 0, false);
    }
    private static int[] start(String packageName, String script, String[] environment,
                               int rows, int columns, int cellWidth, int cellHeight, boolean terminal) {
        byte[][] encoded = new byte[environment.length][];
        for (int i = 0; i < environment.length; i++)
            encoded[i] = environment[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return startUtf8(packageName.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            script.getBytes(java.nio.charset.StandardCharsets.UTF_8), encoded, rows, columns, cellWidth, cellHeight, terminal);
    }
    private static native int[] startUtf8(byte[] packageName, byte[] script, byte[][] environment,
                                         int rows, int columns, int cellWidth, int cellHeight, boolean terminal);
    /** Wait without reaping: the PID stays reserved until finish(), serialized with stop(). */
    static native void awaitExit(int pid);
    static native int finish(int pid);
    static native int signal(int pid, boolean terminate);
}
