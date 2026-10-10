package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.system.StructTimespec;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = BundledInstallerMarkerTest.Posix.class)
public class BundledInstallerMarkerTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    private BundledRishInstaller.InstallMarker marker(File file, String identity) {
        BundledRishInstaller.InstallMarker marker =
            new BundledRishInstaller.InstallMarker(file.getParentFile(), identity);
        marker.add(file, false);
        return marker;
    }

    private File payload() throws Exception {
        File file = new File(files.newFolder(), "runtime.so");
        BundledRishInstaller.installFile(file, new byte[]{1, 2}, false);
        return file;
    }

    @Test public void unchangedInstallationIsCurrentAcrossInstances() throws Exception {
        File file = payload();
        BundledRishInstaller.InstallMarker marker = marker(file, "apk-1");
        assertFalse(marker.isCurrent());
        marker.record();
        File receipt = new File(file.getParentFile(), ".installed-v1");
        FileTime modified = Files.getLastModifiedTime(receipt.toPath());
        assertTrue(marker(file, "apk-1").isCurrent());
        assertEquals(modified, Files.getLastModifiedTime(receipt.toPath()));
    }

    @Test public void changedApkOrGeneratedContentIdentityInvalidatesReceipt() throws Exception {
        File file = payload();
        marker(file, "apk-1\ndevice-1").record();
        assertFalse(marker(file, "apk-2\ndevice-1").isCurrent());
        assertFalse(marker(file, "apk-1\ndevice-2").isCurrent());
    }

    @Test
    @Config(sdk = {24, 26})
    public void legacyAndroidRetainsContentChecksForSameSizeRewrite() throws Exception {
        File file = payload();
        BundledRishInstaller.InstallMarker marker = marker(file, "apk-1");
        marker.record();
        FileTime modified = Files.getLastModifiedTime(file.toPath());
        assertTrue(file.setWritable(true, true));
        Files.write(file.toPath(), new byte[]{3, 4});
        assertTrue(file.setWritable(false, false));
        Files.setLastModifiedTime(file.toPath(), modified);
        assertFalse(marker.isCurrent());
        BundledRishInstaller.installFile(file, new byte[]{1, 2}, false);
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(file.toPath()));
    }

    @Test public void missingFileInvalidatesReceiptAndPreventsPublication() throws Exception {
        File file = payload();
        BundledRishInstaller.InstallMarker marker = marker(file, "apk-1");
        marker.record();
        assertTrue(file.delete());
        assertFalse(marker.isCurrent());
        try {
            marker.record();
            fail("Missing payload must not be recorded as installed");
        } catch (IOException expected) {
            assertFalse(marker.isCurrent());
        }
        BundledRishInstaller.installFile(file, new byte[]{1, 2}, false);
        marker.record();
        assertTrue(marker.isCurrent());
    }

    @Test public void sameSizeReplacementWithRestoredMtimeInvalidatesReceipt() throws Exception {
        File file = payload();
        marker(file, "apk-1").record();
        FileTime modified = Files.getLastModifiedTime(file.toPath());
        // Retain the old inode to ensure that the filesystem cannot immediately reuse it.
        assertTrue(file.renameTo(new File(file.getParentFile(), "old-inode")));
        BundledRishInstaller.installFile(file, new byte[]{3, 4}, false);
        Files.setLastModifiedTime(file.toPath(), modified);
        assertFalse(marker(file, "apk-1").isCurrent());
    }

    @Test public void inPlaceRewriteWithRestoredMtimeInvalidatesReceipt() throws Exception {
        File file = payload();
        marker(file, "apk-1").record();
        FileTime modified = Files.getLastModifiedTime(file.toPath());
        // Filesystem timestamps may use a coarse clock despite nanosecond fields.
        Thread.sleep(20);
        assertTrue(file.setWritable(true, true));
        Files.write(file.toPath(), new byte[]{3, 4});
        assertTrue(file.setWritable(false, false));
        Files.setLastModifiedTime(file.toPath(), modified);
        assertFalse(marker(file, "apk-1").isCurrent());
    }

    @Test public void changedPermissionsRequireRepair() throws Exception {
        File file = payload();
        BundledRishInstaller.InstallMarker marker = marker(file, "apk-1");
        marker.record();
        for (String mode : new String[]{"rw-------", "r--r--r--", "---------", "r-x------"}) {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode));
            assertFalse(mode, marker.isCurrent());
            BundledRishInstaller.installFile(file, new byte[]{1, 2}, false);
            marker.record();
            assertTrue(marker.isCurrent());
        }
    }

    @Test public void missingExecutableBitRequiresRepair() throws Exception {
        File file = payload();
        BundledRishInstaller.installFile(file, new byte[]{1, 2}, true);
        BundledRishInstaller.InstallMarker marker =
            new BundledRishInstaller.InstallMarker(file.getParentFile(), "apk-1");
        marker.add(file, true);
        marker.record();
        assertTrue(marker.isCurrent());
        assertTrue(file.setExecutable(false, false));
        assertFalse(marker.isCurrent());
    }

    @Test public void symlinkReplacementRequiresRepairWithoutChangingTarget() throws Exception {
        File file = payload();
        marker(file, "apk-1").record();
        File target = new File(file.getParentFile(), "target");
        assertTrue(file.renameTo(target));
        Files.createSymbolicLink(file.toPath(), target.toPath());
        assertFalse(marker(file, "apk-1").isCurrent());
        BundledRishInstaller.installFile(file, new byte[]{3, 4}, false);
        marker(file, "apk-1").record();
        assertTrue(marker(file, "apk-1").isCurrent());
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(target.toPath()));
    }

    @Test public void corruptReceiptRequiresRepair() throws Exception {
        File file = payload();
        BundledRishInstaller.InstallMarker marker = marker(file, "apk-1");
        marker.record();
        File receipt = new File(file.getParentFile(), ".installed-v1");
        byte[] corrupt = Files.readAllBytes(receipt.toPath());
        corrupt[0] ^= 1;
        assertTrue(receipt.setWritable(true, true));
        Files.write(receipt.toPath(), corrupt);
        assertFalse(marker.isCurrent());
        marker.record();
        assertTrue(marker.isCurrent());
    }

    @Test public void sameVersionCodeApkReinstallAndSplitChangesInvalidateIdentity() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File apk = files.newFile("base.apk");
        context.getApplicationInfo().sourceDir = apk.getAbsolutePath();
        PackageInfo info = new PackageInfo();
        info.packageName = context.getPackageName();
        info.applicationInfo = context.getApplicationInfo();
        info.versionCode = 1000000000;
        info.lastUpdateTime = 1;
        ShadowPackageManager packages = Shadow.extract(context.getPackageManager());
        packages.installPackage(info);
        String original = BundledRishInstaller.apkIdentity(context);
        info.lastUpdateTime = 2;
        packages.installPackage(info);
        String updated = BundledRishInstaller.apkIdentity(context);
        assertNotEquals(original, updated);
        context.getApplicationInfo().splitSourceDirs =
            new String[]{files.newFile("split.apk").getAbsolutePath()};
        assertNotEquals(updated, BundledRishInstaller.apkIdentity(context));
    }

    // Robolectric's default stat shadow does not preserve real inode/mode/nanosecond
    // metadata. Exercise receipt validation against the host filesystem instead.
    @Implements(Os.class)
    public static class Posix {
        @Implementation public static StructStat lstat(String path) throws ErrnoException {
            try {
                Map<String, Object> stat = Files.readAttributes(new File(path).toPath(), "unix:*",
                    LinkOption.NOFOLLOW_LINKS);
                return new StructStat((long) stat.get("dev"), (long) stat.get("ino"),
                    (int) stat.get("mode"), (int) stat.get("nlink"), (int) stat.get("uid"),
                    (int) stat.get("gid"), (long) stat.get("rdev"), (long) stat.get("size"),
                    time((FileTime) stat.get("lastAccessTime")), time((FileTime) stat.get("lastModifiedTime")),
                    time((FileTime) stat.get("ctime")), 4096, 0);
            } catch (IOException e) {
                throw new ErrnoException("lstat", OsConstants.ENOENT, e);
            }
        }

        private static StructTimespec time(FileTime time) {
            long nanos = time.to(TimeUnit.NANOSECONDS);
            return new StructTimespec(nanos / 1000000000L, nanos % 1000000000L);
        }
    }
}
