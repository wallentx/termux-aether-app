package com.termux.terminal;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TerminalBitmapGcTest {
    private void image(TerminalBuffer b) {
        // Just over 1 MiB of padded image storage.
        b.sixelStart(512, 516);
        for (int y = 0; y < 86; y++) {
            assertTrue(b.sixelReadData('~', 512));
            if (y < 85) assertTrue(b.sixelReadData('-', 1));
        }
        b.sixelEnd(0, 0, 16, 16);
    }
    private Map<?, ?> images(TerminalBuffer b) throws Exception {
        Field f = TerminalBuffer.class.getDeclaredField("mTerminalBitmaps");
        f.setAccessible(true);
        return (Map<?, ?>) f.get(b);
    }
    @Test public void scrollingPreservesNonAdjacentRectangleCopy() throws Exception {
        TerminalBuffer b = new TerminalBuffer(8, 8, 8);
        b.sixelStart(6, 6);
        assertTrue(b.sixelReadData('~', 6));
        b.sixelEnd(0, 0, 6, 6);
        long style = b.mLines[b.externalToInternalRow(0)].getStyle(0);
        assertNotNull(b.getSixelBitmap(style));
        b.blockCopy(0, 0, 1, 1, 0, 5);
        b.scrollDownOneLine(0, 8, TextStyle.NORMAL);
        assertNotNull("Copied cell still owns the scrolled image", b.getSixelBitmap(style));
        assertEquals(style, b.mLines[b.externalToInternalRow(4)].getStyle(0));
        for (int i = 0; i < 5; i++) b.scrollDownOneLine(0, 8, TextStyle.NORMAL);
        assertTrue("Last reference scrolled out", images(b).isEmpty());
    }
    @Test public void allocationPressureReleasesReplacedImagesBeforeTimer() throws Exception {
        TerminalBuffer b = new TerminalBuffer(64, 256, 64);
        for (int i = 0; i < 40; i++) image(b);
        assertTrue("Replaced images should be swept within 16 MiB of allocations", images(b).size() < 16);
        assertNotNull(b.getSixelBitmap(b.mLines[b.externalToInternalRow(0)].getStyle(0)));
    }
    @Test public void pressureSweepPreservesVisibleAndScrollbackImages() throws Exception {
        TerminalBuffer b = new TerminalBuffer(64, 256, 64);
        image(b);
        long historyStyle = b.mLines[b.externalToInternalRow(0)].getStyle(0);
        for (int y = 0; y < 64; y++) b.scrollDownOneLine(0, 64, TextStyle.NORMAL);
        for (int i = 0; i < 40; i++) image(b);
        assertNotNull("Scrollback still owns this bitmap", b.getSixelBitmap(historyStyle));
        assertNotNull(b.getSixelBitmap(b.mLines[b.externalToInternalRow(0)].getStyle(0)));
        b.clearTerminalBitmaps();
        assertTrue(images(b).isEmpty());
    }
}
