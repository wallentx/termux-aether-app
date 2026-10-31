package com.termux.view;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import com.termux.app.terminal.TerminalUrlHighlights;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TerminalRendererBitmapTest {
    private static final TerminalOutput OUTPUT = new TerminalOutput() {
        public void write(byte[] data, int offset, int count) {}
        public void titleChanged(String oldTitle, String newTitle) {}
        public void onCopyTextToClipboard(String text) {}
        public void onPasteTextFromClipboard() {}
        public void onBell() {}
        public void onColorsChanged() {}
    };
    private static class RecordingCanvas extends Canvas {
        final List<Rect> sources = new ArrayList<>();
        final List<RectF> destinations = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        final List<String> textRuns = new ArrayList<>();
        final List<Boolean> underlinedRuns = new ArrayList<>();
        @Override public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
            sources.add(new Rect(src)); destinations.add(new RectF(dst));
        }
        @Override public void drawTextRun(char[] value, int index, int count, int contextIndex,
                int contextCount, float x, float y, boolean rtl, Paint paint) {
            assertTrue("Nonempty text runs only", count > 0);
            text.append(value, index, count);
            textRuns.add(new String(value, index, count));
            underlinedRuns.add(paint.isUnderlineText());
        }
    }
    private TerminalEmulator terminal() {
        TerminalEmulator t = new TerminalEmulator(OUTPUT, 8, 4, 2, 3, 20, null);
        byte[] text = "\033[?25lABCDEFGH".getBytes(StandardCharsets.UTF_8);
        t.append(text, text.length);
        return t;
    }
    private void image(TerminalBuffer b, int x, int y, int width) {
        b.sixelStart(width, 6);
        b.sixelSetRGBColor(1, 100, 0, 0);
        assertTrue(b.sixelReadData('~', width));
        b.sixelEnd(x, y, 2, 3);
    }
    private RecordingCanvas render(TerminalEmulator t) {
        RecordingCanvas c = new RecordingCanvas();
        new TerminalRenderer(16, Typeface.MONOSPACE).render(t, c, 0, -1, -1, -1, -1);
        return c;
    }
    @Test public void controlLinkMaskUnderlinesOnlyUrlText() {
        TerminalEmulator t = new TerminalEmulator(OUTPUT, 24, 4, 2, 3, 20, null);
        byte[] bytes = "plain https://a.io end".getBytes(StandardCharsets.UTF_8);
        t.append(bytes, bytes.length);
        boolean[][] mask = TerminalUrlHighlights.find(t.getScreen(), 0, t.mRows, t.mColumns);
        RecordingCanvas normal = render(t);
        for (boolean underlined : normal.underlinedRuns) assertFalse(underlined);
        RecordingCanvas c = new RecordingCanvas();
        new TerminalRenderer(16, Typeface.MONOSPACE).render(t, c, 0, -1, -1, -1, -1, mask);

        StringBuilder underlined = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < c.textRuns.size(); i++)
            (c.underlinedRuns.get(i) ? underlined : plain).append(c.textRuns.get(i));
        assertTrue(underlined.toString().contains("https://a.io"));
        assertTrue(plain.toString().contains("plain"));
        assertTrue(plain.toString().contains(" end"));
    }
    @Test public void batchesOneDrawPerImageRowAndPreservesAdjacentText() {
        TerminalEmulator t = terminal();
        image(t.getScreen(), 2, 0, 8);
        RecordingCanvas c = render(t);
        assertEquals(2, c.sources.size());
        assertEquals(new Rect(0, 0, 8, 3), c.sources.get(0));
        assertEquals(new Rect(0, 3, 8, 6), c.sources.get(1));
        assertTrue(c.text.toString().startsWith("ABGH"));
        float width = new TerminalRenderer(16, Typeface.MONOSPACE).getFontWidth();
        assertEquals(2 * width, c.destinations.get(0).left, .001f);
        assertEquals(6 * width, c.destinations.get(0).right, .001f);
    }
    @Test public void breaksRunsAtTextHolesAndNoncontiguousImageCoordinates() {
        TerminalEmulator t = terminal();
        TerminalBuffer b = t.getScreen();
        image(b, 0, 0, 16);
        TerminalRow row = b.allocateFullLineIfNecessary(b.externalToInternalRow(0));
        long style = row.getStyle(0);
        int id = TextStyle.getTerminalBitmapNum(style);
        // Reorder one slice; it must not be stretched together with its neighbors.
        row.setChar(2, ' ', TextStyle.encodeTerminalBitmap(id, 5, 0));
        row.setChar(5, 'X', 0);
        RecordingCanvas c = render(t);
        assertEquals(5, c.sources.size()); // Four split runs plus the intact second row.
        assertEquals(new Rect(0, 0, 4, 3), c.sources.get(0));
        assertEquals(new Rect(10, 0, 12, 3), c.sources.get(1));
        assertEquals(new Rect(6, 0, 10, 3), c.sources.get(2));
        assertEquals(new Rect(12, 0, 16, 3), c.sources.get(3));
        assertTrue(c.text.toString().contains("X"));
    }
    @Test public void insertedTextShiftsImageSlicesWithoutSmearingAcrossRow() {
        TerminalEmulator t = terminal();
        TerminalBuffer b = t.getScreen();
        image(b, 0, 0, 8);
        byte[] edit = "\033[1;3H\033[@X".getBytes(StandardCharsets.UTF_8);
        t.append(edit, edit.length);
        RecordingCanvas c = render(t);
        assertEquals(3, c.sources.size());
        assertEquals(new Rect(0, 0, 4, 3), c.sources.get(0));
        assertEquals(new Rect(4, 0, 8, 3), c.sources.get(1));
        float width = new TerminalRenderer(16, Typeface.MONOSPACE).getFontWidth();
        assertEquals(5 * width, c.destinations.get(1).right, .001f);
        assertTrue(c.text.toString().contains("X"));
    }

    @Test public void neighboringImagesRemainSeparateAndMissingBitmapsAreSkipped() {
        TerminalEmulator t = terminal();
        TerminalBuffer b = t.getScreen();
        image(b, 0, 0, 8);
        image(b, 4, 0, 8);
        assertEquals(4, render(t).sources.size());
        b.clearTerminalBitmaps();
        assertEquals(0, render(t).sources.size());
    }
    @Test public void unicodeBeforeAndAfterImageKeepsCorrectCharacterOffsets() {
        TerminalEmulator t = terminal();
        byte[] text = "\r\u754cXXe\u0301XYZ".getBytes(StandardCharsets.UTF_8);
        t.append(text, text.length);
        image(t.getScreen(), 2, 0, 4);
        RecordingCanvas c = render(t);
        assertTrue(c.text.toString().contains("\u754c"));
        assertTrue(c.text.toString().contains("e\u0301XYZ"));
        assertEquals(2, c.sources.size());
    }
}
