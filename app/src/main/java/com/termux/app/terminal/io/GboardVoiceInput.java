package com.termux.app.terminal.io;

import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.session.SessionManager;
import com.termux.terminal.TerminalSession;

/** A real IME editor kept out of the toolbar; committed dictation transfers to the terminal. */
public final class GboardVoiceInput {
    private final TermuxActivity activity;
    private EditText editor;
    private String sessionHandle;
    private long generation;
    private long connectionGeneration;
    private java.util.concurrent.atomic.AtomicBoolean dictationAllowed;

    public GboardVoiceInput(TermuxActivity activity) { this.activity = activity; }
    public boolean isActive() { return editor != null; }

    public void toggle() {
        if (isActive()) { cancel(); return; }
        TerminalSession session = activity.getCurrentSession();
        if (session == null || !session.isRunning()) {
            toast(R.string.msg_voice_input_no_session);
            return;
        }
        sessionHandle = session.mHandle;
        final long attempt = ++generation;
        final java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        dictationAllowed = allowed;
        editor = new EditText(activity) {
            @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                InputConnection target = super.onCreateInputConnection(outAttrs);
                final long connection = ++connectionGeneration;
                return GboardInputConnection.wrap(target, Build.VERSION.SDK_INT, this::post,
                    () -> editor == this && generation == attempt && connectionGeneration == connection
                        && hasFocus() && activity.hasWindowFocus(),
                    () -> BaseInputConnection.getComposingSpanStart(getText()) != -1
                        || BaseInputConnection.getComposingSpanEnd(getText()) != -1,
                    () -> submit(attempt, this));
            }

            @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) cancel();
                    return true;
                }
                return super.onKeyPreIme(keyCode, event);
            }
        };
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setSingleLine(true);
        editor.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setContentDescription(activity.getString(R.string.gboard_voice_draft));
        editor.setSaveEnabled(false);
        editor.setAlpha(0f);
        FrameLayout content = activity.findViewById(android.R.id.content);
        content.addView(editor, new FrameLayout.LayoutParams(1, 1, Gravity.BOTTOM | Gravity.START));
        editor.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEND
                && (event == null || event.getKeyCode() != KeyEvent.KEYCODE_ENTER
                    || event.getAction() != KeyEvent.ACTION_DOWN)) return false;
            submit(attempt, (EditText) view);
            return true;
        });
        editor.setOnFocusChangeListener((view, focused) -> {
            if (!focused && editor == view) cancel();
        });
        activity.getTerminalToolbarViewPager().setCurrentItem(0, false);
        editor.requestFocus();
        InputMethodManager ime = (InputMethodManager) activity.getSystemService(TermuxActivity.INPUT_METHOD_SERVICE);
        ime.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        toast(R.string.gboard_voice_instructions);
        long deadline = SystemClock.uptimeMillis() + 2500;
        editor.postDelayed(() -> awaitKeyboard(attempt, deadline), 350);
    }

    private boolean isCurrent(long attempt) {
        TerminalSession session = activity.getCurrentSession();
        return editor != null && generation == attempt && editor.hasFocus() && activity.hasWindowFocus()
            && session != null && session.isRunning() && session.mHandle.equals(sessionHandle);
    }

    private void submit(long attempt, EditText source) {
        if (editor != source || generation != attempt || !source.hasFocus() || !activity.hasWindowFocus()) return;
        TerminalSession current = activity.getCurrentSession();
        if (current == null || !current.isRunning() || !current.mHandle.equals(sessionHandle)) {
            cancel();
            toast(R.string.msg_voice_input_session_changed);
            return;
        }
        String text = VoiceInputText.forTerminal(source.getText().toString());
        if (text.isEmpty()) return; // IMEs also finish composition during setup and teardown.
        cancel();
        current.write(text);
    }

    private void awaitKeyboard(long attempt, long deadline) {
        if (!isCurrent(attempt)) return;
        WindowInsets insets = editor.getRootWindowInsets();
        if (Build.VERSION.SDK_INT < 30 || insets == null || !insets.isVisible(WindowInsets.Type.ime())) {
            if (Build.VERSION.SDK_INT >= 30 && SystemClock.uptimeMillis() < deadline)
                editor.postDelayed(() -> awaitKeyboard(attempt, deadline), 100);
            else toast(R.string.gboard_voice_manual);
            return;
        }
        // Android's Dictation key reaches the IME; terminal key mappings do not.
        try {
            if (Build.VERSION.SDK_INT < 36) throw new IllegalStateException();
            String selected = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (!"com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME".equals(selected))
                throw new IllegalStateException();
            SessionManager manager = SessionManager.get(activity);
            final java.util.concurrent.atomic.AtomicBoolean allowed = dictationAllowed;
            com.termux.app.session.IGboardTapGuard guard = new com.termux.app.session.IGboardTapGuard.Stub() {
                @Override public boolean isAllowed() { return allowed.get(); }
            };
            new Thread(() -> {
                // UI checks belong on the UI thread; capture happened before dispatch.
                boolean requested = manager.startGboardDictation(guard);
                activity.runOnUiThread(() -> {
                    if (isCurrent(attempt) && !requested) toast(R.string.gboard_voice_manual);
                });
            }, "aether-gboard-dictation").start();
        } catch (Exception unavailable) {
            toast(R.string.gboard_voice_manual);
        }
    }

    public void cancel() {
        ++generation;
        if (dictationAllowed != null) dictationAllowed.set(false);
        EditText previous = editor;
        editor = null;
        sessionHandle = null;
        if (previous != null) {
            previous.setOnFocusChangeListener(null);
            previous.setOnEditorActionListener(null);
            previous.setText("");
            ((FrameLayout) previous.getParent()).removeView(previous);
            activity.getTerminalView().requestFocus();
        }
    }

    private void toast(int message) { Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }
}
