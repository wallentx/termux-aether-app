package com.termux.app.session;

import androidx.annotation.Keep;

/** Invoked only after run-as: shell UID cannot signal a child that switched to the app UID. */
@Keep
public final class SessionSignals {
    @Keep public static void main(String[] args) {
        try {
            Thread deadline = new Thread(() -> {
                try { Thread.sleep(5000); Runtime.getRuntime().halt(124); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }, "session-signal-deadline");
            deadline.setDaemon(true);
            deadline.start();
            if (args.length != 3 || android.os.Process.myUid() < 10000) System.exit(2);
            int leader = Integer.parseInt(args[1]);
            if (leader < 2) System.exit(2);
            SessionNative.load(args[0]);
            int error = SessionNative.signal(leader, "stop".equals(args[2]));
            System.exit(error == 0 ? 0 : 1);
        } catch (Throwable error) {
            android.util.Log.e("AetherSession", "Cannot signal session", error);
            System.exit(1);
        }
    }
}
