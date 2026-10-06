package com.termux.app.session;

/** Compile with a fixture-only TermuxConstants.HOME; never point this probe at the real HOME. */
public final class StorageProbe {
    public static void main(String[] args) {
        try {
            String home = com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH;
            if (!home.contains("aether-session-check/storage-home")) throw new IllegalStateException("Not a fixture home");
            java.io.File storage = new java.io.File(home, "storage");
            storage.mkdirs();
            android.system.Os.symlink("/custom-must-be-preserved", storage + "/downloads");
            String shared = SessionStorage.prepare();
            if (!SessionStorage.prepare().equals(shared)) throw new AssertionError("Unstable bridge path");
            if (!"/custom-must-be-preserved".equals(android.system.Os.readlink(storage + "/downloads")))
                throw new AssertionError("Custom shortcut changed");
            System.out.println("STORAGE_READY " + shared);
            System.out.flush();
            System.in.read(); // Keep the app-context directory FD alive for the run-as client.
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
