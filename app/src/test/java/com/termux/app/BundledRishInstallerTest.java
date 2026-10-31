package com.termux.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.Assert.*;

public class BundledRishInstallerTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void selectsPlusForSideBySideAndDropInWithoutChangingStock() {
        assertEquals("stock", BundledRishInstaller.backendFor("moe.shizuku.privileged.api", "moe.shizuku.manager.ShizukuApplication"));
        assertEquals("plus", BundledRishInstaller.backendFor("af.shizuku.plus.api", null));
        assertEquals("plus", BundledRishInstaller.backendFor("moe.shizuku.privileged.api", "af.shizuku.manager.ShizukuApplication"));
        assertEquals("stock", BundledRishInstaller.backendFor("moe.shizuku.privileged.api", null));
    }

    @Test public void startupRetainsVerifiedStockChoiceWhenPlusIsAlsoInstalled() {
        String plus = "af.shizuku.plus.api", stock = "moe.shizuku.privileged.api";
        assertEquals(stock, BundledRishInstaller.chooseManager(stock, plus, stock));
        assertEquals(plus, BundledRishInstaller.chooseManager(stock, plus));
        assertEquals(plus, BundledRishInstaller.chooseManager(null, plus, stock));
        assertEquals(plus, BundledRishInstaller.chooseManager("untrusted.package", plus, stock));
        assertNull(BundledRishInstaller.chooseManager(stock));
    }

    @Test
    public void updatesReadOnlyDexWithoutChangingThePreviouslyPublishedInode() throws Exception {
        File directory = files.newFolder();
        File dex = new File(directory, "rish.dex");
        BundledRishInstaller.installFile(dex, new byte[]{1, 2}, false);
        File previous = new File(directory, "previous.dex");
        Files.createLink(previous.toPath(), dex.toPath());
        BundledRishInstaller.installFile(dex, new byte[]{3, 4}, false);
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(previous.toPath()));
        assertArrayEquals(new byte[]{3, 4}, Files.readAllBytes(dex.toPath()));
        assertEquals(PosixFilePermissions.fromString("r--------"),
            Files.getPosixFilePermissions(dex.toPath()));
        assertEquals(2, directory.list().length);
    }

    @Test
    public void unchangedContentKeepsInodeAndRepairsWritablePermissions() throws Exception {
        File dex = files.newFile("rish.dex");
        BundledRishInstaller.installFile(dex, new byte[]{1}, false);
        File previous = new File(dex.getParentFile(), "previous.dex");
        Files.createLink(previous.toPath(), dex.toPath());
        assertTrue(dex.setWritable(true, true));
        BundledRishInstaller.installFile(dex, new byte[]{1}, false);
        assertTrue(Files.isSameFile(previous.toPath(), dex.toPath()));
        assertEquals(PosixFilePermissions.fromString("r--------"),
            Files.getPosixFilePermissions(dex.toPath()));
    }

    @Test
    public void replacesSymlinkWithoutWritingOrChangingItsTarget() throws Exception {
        File target = files.newFile("custom");
        Files.write(target.toPath(), new byte[]{9});
        File launcher = new File(target.getParentFile(), "launcher");
        Files.createSymbolicLink(launcher.toPath(), target.toPath());
        BundledRishInstaller.installFile(launcher, new byte[]{8}, true);
        assertFalse(Files.isSymbolicLink(launcher.toPath()));
        assertArrayEquals(new byte[]{9}, Files.readAllBytes(target.toPath()));
        assertEquals(PosixFilePermissions.fromString("r-x------"),
            Files.getPosixFilePermissions(launcher.toPath()));
    }
}
