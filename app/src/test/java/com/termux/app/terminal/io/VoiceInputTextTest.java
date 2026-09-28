package com.termux.app.terminal.io;

import org.junit.Assert;
import org.junit.Test;

public class VoiceInputTextTest {

    @Test
    public void controlCharactersCannotExecuteOrReprogramTheTerminal() {
        Assert.assertEquals("first second [31m", VoiceInputText.forTerminal("first\r\nsecond\u001b[31m\u0000"));
    }

    @Test
    public void unicodeTextIsPreservedWithoutSubmittingIt() {
        Assert.assertEquals("Hello 🌍", VoiceInputText.forTerminal("Hello 🌍"));
        Assert.assertEquals("", VoiceInputText.forTerminal(null));
    }
}
