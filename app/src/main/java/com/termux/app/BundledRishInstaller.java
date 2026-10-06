package com.termux.app;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.termux.shared.logger.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Installs the optional shell client without changing the user's Shizuku authorization. */
final class BundledRishInstaller {
    private BundledRishInstaller() {}

    static synchronized void install(Context context, File prefix) {
        try {
            File directory = new File(prefix, "libexec/termux-rish");
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new IOException("Cannot create " + directory);
            for (String name : new String[]{"rish_shizuku.dex", "LICENSE", "NOTICE"}) {
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
            // EEXIST also preserves dangling symlinks and manually installed commands.
            for (String name : new String[]{"termux-rish", "rish"}) {
                try {
                    Os.symlink("../libexec/termux-rish/termux-rish",
                        new File(prefix, "bin/" + name).getAbsolutePath());
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EEXIST) throw e;
                }
            }
        } catch (IOException | ErrnoException e) {
            // An optional client must never send an existing prefix into bootstrap reset.
            Logger.logError("BundledRishInstaller", "Cannot install rish: " + e.getMessage());
        }
    }

    static void installFile(File destination, byte[] content, boolean executable) throws IOException {
        // Do not chmod a symlink target. Replace the link atomically below instead.
        if (destination.getAbsoluteFile().equals(destination.getCanonicalFile()) && destination.isFile()) {
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
