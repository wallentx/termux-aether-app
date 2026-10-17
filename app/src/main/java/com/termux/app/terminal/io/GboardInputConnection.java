package com.termux.app.terminal.io;

import android.annotation.TargetApi;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.TextAttribute;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Delivers committed drafts after IME edits settle, never intermediate composing updates. */
class GboardInputConnection extends InputConnectionWrapper {
    private final Consumer<Runnable> post;
    private final BooleanSupplier isCurrent;
    private final BooleanSupplier isComposing;
    private final Runnable onCommit;
    private int batchDepth;
    private boolean pending;
    private boolean scheduled;
    private boolean closed;

    static InputConnection wrap(InputConnection target, int sdk, Consumer<Runnable> post,
                                BooleanSupplier isCurrent, BooleanSupplier isComposing, Runnable onCommit) {
        if (target == null) return null;
        if (sdk >= 34) return new Api34(target, post, isCurrent, isComposing, onCommit);
        if (sdk >= 33) return new Api33(target, post, isCurrent, isComposing, onCommit);
        return new GboardInputConnection(target, post, isCurrent, isComposing, onCommit);
    }

    GboardInputConnection(InputConnection target, Consumer<Runnable> post,
                          BooleanSupplier isCurrent, BooleanSupplier isComposing, Runnable onCommit) {
        super(target, false);
        this.post = post;
        this.isCurrent = isCurrent;
        this.isComposing = isComposing;
        this.onCommit = onCommit;
    }

    final boolean active() { return !closed && isCurrent.getAsBoolean(); }

    final boolean committed(boolean accepted) {
        if (accepted) {
            pending = true;
            schedule();
        }
        return accepted;
    }

    final boolean composing(boolean accepted) {
        if (accepted) pending = false;
        return accepted;
    }

    private void schedule() {
        if (!active() || batchDepth != 0 || !pending || scheduled) return;
        scheduled = true;
        post.accept(() -> {
            scheduled = false;
            if (!active() || batchDepth != 0 || !pending || isComposing.getAsBoolean()) return;
            pending = false;
            onCommit.run();
        });
    }

    @Override public boolean commitText(CharSequence text, int cursor) {
        return active() && committed(super.commitText(text, cursor));
    }

    @Override public boolean finishComposingText() {
        return active() && committed(super.finishComposingText());
    }

    @Override public boolean setComposingText(CharSequence text, int cursor) {
        return active() && composing(super.setComposingText(text, cursor));
    }

    @Override public boolean setComposingRegion(int start, int end) {
        return active() && composing(super.setComposingRegion(start, end));
    }

    @Override public boolean beginBatchEdit() {
        if (!active() || !super.beginBatchEdit()) return false;
        ++batchDepth;
        return true;
    }

    @Override public boolean endBatchEdit() {
        if (!active()) return false;
        boolean result = super.endBatchEdit();
        if (batchDepth > 0) --batchDepth;
        schedule();
        return result;
    }

    @Override public void closeConnection() {
        closed = true;
        pending = false;
        super.closeConnection();
    }

    // New overloads forward directly to the wrapped target, bypassing the legacy overrides.
    @TargetApi(33)
    private static class Api33 extends GboardInputConnection {
        Api33(InputConnection target, Consumer<Runnable> post, BooleanSupplier isCurrent,
              BooleanSupplier isComposing, Runnable onCommit) {
            super(target, post, isCurrent, isComposing, onCommit);
        }

        @Override public boolean commitText(CharSequence text, int cursor, TextAttribute attributes) {
            return active() && committed(super.commitText(text, cursor, attributes));
        }

        @Override public boolean setComposingText(CharSequence text, int cursor, TextAttribute attributes) {
            return active() && composing(super.setComposingText(text, cursor, attributes));
        }

        @Override public boolean setComposingRegion(int start, int end, TextAttribute attributes) {
            return active() && composing(super.setComposingRegion(start, end, attributes));
        }
    }

    @TargetApi(34)
    private static final class Api34 extends Api33 {
        Api34(InputConnection target, Consumer<Runnable> post, BooleanSupplier isCurrent,
              BooleanSupplier isComposing, Runnable onCommit) {
            super(target, post, isCurrent, isComposing, onCommit);
        }

        @Override public boolean replaceText(int start, int end, CharSequence text, int cursor,
                                             TextAttribute attributes) {
            return active() && committed(super.replaceText(start, end, text, cursor, attributes));
        }
    }
}
