package com.termux.app.session;

import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import com.termux.shared.termux.TermuxConstants;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Keep the app's storage mount alive independently of shell FD inheritance/close_fds behavior. */
final class SessionStorage {
    private static ParcelFileDescriptor root;

    private SessionStorage() {}

    static synchronized String prepare() throws Exception {
        if (root == null) {
            java.io.FileDescriptor fd = Os.open(Environment.getExternalStorageDirectory().getPath(),
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw new IOException("Shared storage is not a directory");
                root = ParcelFileDescriptor.dup(fd);
            } finally { Os.close(fd); }
        }
        File bridge = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux/aether-storage");
        if (!bridge.getCanonicalPath().equals(bridge.getAbsolutePath()))
            throw new IOException("Storage bridge directory must not be a symlink");
        if (!bridge.isDirectory() && !bridge.mkdirs()) throw new IOException("Cannot create storage bridge");
        File shared = new File(bridge, "shared");
        String target = "/proc/" + Process.myPid() + "/fd/" + root.getFd();
        publish(shared, target, true);

        // Preserve custom files/links. Only migrate the conventional termux-setup-storage targets.
        File storage = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "storage");
        if (!storage.getCanonicalPath().equals(storage.getAbsolutePath())) return shared.getPath();
        if (!storage.isDirectory() && !storage.mkdirs()) throw new IOException("Cannot create storage shortcuts");
        Map<String, String> folders = new LinkedHashMap<>();
        folders.put("shared", "");
        folders.put("downloads", "Download");
        folders.put("documents", "Documents");
        folders.put("dcim", "DCIM");
        folders.put("pictures", "Pictures");
        folders.put("music", "Music");
        folders.put("movies", "Movies");
        String external = Environment.getExternalStorageDirectory().getPath();
        for (Map.Entry<String, String> entry : folders.entrySet()) {
            String suffix = entry.getValue().isEmpty() ? "" : "/" + entry.getValue();
            File link = new File(storage, entry.getKey());
            String existing = readLink(link);
            if (existing == null && link.exists()) continue;
            if (existing != null && !replaceable(existing, external + suffix, shared.getPath() + suffix)) continue;
            publish(link, shared.getPath() + suffix, false);
        }
        return shared.getPath();
    }

    static boolean replaceable(String target, String conventional, String managed) {
        return target.equals(conventional) || target.equals(managed)
            || target.equals(conventional.replace("/storage/emulated/0", "/sdcard"));
    }

    static String workingDirectory(String path) throws Exception {
        String external = Environment.getExternalStorageDirectory().getPath();
        if (under(path, external) || under(path, "/sdcard"))
            return translate(path, external, prepare());
        return path;
    }

    static String translate(String path, String external, String bridge) {
        if (under(path, external)) return bridge + path.substring(external.length());
        if (under(path, "/sdcard")) return bridge + path.substring("/sdcard".length());
        return path;
    }

    private static boolean under(String path, String parent) {
        return path != null && (path.equals(parent) || path.startsWith(parent + "/"));
    }

    private static String readLink(File path) {
        try { return Os.readlink(path.getPath()); } catch (Exception ignored) { return null; }
    }

    private static void publish(File link, String target, boolean managed) throws Exception {
        String existing = readLink(link);
        if (existing == null && link.exists()) throw new IOException("Refusing to replace " + link);
        if (target.equals(existing)) return;
        if (managed && existing != null && !existing.matches("/proc/[0-9]+/fd/[0-9]+"))
            throw new IOException("Refusing to replace custom storage bridge");
        File temporary = new File(link.getParentFile(), ".aether-" + UUID.randomUUID());
        try {
            Os.symlink(target, temporary.getPath());
            Os.rename(temporary.getPath(), link.getPath());
        } finally {
            // delete() unlinks only the temporary link, never its target.
            temporary.delete();
        }
    }
}
