package com.termux.terminal;

import junit.framework.TestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** One-byte append calls keep exercising the original parser as the reference. */
public class AsciiBatchTest extends TestCase {
    private static final class Pair {
        final TerminalTestCase.MockTerminalOutput oldOutput = new TerminalTestCase.MockTerminalOutput();
        final TerminalTestCase.MockTerminalOutput newOutput = new TerminalTestCase.MockTerminalOutput();
        final TerminalEmulator reference, candidate;
        Pair(int columns) {
            reference = new TerminalEmulator(oldOutput, columns, 5, 10, 20, 30, null);
            candidate = new TerminalEmulator(newOutput, columns, 5, 10, 20, 30, null);
        }
        void append(byte[] bytes) throws Exception {
            for (byte b : bytes) reference.append(new byte[]{b}, 1);
            candidate.append(bytes, bytes.length);
            assertState();
        }
        void assertState() throws Exception {
            for (Field field : TerminalEmulator.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                assertEquals(field.getName(), field.get(reference), field.get(candidate));
            }
            assertEquals(reference.getTitle(), candidate.getTitle());
            assertEquals(oldOutput.titleChanges, newOutput.titleChanges);
            assertEquals(oldOutput.clipboardPuts, newOutput.clipboardPuts);
            assertEquals(oldOutput.bellsRung, newOutput.bellsRung);
            assertEquals(oldOutput.colorsChanged, newOutput.colorsChanged);
            assertTrue(Arrays.equals(oldOutput.baos.toByteArray(), newOutput.baos.toByteArray()));
            for (String name : new String[]{"mMainBuffer", "mAltBuffer"}) {
                Field field = TerminalEmulator.class.getDeclaredField(name);
                field.setAccessible(true);
                sameBuffer((TerminalBuffer) field.get(reference), (TerminalBuffer) field.get(candidate));
            }
        }
    }
    private static void sameBuffer(TerminalBuffer a, TerminalBuffer b) {
        assertEquals(a.getActiveTranscriptRows(), b.getActiveTranscriptRows());
        for (int y = -a.getActiveTranscriptRows(); y < a.mScreenRows; y++) {
            TerminalRow x = a.mLines[a.externalToInternalRow(y)], z = b.mLines[b.externalToInternalRow(y)];
            if (x == null || z == null) { assertEquals(x, z); continue; }
            assertEquals(x.getSpaceUsed(), z.getSpaceUsed());
            assertEquals(new String(x.mText, 0, x.getSpaceUsed()), new String(z.mText, 0, z.getSpaceUsed()));
            assertTrue(Arrays.equals(x.mStyle, z.mStyle));
            assertEquals(x.mLineWrap, z.mLineWrap);
            assertEquals(x.mHasTerminalBitmap, z.mHasTerminalBitmap);
            assertEquals(x.mHasNonOneWidthOrSurrogateChars, z.mHasNonOneWidthOrSurrogateChars);
        }
    }
    private void checkChunks(String text, int columns, int chunkSize) throws Exception {
        Pair pair = new Pair(columns);
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i += chunkSize)
            pair.append(Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + chunkSize)));
        // Probe delayed wrapping and REP's remembered last code point after the batch.
        pair.append("\033[3bZ\u0301\033[6n".getBytes(StandardCharsets.UTF_8));
    }
    public void testWrapStylesControlsAndHistoryAcrossChunkSizes() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 80; i++)
            text.append("abcdefghijklmnopqrstuvwxyz\033[31mRED text\033[0m\tXYZ\b!\r\n");
        for (int width : new int[]{1, 2, 7, 80})
            for (int chunk : new int[]{2, 3, 17, 4096}) checkChunks(text.toString(), width, chunk);
    }
    public void testTerminalModesAndEscapeStrings() throws Exception {
        String text = "normal ascii\033[4h\rINSERT TEXT\033[4l\033[?7lNO WRAP TEXT LONGER THAN ROW"
            + "\033[?7h\033(0lqqqqqqk\016qqqq\017\033(B\033)0\016lqqk\017\033)B\r\nback to ascii"
            + "\033[38;2;123;45;67;48;2;10;20;30mTRUECOLOR TEXT\033[0m"
            + "\033[?69h\033[3;12s\033[?6h\033[2;4rMARGIN TEXT AND SCROLLING\r\nnext line"
            + "\033[?6l\033[?69l\033[r\033[?1049hALT SCREEN TEXT\033[?1049lMAIN TEXT"
            + "\033]2;ASCII title\007after title\033Pignored ascii\033\\after DCS"
            + "\033[2J\033[H\0337SAVE\0338RESTORE\033[5b\033[6n";
        for (int chunk : new int[]{2, 5, 31, 4096}) checkChunks(text, 20, chunk);
    }
    public void testUnicodeAndMalformedUtf8AtEverySplit() throws Exception {
        byte[] bytes = "long ascii prefix cafe\u0301 \u754c \ud83d\ude42 ASCII suffix".getBytes(StandardCharsets.UTF_8);
        for (int split = 1; split < bytes.length; split++) {
            Pair pair = new Pair(20);
            pair.append(Arrays.copyOfRange(bytes, 0, split));
            pair.append(Arrays.copyOfRange(bytes, split, bytes.length));
            pair.append(new byte[]{(byte) 0xe2, (byte) 0x82});
            pair.append("printable recovery text".getBytes(StandardCharsets.UTF_8));
            pair.append(new byte[]{(byte) 0xc0, (byte) 0xaf, (byte) 0xff, 'A', 'B', (byte) 0x80});
        }
    }
    public void testRandomMixedStream() throws Exception {
        Random random = new Random(8841);
        String[] tokens = {"plain printable text ", "0123456789", "\r", "\n", "\t", "\b", "\u754c",
            "e\u0301", "\ud83d\ude42", "\033[2J", "\033[H", "\033[32m", "\033[0m", "\033[3b",
            "\033[4h", "\033[4l", "\033[?7h", "\033[?7l", "\033(B", "\033(0", "\007"};
        Pair pair = new Pair(23);
        for (int i = 0; i < 1000; i++) {
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < 1 + random.nextInt(8); j++) text.append(tokens[random.nextInt(tokens.length)]);
            pair.append(text.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
    public void testImageCellsForceSafeRowPath() throws Exception {
        Pair pair = new Pair(20);
        for (TerminalEmulator t : new TerminalEmulator[]{pair.reference, pair.candidate})
            t.getScreen().setChar(5, 0, ' ', TextStyle.encodeTerminalBitmap(2, 1, 1));
        pair.append("ABCD".getBytes(StandardCharsets.UTF_8));
        pair.append("overwrite image and wrap into a clean row".getBytes(StandardCharsets.UTF_8));
    }

    public void testAppendLengthAndEmptyInput() throws Exception {
        Pair pair = new Pair(20);
        byte[] bytes = "GOOD ignored suffix".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 4; i++) pair.reference.append(new byte[]{bytes[i]}, 1);
        pair.candidate.append(bytes, 4);
        pair.assertState();
        pair.append(new byte[0]);
    }
}
