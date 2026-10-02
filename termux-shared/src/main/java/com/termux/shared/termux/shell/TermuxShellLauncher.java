package com.termux.shared.termux.shell;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Launch dynamic app-data executables without execve of a writable app-data file. */
public final class TermuxShellLauncher {
    private TermuxShellLauncher() {}

    public static String[] prepare(String[] command, boolean loginShell, boolean linkerRequired,
                                   boolean is64Bit, File dataDirectory) {
        if (!linkerRequired || command.length == 0 || !isInside(command[0], dataDirectory))
            return command;

        List<String> launch = new ArrayList<>();
        launch.add(is64Bit ? "/system/bin/linker64" : "/system/bin/linker");
        launch.add(command[0]);
        // The Android linker replaces argv[0] with the executable path, losing the leading '-'.
        if (loginShell) launch.add("-l");
        launch.addAll(Arrays.asList(command).subList(1, command.length));
        return launch.toArray(new String[0]);
    }

    public static void addExecPreload(Map<String, String> environment, File library) {
        if (!library.isFile()) return;
        String path = library.getAbsolutePath();
        String existing = environment.get("LD_PRELOAD");
        if (existing != null && Arrays.asList(existing.split("[ :]+")).contains(path)) return;
        environment.put("LD_PRELOAD", path + (existing == null || existing.isEmpty() ? "" : ":" + existing));
    }

    private static boolean isInside(String executable, File directory) {
        try {
            return new File(executable).getCanonicalPath().startsWith(directory.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }
}
