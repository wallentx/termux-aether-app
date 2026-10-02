package com.termux.terminal;

import junit.framework.TestCase;
import java.util.Arrays;
import java.util.Random;

public class TerminalBufferBulkTest extends TestCase {
    private TerminalBuffer seeded(int columns, int rows, boolean complex, boolean images) {
        TerminalBuffer b = new TerminalBuffer(columns, rows * 2, rows);
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++)
                b.setChar(x, y, '!' + (x + y) % 90, TextStyle.encode(x % 16, y % 16, x % 2));
            if (complex) {
                b.setChar(2, y, 0x754c, TextStyle.NORMAL);
                b.setChar(5, y, 0x0301, TextStyle.NORMAL);
                b.setChar(8, y, 0x1f600, TextStyle.NORMAL);
            }
            if (images) b.setChar(11, y, ' ', TextStyle.encodeTerminalBitmap(1, 2, 3));
            b.setLineWrap(y);
        }
        return b;
    }

    private void assertSameBuffer(TerminalBuffer expected, TerminalBuffer actual) {
        for (int y = 0; y < expected.mScreenRows; y++) {
            TerminalRow a = expected.mLines[expected.externalToInternalRow(y)];
            TerminalRow b = actual.mLines[actual.externalToInternalRow(y)];
            assertEquals(a.getSpaceUsed(), b.getSpaceUsed());
            assertEquals(new String(a.mText, 0, a.getSpaceUsed()), new String(b.mText, 0, b.getSpaceUsed()));
            assertTrue(Arrays.equals(a.mStyle, b.mStyle));
            assertEquals(a.mLineWrap, b.mLineWrap);
            assertEquals(a.mHasTerminalBitmap, b.mHasTerminalBitmap);
            assertEquals(a.mHasNonOneWidthOrSurrogateChars, b.mHasNonOneWidthOrSurrogateChars);
        }
    }

    private void scalarFill(TerminalBuffer b, int x, int y, int w, int h, int cp, long style) {
        for (int row = y; row < y + h; row++) {
            for (int col = x; col < x + w; col++) b.setChar(col, row, cp, style);
            if (x + w == b.mColumns && cp == ' ') b.clearLineWrap(row);
        }
    }

    public void testPartialAndFullFillsMatchScalarIncludingUnicodeAndImages() {
        for (boolean complex : new boolean[]{false, true}) {
            for (boolean images : new boolean[]{false, true}) {
                TerminalBuffer actual = seeded(20, 5, complex, images);
                TerminalBuffer expected = seeded(20, 5, complex, images);
                Random random = new Random(771);
                for (int i = 0; i < 300; i++) {
                    int x = random.nextInt(20), y = random.nextInt(5);
                    int w = random.nextInt(21 - x), h = random.nextInt(6 - y);
                    int cp = i % 3 == 0 ? ' ' : 'A' + i % 26;
                    long style = images && i % 11 == 0 ? TextStyle.encodeTerminalBitmap(2, 1, 1)
                        : TextStyle.encode(i % 16, (i / 16) % 16, i % 4);
                    scalarFill(expected, x, y, w, h, cp, style);
                    actual.blockSet(x, y, w, h, cp, style);
                    assertSameBuffer(expected, actual);
                }
                scalarFill(expected, 0, 0, 20, 5, ' ', TextStyle.NORMAL);
                actual.blockSet(0, 0, 20, 5, ' ', TextStyle.NORMAL);
                assertSameBuffer(expected, actual);
            }
        }
    }

    public void testSimpleCopiesMatchSnapshotWithStylesAndOverlap() {
        int[][] rectangles = {
            {0, 0, 20, 1, 0, 1}, {0, 0, 19, 1, 1, 0}, {1, 0, 19, 1, 0, 0},
            {2, 1, 15, 3, 4, 0}, {4, 0, 15, 3, 2, 1}, {0, 0, 20, 5, 0, 0},
            {20, 0, 0, 5, 20, 0}, {3, 2, 1, 1, 4, 2}
        };
        for (int[] r : rectangles) {
            TerminalBuffer actual = seeded(20, 5, false, false);
            TerminalBuffer expected = seeded(20, 5, false, false);
            char[][] chars = new char[5][];
            long[][] styles = new long[5][];
            for (int y = 0; y < 5; y++) {
                chars[y] = expected.mLines[y].mText.clone();
                styles[y] = expected.mLines[y].mStyle.clone();
            }
            for (int y = 0; y < r[3]; y++)
                for (int x = 0; x < r[2]; x++)
                    expected.setChar(r[4] + x, r[5] + y, chars[r[1] + y][r[0] + x], styles[r[1] + y][r[0] + x]);
            actual.blockCopy(r[0], r[1], r[2], r[3], r[4], r[5]);
            assertSameBuffer(expected, actual);
        }
    }

    public void testComplexAndBitmapSelfCopiesMatchUnmodifiedSourceStyles() {
        for (boolean complex : new boolean[]{false, true}) {
            for (boolean images : new boolean[]{false, true}) {
                for (int[] range : new int[][]{{0, 18, 2}, {2, 20, 0}}) {
                    TerminalBuffer actual = seeded(20, 2, complex, images);
                    TerminalBuffer expected = seeded(20, 2, complex, images);
                    TerminalBuffer snapshot = seeded(20, 2, complex, images);
                    actual.mLines[0].copyInterval(actual.mLines[0], range[0], range[1], range[2]);
                    expected.mLines[0].copyInterval(snapshot.mLines[0], range[0], range[1], range[2]);
                    assertSameBuffer(expected, actual);
                }
            }
        }
    }

    public void testCopyToComplexRowKeepsUntouchedCombiningAndImageCells() {
        TerminalBuffer actual = seeded(20, 2, true, true);
        TerminalBuffer expected = seeded(20, 2, true, true);
        actual.mLines[0].clear(TextStyle.NORMAL);
        expected.mLines[0].clear(TextStyle.NORMAL);
        for (int x = 0; x < 3; x++) {
            actual.setChar(x, 0, 'a' + x, x + 100);
            expected.setChar(x, 0, 'a' + x, x + 100);
            expected.setChar(x + 14, 1, 'a' + x, x + 100);
        }
        actual.blockCopy(0, 0, 3, 1, 14, 1);
        assertSameBuffer(expected, actual);
    }

    public void testZeroWidthFillStillClearsWrapAtRightEdge() {
        TerminalBuffer actual = seeded(20, 2, false, false);
        actual.blockSet(20, 0, 0, 1, ' ', TextStyle.NORMAL);
        assertFalse(actual.getLineWrap(0));
        assertTrue(actual.getLineWrap(1));
    }

    public void testFilledBitmapStylesRemainMarked() {
        TerminalBuffer actual = seeded(20, 2, false, false);
        long image = TextStyle.encodeTerminalBitmap(1, 0, 0);
        actual.blockSet(3, 1, 4, 1, ' ', image);
        assertTrue(actual.mLines[1].mHasTerminalBitmap);
        for (int x = 3; x < 7; x++) assertEquals(image, actual.getStyleAt(1, x));
    }

    public void testNonAsciiFillMatchesScalar() {
        for (int cp : new int[]{0x754c, 0x0301, 0x1f600, 0x00e9}) {
            TerminalBuffer actual = seeded(20, 2, false, false);
            TerminalBuffer expected = seeded(20, 2, false, false);
            scalarFill(expected, 2, 0, 6, 2, cp, TextStyle.NORMAL);
            actual.blockSet(2, 0, 6, 2, cp, TextStyle.NORMAL);
            assertSameBuffer(expected, actual);
        }
    }

    public void testBitmapClearAndConstructorKeepCopyGuardAccurate() {
        long image = TextStyle.encodeTerminalBitmap(1, 0, 0);
        TerminalRow source = new TerminalRow(20, image);
        assertTrue(source.mHasTerminalBitmap);
        TerminalRow destination = new TerminalRow(20, TextStyle.NORMAL);
        destination.copyInterval(source, 2, 6, 3);
        assertTrue(destination.mHasTerminalBitmap);
        for (int x = 3; x < 7; x++) assertEquals(image, destination.getStyle(x));
        source.clear(TextStyle.NORMAL);
        assertFalse(source.mHasTerminalBitmap);
        source.clear(image);
        assertTrue(source.mHasTerminalBitmap);
    }
}
