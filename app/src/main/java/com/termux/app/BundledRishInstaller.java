package com.termux.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import com.termux.shared.logger.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Installs the optional shell client without changing the user's Shizuku authorization. */
public final class BundledRishInstaller {
    private BundledRishInstaller() {}

    static synchronized void install(Context context, File prefix) {
        try {
            File directory = new File(prefix, "libexec/termux-rish");
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new IOException("Cannot create " + directory);
            String[] assets = {"rish_shizuku.dex", "rish_shizuku_plus.dex", "LICENSE", "NOTICE"};
            InstallMarker marker = new InstallMarker(directory, apkIdentity(context));
            for (String name : assets) marker.add(new File(directory, name), false);
            marker.add(new File(directory, "termux-rish"), true);
            boolean current = marker.isCurrent();
            if (!current) {
                for (String name : assets) {
                    try (InputStream input = context.getAssets().open("rish/" + name)) {
                        installFile(new File(directory, name), readAll(input), false);
                    }
                }
                byte[] launcher;
                try (InputStream input = context.getAssets().open("rish/termux-rish")) {
                    launcher = new String(readAll(input), StandardCharsets.UTF_8)
                        .replace("@PREFIX@", prefix.getAbsolutePath())
                        .replace("@PACKAGE@", context.getPackageName())
                        .getBytes(StandardCharsets.UTF_8);
                }
                installFile(new File(directory, "termux-rish"), launcher, true);
            }
            // EEXIST also preserves dangling symlinks and manually installed commands.
            for (String name : new String[]{"termux-rish", "rish"}) {
                try {
                    Os.symlink("../libexec/termux-rish/termux-rish",
                        new File(prefix, "bin/" + name).getAbsolutePath());
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EEXIST) throw e;
                }
            }
            if (!current) marker.record();
            // Refresh independently of the APK receipt: the user may change managers.
            java.util.List<ApplicationInfo> managers = com.termux.app.session.ShizukuManagers
                .installed(context.getPackageManager());
            String[] names = new String[managers.size()];
            for (int i = 0; i < names.length; i++) names[i] = managers.get(i).packageName;
            String selected = chooseManager(rememberedManager(directory), names);
            String backend = "stock";
            for (ApplicationInfo manager : managers) {
                if (manager.packageName.equals(selected)) backend = backendFor(manager.packageName, manager.className);
            }
            installFile(new File(directory, "backend"), (backend + "\n").getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException | ErrnoException | RuntimeException e) {
            // An optional client must never send an existing prefix into bootstrap reset.
            Logger.logError("BundledRishInstaller", "Cannot install rish: " + e.getMessage());
        }
    }

    static String backendFor(String packageName, String className) {
        return com.termux.app.session.ShizukuManagers.PLUS.equals(packageName)
            || (className != null && className.startsWith("af.shizuku.manager.")) ? "plus" : "stock";
    }

    static String chooseManager(String remembered, String... installed) {
        for (String name : installed) if (name.equals(remembered)) return name;
        return installed.length == 0 ? null : installed[0];
    }

    private static String rememberedManager(File directory) throws IOException {
        File file = new File(directory, "manager");
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile()) || !file.isFile() || file.length() > 128)
            return null;
        try (InputStream input = new FileInputStream(file)) {
            return new String(readAll(input), StandardCharsets.UTF_8).trim();
        }
    }

    /** A verified discovery reply identifies which manager actually answered. */
    public static synchronized void selectManager(Context context, ApplicationInfo manager) {
        try {
            File directory = new File(context.getFilesDir(), "usr/libexec/termux-rish").getCanonicalFile();
            if (!directory.isDirectory()) return;
            installFile(new File(directory, "manager"),
                (manager.packageName + "\n").getBytes(StandardCharsets.UTF_8), false);
            installFile(new File(directory, "backend"),
                (backendFor(manager.packageName, manager.className) + "\n").getBytes(StandardCharsets.UTF_8), false);
        } catch (IOException error) {
            Logger.logError("BundledRishInstaller", "Cannot select rish loader: " + error.getMessage());
        }
    }

    // versionCode is deliberately shared by development builds, so use the actual
    // installed APK identity, including split APKs and reinstall/update time.
    static String apkIdentity(Context context) throws IOException {
        ApplicationInfo info = context.getApplicationInfo();
        StringBuilder identity = new StringBuilder(context.getPackageName());
        try {
            identity.append('\n').append(context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Cannot identify installed APK", e);
        }
        appendApk(identity, info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String path : info.splitSourceDirs) appendApk(identity, path);
        }
        return identity.append('\n').append(info.nativeLibraryDir).toString();
    }

    private static void appendApk(StringBuilder identity, String path) {
        File apk = new File(path);
        identity.append('\n').append(path).append(':').append(apk.length())
            .append(':').append(apk.lastModified());
    }

    /** Small receipt checked with stat calls, without reading runtime assets or installed payloads. */
    static final class InstallMarker {
        private final File marker;
        private final String identity;
        private final Map<File, Boolean> files = new LinkedHashMap<>();

        InstallMarker(File directory, String identity) {
            marker = new File(directory, ".installed-v1");
            this.identity = identity;
        }

        void add(File file, boolean executable) {
            files.put(file, executable);
        }

        boolean isCurrent() throws IOException {
            // Older StructStat APIs expose ctime only in seconds, which can hide
            // same-size rewrites. Retain content comparisons on those devices.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return false;
            byte[] state = snapshot();
            if (state == null || !marker.getAbsoluteFile().equals(marker.getCanonicalFile())
                || !marker.isFile() || marker.length() != state.length) return false;
            try (InputStream input = new FileInputStream(marker)) {
                return Arrays.equals(state, readAll(input));
            } catch (IOException e) {
                return false;
            }
        }

        // Publish only after every file and command link has been installed. An
        // interrupted or failed update retains a stale receipt and is retried.
        void record() throws IOException {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return;
            byte[] state = snapshot();
            if (state == null) throw new IOException("Incomplete bundled installation: " + marker);
            installFile(marker, state, false);
        }

        private byte[] snapshot() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(identity);
                for (Map.Entry<File, Boolean> entry : files.entrySet()) {
                    File file = entry.getKey();
                    if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) return null;
                    StructStat stat;
                    try {
                        stat = Os.lstat(file.getAbsolutePath());
                    } catch (ErrnoException e) {
                        return null;
                    }
                    int mode = OsConstants.S_IFREG | (entry.getValue() ? 0500 : 0400);
                    if (stat.st_mode != mode) return null;
                    output.writeUTF(file.getAbsolutePath());
                    output.writeLong(stat.st_dev);
                    output.writeLong(stat.st_ino);
                    output.writeLong(stat.st_size);
                    output.writeInt(stat.st_mode);
                    output.writeLong(stat.st_mtime);
                    output.writeLong(stat.st_ctime);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        output.writeLong(stat.st_mtim.tv_nsec);
                        output.writeLong(stat.st_ctim.tv_nsec);
                    }
                }
            }
            return bytes.toByteArray();
        }
    }

    static void installFile(File destination, byte[] content, boolean executable) throws IOException {
        // Do not chmod a symlink target. Replace the link atomically below instead.
        if (destination.getAbsoluteFile().equals(destination.getCanonicalFile())
            && destination.isFile() && destination.canRead()) {
            try (InputStream input = new FileInputStream(destination)) {
                if (Arrays.equals(content, readAll(input))) {
                    setPermissions(destination, executable);
                    return;
                }
            }
        }
        File temporary = File.createTempFile(".rish-", ".tmp", destination.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                // Android 14+ requires dynamically loaded DEX files to be read-only.
                // Keep the already-open descriptor for writing, then publish by rename.
                setPermissions(temporary, executable);
                output.write(content);
                output.getFD().sync();
            }
            if (!temporary.renameTo(destination))
                throw new IOException("Cannot replace " + destination);
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
        }
    }

    private static void setPermissions(File file, boolean executable) throws IOException {
        if (!file.setWritable(false, false) || !file.setReadable(false, false)
            || !file.setReadable(true, true) || !file.setExecutable(false, false)
            || (executable && !file.setExecutable(true, true)))
            throw new IOException("Cannot set private read-only permissions on " + file);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
