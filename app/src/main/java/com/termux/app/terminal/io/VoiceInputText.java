package com.termux.app.terminal.io;

/** Removes terminal control characters from text returned by a speech recognition app. */
public final class VoiceInputText {

    private VoiceInputText() {}

    public static String forTerminal(String text) {
        if (text == null) return "";

        StringBuilder result = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            if (codePoint == '\r' || codePoint == '\n' || codePoint == '\t') {
                if (result.length() == 0 || result.charAt(result.length() - 1) != ' ')
                    result.append(' ');
            } else if (!Character.isISOControl(codePoint)) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }
}
