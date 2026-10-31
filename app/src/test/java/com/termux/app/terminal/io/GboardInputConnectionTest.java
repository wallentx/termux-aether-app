package com.termux.app.terminal.io;

import android.app.Application;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.TextAttribute;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises IME edit sequences without starting a keyboard or recording audio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class GboardInputConnectionTest {

    @Test public void finalDictationAppearsOnceWithoutAnEditorSendAction() {
        Fixture fixture = new Fixture(28);
        fixture.connection.setComposingText("Turn le", 1);
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());

        assertTrue(fixture.connection.commitText("Turn left", 1));
        assertTrue(fixture.connection.finishComposingText());
        assertTrue(fixture.inserted.isEmpty());
        fixture.drain();

        assertEquals(Arrays.asList("Turn left"), fixture.inserted);
        assertEquals(1, fixture.notifications);
        assertEquals(0, fixture.editor.editorActions);
        assertFalse(fixture.connection.finishComposingText());
        assertFalse(fixture.connection.commitText(" late update", 1));
        fixture.drain();
        assertEquals(Arrays.asList("Turn left"), fixture.inserted);
        assertEquals(1, fixture.notifications);
    }

    @Test public void finishingCompositionPublishesTheCompletedDraft() {
        Fixture fixture = new Fixture(28);
        fixture.connection.setComposingText("hello", 1);
        fixture.connection.setComposingText("hello world", 1);
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());

        fixture.connection.finishComposingText();
        fixture.drain();

        assertEquals(Arrays.asList("hello world"), fixture.inserted);
    }

    @Test public void nestedBatchWaitsForAllFinalEditsEvenWhenOuterEndReturnsFalse() {
        Fixture fixture = new Fixture(28);
        fixture.connection.setComposingText("Take the left", 1);
        assertTrue(fixture.connection.beginBatchEdit());
        fixture.connection.commitText("Take the right", 1);
        assertTrue(fixture.connection.beginBatchEdit());
        fixture.connection.commitText(" exit", 1);
        fixture.connection.finishComposingText();
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());

        assertTrue(fixture.connection.endBatchEdit());
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());
        assertFalse(fixture.connection.endBatchEdit());
        fixture.drain();

        assertEquals(Arrays.asList("Take the right exit"), fixture.inserted);
        assertEquals(1, fixture.notifications);
    }

    @Test public void resumedCompositionDefersAPendingCommitUntilItFinishes() {
        Fixture fixture = new Fixture(28);
        fixture.connection.commitText("take the left", 1);
        fixture.connection.setComposingRegion(9, 13);
        fixture.connection.setComposingText("right", 1);
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());
        assertEquals(0, fixture.notifications);

        fixture.connection.finishComposingText();
        fixture.drain();
        assertEquals(Arrays.asList("take the right"), fixture.inserted);
    }

    @Test public void composingStateIsCheckedWhenTheQueuedCommitIsDelivered() {
        Fixture fixture = new Fixture(28);
        fixture.connection.commitText("unfinished", 1);
        // The editor can acquire composing spans independently of this connection.
        fixture.editor.composingStart = 0;
        fixture.editor.composingEnd = fixture.editor.text.length();
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());
        assertEquals(0, fixture.notifications);

        fixture.connection.finishComposingText();
        fixture.drain();
        assertEquals(Arrays.asList("unfinished"), fixture.inserted);
    }

    @Test public void canceledDraftDiscardsTheQueuedCommitAndRejectsLateImeEdits() {
        Fixture fixture = new Fixture(28);
        fixture.connection.commitText("canceled", 1);
        fixture.active = false;
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());
        assertEquals(0, fixture.notifications);
        assertFalse(fixture.connection.commitText(" late edit", 1));
        assertEquals("canceled", fixture.editor.text.toString());
    }

    @Test public void closingTheConnectionDiscardsTheQueuedCommit() {
        Fixture fixture = new Fixture(28);
        fixture.connection.commitText("closed", 1);
        fixture.connection.closeConnection();
        fixture.drain();

        assertTrue(fixture.editor.closed);
        assertTrue(fixture.inserted.isEmpty());
        assertEquals(0, fixture.notifications);
        assertFalse(fixture.connection.commitText(" late edit", 1));
        assertEquals("closed", fixture.editor.text.toString());
    }

    @Test public void explicitSendBeforeQueuedAutoInsertionDoesNotDuplicateTheDraft() {
        Fixture fixture = new Fixture(28);
        fixture.connection.commitText("send once", 1);
        // The existing Send fallback inserts the draft and cancels its capture session.
        fixture.inserted.add(VoiceInputText.forTerminal(fixture.editor.text.toString()));
        fixture.active = false;
        fixture.drain();

        assertEquals(Arrays.asList("send once"), fixture.inserted);
        assertEquals(0, fixture.notifications);
        assertFalse(fixture.connection.finishComposingText());
    }

    @Test public void failedImeEditsDoNotPublishTheExistingDraft() {
        Fixture fixture = new Fixture(28);
        fixture.connection.setComposingText("still composing", 1);
        fixture.editor.acceptEdits = false;

        assertFalse(fixture.connection.commitText("rejected", 1));
        assertFalse(fixture.connection.finishComposingText());
        fixture.drain();

        assertEquals("still composing", fixture.editor.text.toString());
        assertTrue(fixture.inserted.isEmpty());
        assertEquals(0, fixture.notifications);
    }

    @Test public void emptyDraftDoesNotInsertOrSubmitAnything() {
        Fixture fixture = new Fixture(28);
        fixture.connection.finishComposingText();
        fixture.connection.commitText("", 1);
        fixture.drain();

        assertTrue(fixture.inserted.isEmpty());
        assertEquals(0, fixture.editor.editorActions);
    }

    @Test public void terminalInsertionPreservesUnicodeWithoutSendingAnEnter() {
        Fixture fixture = new Fixture(28);
        fixture.connection.commitText("Hello 🌍\nnext line\u001b", 1);
        fixture.drain();

        assertEquals(Arrays.asList("Hello 🌍 next line"), fixture.inserted);
        assertEquals(0, fixture.editor.editorActions);
    }

    @Test public void api33CommitWithTextAttributesPublishesWithoutSend() {
        Fixture fixture = new Fixture(33);
        fixture.connection.setComposingText("dicta", 1, null);
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());

        assertTrue(fixture.connection.commitText("dictation complete", 1, null));
        fixture.drain();

        assertEquals(Arrays.asList("dictation complete"), fixture.inserted);
        assertEquals(1, fixture.notifications);
        assertEquals(0, fixture.editor.editorActions);
    }

    @Test public void api33ComposingOverloadsInvalidatePendingCommits() {
        Fixture fixture = new Fixture(33);
        fixture.connection.commitText("wrong", 1, null);
        fixture.connection.setComposingRegion(0, 5, null);
        fixture.connection.setComposingText("correct", 1, null);
        fixture.drain();
        assertTrue(fixture.inserted.isEmpty());
        assertEquals(0, fixture.notifications);

        fixture.connection.finishComposingText();
        fixture.drain();
        assertEquals(Arrays.asList("correct"), fixture.inserted);
    }

    @Test public void api34ReplaceTextPublishesTheFinalReplacement() throws Exception {
        Method replaceText;
        try {
            replaceText = InputConnection.class.getMethod("replaceText", int.class, int.class,
                CharSequence.class, int.class, TextAttribute.class);
        } catch (NoSuchMethodException unavailable) {
            // Robolectric 4.10 supports API 33; the Android 14 host run covers this overload.
            Assume.assumeNoException(unavailable);
            return;
        }

        Fixture fixture = new Fixture(34);
        fixture.connection.setComposingText("turn left", 1, null);
        assertEquals(true, replaceText.invoke(fixture.connection, 0, 9, "turn right", 1, null));
        fixture.drain();

        assertEquals(Arrays.asList("turn right"), fixture.inserted);
        assertEquals(1, fixture.notifications);
        assertEquals(0, fixture.editor.editorActions);
    }

    private static final class Fixture {
        final Editor editor = new Editor();
        final ArrayDeque<Runnable> posted = new ArrayDeque<>();
        final List<String> inserted = new ArrayList<>();
        final InputConnection connection;
        boolean active = true;
        int notifications;

        Fixture(int sdk) {
            InputConnection target = (InputConnection) Proxy.newProxyInstance(
                InputConnection.class.getClassLoader(), new Class<?>[] {InputConnection.class}, editor);
            connection = GboardInputConnection.wrap(target, sdk, posted::addLast,
                () -> active, () -> editor.composingStart >= 0, () -> {
                    notifications++;
                    String text = VoiceInputText.forTerminal(editor.text.toString());
                    if (active && !text.isEmpty()) {
                        inserted.add(text);
                        active = false;
                    }
                });
        }

        void drain() {
            while (!posted.isEmpty()) posted.removeFirst().run();
        }
    }

    /** A small editable target; the real Android wrapper handles method dispatch. */
    private static final class Editor implements InvocationHandler {
        final StringBuilder text = new StringBuilder();
        int cursor;
        int composingStart = -1;
        int composingEnd = -1;
        int batchDepth;
        int editorActions;
        boolean acceptEdits = true;
        boolean closed;

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "beginBatchEdit":
                    batchDepth++;
                    return true;
                case "endBatchEdit":
                    if (batchDepth > 0) batchDepth--;
                    return batchDepth > 0;
                case "closeConnection":
                    closed = true;
                    return null;
                case "performEditorAction":
                    editorActions++;
                    return true;
                case "setComposingRegion":
                    if (!acceptEdits) return false;
                    composingStart = (Integer) args[0];
                    composingEnd = (Integer) args[1];
                    return true;
                case "setComposingText":
                case "commitText":
                    if (!acceptEdits) return false;
                    int start = composingStart >= 0 ? composingStart : cursor;
                    int end = composingStart >= 0 ? composingEnd : cursor;
                    replace(start, end, (CharSequence) args[0]);
                    if (method.getName().equals("setComposingText")) {
                        composingStart = start;
                        composingEnd = cursor;
                    }
                    return true;
                case "finishComposingText":
                    if (!acceptEdits) return false;
                    composingStart = composingEnd = -1;
                    return true;
                case "replaceText":
                    if (!acceptEdits) return false;
                    replace((Integer) args[0], (Integer) args[1], (CharSequence) args[2]);
                    return true;
                default:
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
            }
        }

        private void replace(int start, int end, CharSequence replacement) {
            text.replace(start, end, replacement.toString());
            cursor = start + replacement.length();
            composingStart = composingEnd = -1;
        }
    }
}
