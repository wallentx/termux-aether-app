package com.termux.app.session;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionStorageTest {
    @Test public void translatesKernelWorkingDirectoryWithoutMatchingUnrelatedPrefixes() {
        String root = "/storage/emulated/0", bridge = "/home/.termux/aether-storage/shared";
        assertEquals(bridge + "/Download", SessionStorage.translate(root + "/Download", root, bridge));
        assertEquals(bridge, SessionStorage.translate("/sdcard", root, bridge));
        assertEquals("/storage/emulated/01", SessionStorage.translate("/storage/emulated/01", root, bridge));
        assertEquals("/sdcard-other", SessionStorage.translate("/sdcard-other", root, bridge));
        assertEquals("/home/project", SessionStorage.translate("/home/project", root, bridge));
    }
    @Test public void migratesOnlyStockOrAlreadyManagedTargets() {
        String stock = "/storage/emulated/0/Download", managed = "/home/.termux/aether-storage/shared/Download";
        assertTrue(SessionStorage.replaceable(stock, stock, managed));
        assertTrue(SessionStorage.replaceable("/sdcard/Download", stock, managed));
        assertTrue(SessionStorage.replaceable(managed, stock, managed));
        assertFalse(SessionStorage.replaceable("/home/my-custom-downloads", stock, managed));
        assertFalse(SessionStorage.replaceable(stock + "-private", stock, managed));
    }
}
