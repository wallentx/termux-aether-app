package com.termux.app.terminal.io;

import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.termux.app.R;
import com.termux.app.TermuxActivity;
import com.termux.app.session.SessionManager;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** A real IME editor kept out of the toolbar; only Send transfers its draft to the terminal. */
public final class GboardVoiceInput {
    private final TermuxActivity activity;
    private EditText editor;
    private String sessionHandle;
    private long generation;
    private java.util.concurrent.atomic.AtomicBoolean tapAllowed;

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
        tapAllowed = allowed;
        editor = new EditText(activity) {
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
            TerminalSession current = activity.getCurrentSession();
            String text = VoiceInputText.forTerminal(editor.getText().toString());
            boolean sameSession = current != null && current.isRunning() && current.mHandle.equals(sessionHandle);
            cancel();
            if (!sameSession) toast(R.string.msg_voice_input_session_changed);
            else if (!text.isEmpty()) current.write(text);
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

    private void awaitKeyboard(long attempt, long deadline) {
        if (!isCurrent(attempt)) return;
        WindowInsets insets = editor.getRootWindowInsets();
        if (Build.VERSION.SDK_INT < 30 || insets == null || !insets.isVisible(WindowInsets.Type.ime())) {
            if (Build.VERSION.SDK_INT >= 30 && SystemClock.uptimeMillis() < deadline)
                editor.postDelayed(() -> awaitKeyboard(attempt, deadline), 100);
            else toast(R.string.gboard_voice_manual);
            return;
        }
        // No guessed coordinates: an optional local calibration names a tested icon image.
        try {
            String selected = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux/gboard-mic.json");
            if (file.length() > 4096 || !file.isFile()) throw new IllegalStateException();
            JSONObject profile = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!"com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME".equals(selected)
                || !selected.equals(profile.getString("ime"))) throw new IllegalStateException();
            android.content.pm.PackageInfo info = activity.getPackageManager().getPackageInfo("com.google.android.inputmethod.latin", 0);
            if (info.getLongVersionCode() != profile.getLong("versionCode")) throw new IllegalStateException();
            int x = profile.getInt("x"), y = profile.getInt("y");
            int width = profile.getInt("width"), height = profile.getInt("height");
            String fingerprint = profile.getString("fingerprint");
            SessionManager manager = SessionManager.get(activity);
            final java.util.concurrent.atomic.AtomicBoolean allowed = tapAllowed;
            com.termux.app.session.IGboardTapGuard guard = new com.termux.app.session.IGboardTapGuard.Stub() {
                @Override public boolean isAllowed() { return allowed.get(); }
            };
            new Thread(() -> {
                // UI checks belong on the UI thread; capture happened before dispatch.
                boolean tapped = manager.tapGboardMicrophone(x, y, width, height, fingerprint, guard);
                activity.runOnUiThread(() -> {
                    if (isCurrent(attempt) && !tapped) toast(R.string.gboard_voice_manual);
                });
            }, "aether-gboard-mic").start();
        } catch (Exception unavailable) {
            toast(R.string.gboard_voice_manual);
        }
    }

    public void cancel() {
        ++generation;
        if (tapAllowed != null) tapAllowed.set(false);
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
