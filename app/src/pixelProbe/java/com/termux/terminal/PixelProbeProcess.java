package com.termux.terminal;

import org.json.JSONException;
import org.json.JSONObject;

/** Uses the same package-private JNI PTY launcher as TerminalSession. Probe builds only. */
public final class PixelProbeProcess {
    static {
        System.loadLibrary("termux-pixel-probe");
    }

    private PixelProbeProcess() {}

    public static native String capabilities();
    private static native String collect(int fd, int pid);

    public static JSONObject run(String command, String cwd, String[] args, String[] environment)
        throws JSONException {
        int[] pid = new int[1];
        int fd = JNI.createSubprocess(command, cwd, args, environment, pid, 24, 80, 0, 0);
        try {
            return new JSONObject(collect(fd, pid[0]));
        } finally {
            JNI.close(fd);
        }
    }
}
