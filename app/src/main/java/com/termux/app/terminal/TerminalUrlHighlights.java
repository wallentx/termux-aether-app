package com.termux.app.terminal;

import com.termux.shared.termux.data.TermuxUrlUtils;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/** Finds visible URL cells without changing terminal content or text styles. */
public final class TerminalUrlHighlights {

    private static final int MAX_LINK_CONTINUATION_ROWS = 16;

    private TerminalUrlHighlights() {}

    public static boolean[][] find(TerminalBuffer screen, int topRow, int rows, int columns) {
        ScanResult result = scan(screen, topRow, rows, columns, -1, -1);
        return result.found ? result.mask : null;
    }

    public static String findUrlAt(TerminalBuffer screen, int topRow, int rows, int columns,
                                   int targetRow, int targetColumn) {
        return scan(screen, topRow, rows, columns, targetRow, targetColumn).url;
    }

    private static ScanResult scan(TerminalBuffer screen, int topRow, int rows, int columns,
                                   int targetRow, int targetColumn) {
        if (screen == null || rows <= 0 || columns <= 0) return new ScanResult(null);

        ScanResult result = new ScanResult(new boolean[rows][columns]);
        int firstRow = topRow;
        int earliestRow = Math.max(-screen.getActiveTranscriptRows(), topRow - MAX_LINK_CONTINUATION_ROWS);
        while (firstRow > earliestRow && continuesOnNextRow(screen, firstRow - 1, columns)) firstRow--;
        int lastRow = topRow + rows - 1;
        int latestRow = Math.min(screen.getActiveRows() - screen.getActiveTranscriptRows() - 1,
            lastRow + MAX_LINK_CONTINUATION_ROWS);
        while (lastRow < latestRow && continuesOnNextRow(screen, lastRow, columns)) lastRow++;

        StringBuilder text = new StringBuilder();
        List<RowPart> parts = new ArrayList<>();
        for (int row = firstRow; row <= lastRow; row++) {
            String rowText = screen.getSelectedText(0, row, columns - 1, row);
            parts.add(new RowPart(row, text.length(), rowText));
            text.append(rowText);
            if (!continuesOnNextRow(screen, row, columns, rowText)) text.append('\n');
        }
        markLinks(text, parts, result, topRow, columns, targetRow, targetColumn);
        return result;
    }

    private static boolean continuesOnNextRow(TerminalBuffer screen, int row, int columns) {
        String text = screen.getSelectedText(0, row, columns - 1, row);
        return continuesOnNextRow(screen, row, columns, text);
    }

    private static boolean continuesOnNextRow(TerminalBuffer screen, int row, int columns, String text) {
        return screen.getLineWrap(row) || displayColumns(text, text.length()) == columns;
    }

    private static void markLinks(CharSequence text, List<RowPart> parts, ScanResult result,
                                  int topRow, int columns, int targetRow, int targetColumn) {
        if (text.length() == 0) return;
        Matcher matcher = TermuxUrlUtils.getUrlMatchRegex().matcher(text);
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end();
            for (RowPart part : parts) {
                int first = Math.max(start, part.start);
                int last = Math.min(end, part.start + part.text.length());
                if (first >= last || part.row < topRow || part.row >= topRow + result.mask.length) continue;

                int startColumn = Math.min(columns, displayColumns(part.text, first - part.start));
                int endColumn = Math.min(columns, displayColumns(part.text, last - part.start));
                for (int column = startColumn; column < endColumn; column++) {
                    result.mask[part.row - topRow][column] = true;
                    result.found = true;
                }
                if (part.row == targetRow && targetColumn >= startColumn && targetColumn < endColumn)
                    result.url = text.subSequence(start, end).toString();
            }
        }
    }

    private static int displayColumns(String text, int end) {
        int columns = 0;
        for (int offset = 0; offset < end;) {
            int codePoint = text.codePointAt(offset);
            columns += Math.max(0, WcWidth.width(codePoint));
            offset += Character.charCount(codePoint);
        }
        return columns;
    }

    private static final class RowPart {
        final int row;
        final int start;
        final String text;

        RowPart(int row, int start, String text) {
            this.row = row;
            this.start = start;
            this.text = text;
        }
    }

    private static final class ScanResult {
        final boolean[][] mask;
        boolean found;
        String url;

        ScanResult(boolean[][] mask) {
            this.mask = mask;
        }
    }
}
