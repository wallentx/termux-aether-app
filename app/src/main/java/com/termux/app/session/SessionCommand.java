package com.termux.app.session;

import java.util.ArrayList;
import java.util.List;

/** Literal argv/environment transport across run-as and Android's secure environment filtering. */
final class SessionCommand {
    private SessionCommand() {}

    static String quote(String text) {
        if (text == null || text.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid argument");
        return "'" + text.replace("'", "'\\''") + "'";
    }

    static String script(String executable, String cwd, String[] argv, String[] environment) {
        if (executable == null || !executable.startsWith("/") || argv == null || argv.length == 0
            || environment == null || cwd == null || !cwd.startsWith("/"))
            throw new IllegalArgumentException("Invalid session command");
        StringBuilder out = new StringBuilder("set -e\nunset LD_PRELOAD LD_LIBRARY_PATH TERMUX_EXEC__PROC_SELF_EXE\n");
        for (String entry : environment) {
            int equals = entry.indexOf('=');
            if (equals < 1 || entry.indexOf('\0') >= 0 || !entry.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_]*"))
                throw new IllegalArgumentException("Invalid environment key");
            // Other entries travel in execve's environment, not in a process-list-visible shell argument.
            // Privileged run-as startup strips TMPDIR; restore it here after dropping to the app UID.
            String key = entry.substring(0, equals);
            if (key.startsWith("LD_") || key.startsWith("TERMUX_EXEC__") || key.equals("HOME")
                || key.equals("PATH") || key.equals("SHELL") || key.equals("USER")
                || key.equals("LOGNAME") || key.equals("IFS") || key.equals("TMPDIR"))
                out.append("export ").append(entry, 0, equals + 1).append(quote(entry.substring(equals + 1))).append('\n');
        }
        out.append("cd ").append(quote(cwd)).append('\n');
        out.append("exec -a ").append(quote(argv[0])).append(' ').append(quote(executable));
        for (int i = 1; i < argv.length; i++) out.append(' ').append(quote(argv[i]));
        // Stay below Linux's per-argument limit, even for non-ASCII environments.
        if (out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 120000)
            throw new IllegalArgumentException("Session environment is too large");
        return out.toString();
    }

    static String[] bootstrapEnvironment(String[] environment) {
        List<String> result = new ArrayList<>();
        for (String entry : environment) {
            // The shell UID cannot load private Termux libraries before run-as drops to the app UID.
            if (!entry.startsWith("LD_") && !entry.startsWith("TERMUX_EXEC__")) result.add(entry);
        }
        return result.toArray(new String[0]);
    }
}
