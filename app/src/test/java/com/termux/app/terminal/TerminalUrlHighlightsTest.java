package com.termux.app.terminal;

import android.app.Application;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TerminalUrlHighlightsTest {

    @Test public void underlinesBothRowsOfWrappedUrl() {
        TerminalBuffer screen = new TerminalBuffer(8, 8, 4);
        write(screen, 0, "https://");
        write(screen, 1, "example.");
        write(screen, 2, "com");
        screen.setLineWrap(0);
        screen.setLineWrap(1);

        boolean[][] mask = TerminalUrlHighlights.find(screen, 0, 4, 8);
        for (int column = 0; column < 8; column++) {
            assertTrue(mask[0][column]);
            assertTrue(mask[1][column]);
        }
        for (int column = 0; column < 8; column++)
            assertTrue(column < 3 ? mask[2][column] : !mask[2][column]);
        for (boolean underlined : mask[3]) assertFalse(underlined);
        assertEquals("https://example.com", TerminalUrlHighlights.findUrlAt(screen, 0, 4, 8, 2, 1));
        assertNull(TerminalUrlHighlights.findUrlAt(screen, 0, 4, 8, 3, 0));
        assertEquals("https://example.com", TerminalUrlHighlights.findUrlAt(screen, 0, 2, 8, 1, 3));
    }

    @Test public void fullWidthScreenAppRowsAreJoined() {
        TerminalBuffer screen = new TerminalBuffer(8, 8, 4);
        write(screen, 0, "https://");
        write(screen, 1, "a.io");

        boolean[][] mask = TerminalUrlHighlights.find(screen, 0, 4, 8);
        assertTrue(mask[0][0]);
        assertTrue(mask[0][7]);
        assertTrue(mask[1][0]);
        assertTrue(mask[1][3]);
        assertFalse(mask[1][4]);
    }

    @Test public void styledIndentedLinkFromCodexDisplayOpensWholeUrl() {
        TerminalBuffer screen = new TerminalBuffer(60, 8, 4);
        String first = "pushed to app (https://github.com/wallentx/";
        String second = "  termux-aether-app/commit/800df096)";
        String url = "https://github.com/wallentx/termux-aether-app/commit/800df096";
        write(screen, 0, first);
        write(screen, 1, second);
        underline(screen, 0, first.indexOf("https://"), first.length());
        underline(screen, 1, 2, second.length() - 1);

        boolean[][] mask = TerminalUrlHighlights.find(screen, 0, 4, 60);
        assertTrue(mask[0][first.indexOf("https://")]);
        assertTrue(mask[0][first.length() - 1]);
        assertFalse(mask[1][0]);
        assertFalse(mask[1][1]);
        assertTrue(mask[1][2]);
        assertTrue(mask[1][second.length() - 2]);
        assertFalse(mask[1][second.length() - 1]);
        assertEquals(url, TerminalUrlHighlights.findUrlAt(screen, 0, 4, 60, 0, first.length() - 1));
        assertEquals(url, TerminalUrlHighlights.findUrlAt(screen, 0, 4, 60, 1, 2));
        assertEquals(url, TerminalUrlHighlights.findUrlAt(screen, 1, 2, 60, 1, 2));
        assertNull(TerminalUrlHighlights.findUrlAt(screen, 0, 4, 60, 1, second.length() - 1));
    }

    @Test public void unrelatedIndentedLineDoesNotExtendUrl() {
        TerminalBuffer screen = new TerminalBuffer(40, 8, 4);
        write(screen, 0, "https://a.io/");
        write(screen, 1, "  next item");

        assertEquals("https://a.io/", TerminalUrlHighlights.findUrlAt(screen, 0, 4, 40, 0, 1));
        assertNull(TerminalUrlHighlights.findUrlAt(screen, 0, 4, 40, 1, 2));
        boolean[][] mask = TerminalUrlHighlights.find(screen, 0, 4, 40);
        assertFalse(mask[1][2]);
    }

    @Test public void balancedUrlParenthesesRemainPartOfLink() {
        TerminalBuffer screen = new TerminalBuffer(40, 8, 4);
        write(screen, 0, "https://example.com/a(b)");

        assertEquals("https://example.com/a(b)",
            TerminalUrlHighlights.findUrlAt(screen, 0, 4, 40, 0, 23));
    }

    @Test public void ignoresSpacesAndAccountsForWideCharacters() {
        TerminalBuffer screen = new TerminalBuffer(40, 8, 4);
        String text = "界 https://a.io and https://b.io";
        write(screen, 0, text);

        boolean[][] mask = TerminalUrlHighlights.find(screen, 0, 4, 40);
        assertFalse(mask[0][0]);
        assertFalse(mask[0][1]);
        assertFalse(mask[0][2]);
        for (int column = 3; column < 3 + "https://a.io".length(); column++) assertTrue(mask[0][column]);
        assertFalse(mask[0][3 + "https://a.io".length()]);
        int second = text.indexOf("https://b.io") + 1; // The wide prefix occupies one extra cell.
        for (int column = second; column < second + "https://b.io".length(); column++) assertTrue(mask[0][column]);
        assertFalse(mask[0][second - 1]);
        assertNull(TerminalUrlHighlights.findUrlAt(screen, 0, 4, 40, 0, 1));
        assertEquals("https://a.io", TerminalUrlHighlights.findUrlAt(screen, 0, 4, 40, 0, 3));
        assertEquals("https://b.io", TerminalUrlHighlights.findUrlAt(screen, 0, 4, 40, 0, second));
    }

    private static void write(TerminalBuffer screen, int row, String text) {
        int column = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            screen.setChar(column, row, codePoint, screen.getStyleAt(row, column));
            column += Math.max(0, WcWidth.width(codePoint));
            offset += Character.charCount(codePoint);
        }
    }

    private static void underline(TerminalBuffer screen, int row, int start, int end) {
        screen.setOrClearEffect(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE,
            true, false, true, 0, 60, row, start, row + 1, end);
    }
}
