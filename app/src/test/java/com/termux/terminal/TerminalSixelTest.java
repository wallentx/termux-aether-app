package com.termux.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Random;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TerminalSixelTest {
    private TerminalSixel create(int width, int height) {
        return new TerminalSixel(null, Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
    }

    @Test public void everyMaskAndRepeatPreservesUnpaintedPixels() {
        for (int repeat : new int[]{1, 2, 3, 16, 127, 8192}) {
            for (int mask = 0; mask < 64; mask++) {
                TerminalSixel sixel = create(repeat + 2, 6);
                sixel.getBitmap().eraseColor(0xff112233);
                sixel.mCurX = 1;
                sixel.setRGBColor(1, 100, 0, 0);
                assertTrue(sixel.readData('?' + mask, repeat));
                int[] row = new int[repeat + 2];
                for (int y = 0; y < 6; y++) {
                    sixel.getBitmap().getPixels(row, 0, row.length, 0, y, row.length, 1);
                    assertEquals(0xff112233, row[0]);
                    assertEquals(0xff112233, row[repeat + 1]);
                    int expected = (mask & (1 << y)) != 0 ? 0xffff0000 : 0xff112233;
                    for (int x = 1; x <= repeat; x++) assertEquals(expected, row[x]);
                }
                assertEquals(repeat + 1, sixel.getCurX());
                assertEquals(repeat + 1, sixel.getWidth());
                assertEquals(6, sixel.getHeight());
                sixel.getBitmap().recycle();
            }
        }
    }

    @Test public void colorCacheHandlesShorterRunsThenGrowthAndPaletteReuse() {
        TerminalSixel sixel = create(32, 6);
        int[][] pixels = new int[6][32];
        Random random = new Random(12345);
        for (int n = 0; n < 200; n++) {
            assertTrue(sixel.readData('$', 1));
            int repeat = 1 + random.nextInt(32);
            int mask = random.nextInt(64);
            if ((n & 1) == 0) sixel.setRGBColor(3, random.nextInt(101), random.nextInt(101), random.nextInt(101));
            else sixel.setColor(3);
            int color = sixel.getColor();
            assertTrue(sixel.readData('?' + mask, repeat));
            for (int y = 0; y < 6; y++) {
                for (int x = 0; x < repeat; x++) {
                    if ((mask & (1 << y)) != 0) pixels[y][x] = color;
                }
                int[] actual = new int[32];
                sixel.getBitmap().getPixels(actual, 0, 32, 0, y, 32, 1);
                assertArrayEquals(pixels[y], actual);
            }
        }
    }

    @Test public void resizeAndNewlinePreserveEarlierBands() {
        TerminalSixel sixel = create(1, 1);
        sixel.setRGBColor(2, 0, 100, 0);
        assertTrue(sixel.readData('~', 150));
        assertTrue(sixel.readData('-', 1));
        sixel.setRGBColor(2, 0, 0, 100);
        assertTrue(sixel.readData('@', 300));
        assertEquals(300, sixel.getWidth());
        assertEquals(12, sixel.getHeight());
        assertEquals(300, sixel.getCurX());
        assertEquals(6, sixel.getCurY());
        assertEquals(0xff00ff00, sixel.getBitmap().getPixel(149, 5));
        assertEquals(0, sixel.getBitmap().getPixel(150, 5));
        assertEquals(0xff0000ff, sixel.getBitmap().getPixel(299, 6));
        assertEquals(0, sixel.getBitmap().getPixel(299, 7));
    }

    @Test public void zeroNegativeInvalidAndOversizedRunsDoNotPaint() {
        TerminalSixel sixel = create(16, 6);
        assertTrue(sixel.readData('~', 0));
        assertTrue(sixel.readData('~', -1));
        assertTrue(sixel.readData('!', 4));
        assertFalse(sixel.readData('~', TerminalSixel.SIXEL__MAX_REPEAT + 1));
        assertEquals(0, sixel.getCurX());
        assertEquals(0, sixel.getWidth());
        assertEquals(0, sixel.getHeight());
        assertEquals(0, sixel.getBitmap().getPixel(0, 0));
    }
}
