package com.termux.app.terminal;

import com.termux.shared.termux.data.TermuxUrlUtils;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TextStyle;
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
        while (firstRow > earliestRow && rowsMayContinue(screen, firstRow - 1, firstRow, columns)) firstRow--;
        int lastRow = topRow + rows - 1;
        int latestRow = Math.min(screen.getActiveRows() - screen.getActiveTranscriptRows() - 1,
            lastRow + MAX_LINK_CONTINUATION_ROWS);
        while (lastRow < latestRow && rowsMayContinue(screen, lastRow, lastRow + 1, columns)) lastRow++;

        StringBuilder text = new StringBuilder();
        List<RowPart> parts = new ArrayList<>();
        String previousRowText = null;
        for (int row = firstRow; row <= lastRow; row++) {
            String fullRowText = screen.getSelectedText(0, row, columns - 1, row);
            String rowText = fullRowText;
            int columnOffset = 0;
            if (previousRowText != null && !continuesOnNextRow(screen, row - 1, columns, previousRowText)) {
                int indent = styledContinuationIndent(screen, row - 1, previousRowText, row, rowText);
                if (indent >= 0 && endsInUrl(text)) {
                    columnOffset = displayColumns(rowText, indent);
                    rowText = rowText.substring(indent);
                } else {
                    text.append('\n');
                }
            }
            parts.add(new RowPart(row, text.length(), rowText, columnOffset));
            text.append(rowText);
            previousRowText = fullRowText;
        }
        markLinks(text, parts, result, topRow, columns, targetRow, targetColumn);
        return result;
    }

    private static boolean rowsMayContinue(TerminalBuffer screen, int row, int nextRow, int columns) {
        String text = screen.getSelectedText(0, row, columns - 1, row);
        if (continuesOnNextRow(screen, row, columns, text)) return true;
        String nextText = screen.getSelectedText(0, nextRow, columns - 1, nextRow);
        return styledContinuationIndent(screen, row, text, nextRow, nextText) >= 0;
    }

    private static boolean continuesOnNextRow(TerminalBuffer screen, int row, int columns, String text) {
        return screen.getLineWrap(row) || displayColumns(text, text.length()) == columns;
    }

    private static int styledContinuationIndent(TerminalBuffer screen, int row, String text,
                                                int nextRow, String nextText) {
        if (text.isEmpty() || nextText.isEmpty()) return -1;
        int indent = 0;
        while (indent < nextText.length() && nextText.charAt(indent) == ' ') indent++;
        if (indent == nextText.length()) return -1;

        int lastColumn = displayColumns(text, text.length()) - 1;
        if (lastColumn < 0) return -1;
        long lastStyle = screen.getStyleAt(row, lastColumn);
        long nextStyle = screen.getStyleAt(nextRow, displayColumns(nextText, indent));
        int underline = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
        if (TextStyle.isTerminalBitmap(lastStyle) || TextStyle.isTerminalBitmap(nextStyle) ||
            (TextStyle.decodeEffect(lastStyle) & underline) == 0 ||
            (TextStyle.decodeEffect(nextStyle) & underline) == 0 ||
            TextStyle.decodeForeColor(lastStyle) != TextStyle.decodeForeColor(nextStyle)) return -1;
        return indent;
    }

    private static boolean endsInUrl(CharSequence text) {
        Matcher matcher = TermuxUrlUtils.getUrlMatchRegex().matcher(text);
        while (matcher.find()) {
            if (trimUnmatchedClosingParentheses(text, matcher.start(1), matcher.end()) == text.length()) return true;
        }
        return false;
    }

    private static int trimUnmatchedClosingParentheses(CharSequence text, int start, int end) {
        int balance = 0;
        for (int offset = start; offset < end; offset++) {
            char c = text.charAt(offset);
            if (c == '(') balance++;
            else if (c == ')') balance--;
        }
        while (balance < 0 && end > start && text.charAt(end - 1) == ')') {
            end--;
            balance++;
        }
        return end;
    }

    private static void markLinks(CharSequence text, List<RowPart> parts, ScanResult result,
                                  int topRow, int columns, int targetRow, int targetColumn) {
        if (text.length() == 0) return;
        Matcher matcher = TermuxUrlUtils.getUrlMatchRegex().matcher(text);
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = trimUnmatchedClosingParentheses(text, start, matcher.end());
            for (RowPart part : parts) {
                int first = Math.max(start, part.start);
                int last = Math.min(end, part.start + part.text.length());
                if (first >= last || part.row < topRow || part.row >= topRow + result.mask.length) continue;

                int startColumn = Math.min(columns, part.columnOffset + displayColumns(part.text, first - part.start));
                int endColumn = Math.min(columns, part.columnOffset + displayColumns(part.text, last - part.start));
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
        final int columnOffset;

        RowPart(int row, int start, String text, int columnOffset) {
            this.row = row;
            this.start = start;
            this.text = text;
            this.columnOffset = columnOffset;
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
