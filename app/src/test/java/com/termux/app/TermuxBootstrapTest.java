package com.termux.app;

import com.termux.shared.termux.TermuxBootstrap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class TermuxBootstrapTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test
    public void switchesOnlyThePackagedDirectPreloadAndPreservesOtherFiles() throws Exception {
        File prefix = files.newFolder("linker-prefix");
        File lib = new File(prefix, "lib");
        assertTrue(lib.mkdir());
        File primary = new File(lib, "libtermux-exec-ld-preload.so");
        File direct = new File(lib, "libtermux-exec-direct-ld-preload.so");
        File linker = new File(lib, "libtermux-exec-linker-ld-preload.so");
        Files.write(primary.toPath(), new byte[]{1, 2});
        Files.write(direct.toPath(), new byte[]{1, 2});
        Files.write(linker.toPath(), new byte[]{3, 4});
        TermuxInstaller.selectLinkerPreload(prefix);
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(primary.toPath()));
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(direct.toPath()));
        assertEquals(3, lib.list().length);
        Files.write(primary.toPath(), new byte[]{5, 6});
        TermuxInstaller.selectLinkerPreload(prefix);
        assertArrayEquals(new byte[]{5, 6}, Files.readAllBytes(primary.toPath()));
    }

    @Test
    public void recognizesPacmanAndRetainsAptVariants() {
        assertEquals(TermuxBootstrap.PackageManager.PACMAN,
            TermuxBootstrap.PackageManager.managerOf("pacman"));
        assertEquals(TermuxBootstrap.PackageVariant.PACMAN_ANDROID_7,
            TermuxBootstrap.PackageVariant.variantOf("pacman-android-7"));
        assertNotNull(TermuxBootstrap.PackageVariant.variantOf("apt-android-7"));
        assertNotNull(TermuxBootstrap.PackageVariant.variantOf("apt-android-5"));
        assertNull(TermuxBootstrap.PackageVariant.variantOf("pacman-android-unknown"));
    }

    @Test
    public void detectsAptPrefixWithoutChangingItsFiles() throws Exception {
        File prefix = files.newFolder("prefix");
        File database = new File(prefix, "var/lib/dpkg/status");
        assertTrue(database.getParentFile().mkdirs());
        assertTrue(database.createNewFile());
        assertTrue(TermuxInstaller.hasAptOnlyPrefix(prefix));
        assertTrue(database.isFile());
    }

    @Test
    public void freshPrefixIsNotAnAptMigration() throws Exception {
        assertFalse(TermuxInstaller.hasAptOnlyPrefix(files.newFolder("fresh")));
    }

    @Test
    public void existingPacmanDatabaseIsRecognized() throws Exception {
        File prefix = files.newFolder("pacman");
        File pacman = new File(prefix, "var/lib/pacman/local/ALPM_DB_VERSION");
        assertTrue(pacman.getParentFile().mkdirs());
        assertTrue(pacman.createNewFile());
        assertFalse(TermuxInstaller.hasAptOnlyPrefix(prefix));
        File dpkg = new File(prefix, "var/lib/dpkg/status");
        assertTrue(dpkg.getParentFile().mkdirs());
        assertTrue(dpkg.createNewFile());
        assertFalse(TermuxInstaller.hasAptOnlyPrefix(prefix));
    }
}
