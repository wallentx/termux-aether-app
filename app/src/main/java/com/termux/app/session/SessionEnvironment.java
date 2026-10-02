package com.termux.app.session;

import android.content.Context;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import java.io.File;
import java.util.HashMap;

/** Preserve Android bootclasspath/runtime and Termux path translation, without linker execution. */
public final class SessionEnvironment extends TermuxShellEnvironment {
    @Override public String[] setupShellCommandExecution(Context context, String[] command,
                                                         boolean loginShell, HashMap<String, String> environment) {
        String preload = environment.get("LD_PRELOAD");
        StringBuilder kept = new StringBuilder();
        if (preload != null) {
            for (String part : preload.split("[ :]+")) {
                if (part.isEmpty() || part.contains("libtermux-exec")) continue;
                if (kept.length() > 0) kept.append(':');
                kept.append(part);
            }
        }
        File exec = new File(TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH, "libtermux-exec-ld-preload.so");
        if (exec.isFile()) {
            if (kept.length() > 0) kept.append(':');
            kept.append(exec.getPath());
        }
        if (kept.length() == 0) environment.remove("LD_PRELOAD");
        else environment.put("LD_PRELOAD", kept.toString());
        environment.remove("TERMUX_EXEC__PROC_SELF_EXE");
        environment.put("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE", "disable");
        environment.put("AETHER_SESSION_BACKEND", "shizuku-runas");
        try {
            String storage = SessionStorage.prepare();
            environment.put("AETHER_SHARED_STORAGE", storage);
            environment.put("EXTERNAL_STORAGE", storage);
        } catch (Exception error) {
            // Private-directory sessions remain usable before Android storage access is granted.
            android.util.Log.w("AetherSession", "Shared storage unavailable", error);
        }
        return command;
    }
}
