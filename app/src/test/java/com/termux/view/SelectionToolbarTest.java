package com.termux.view;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.Build;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MotionEvent;
import android.view.View;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSessionClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowView;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = SelectionToolbarTest.ToolbarViewShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SelectionToolbarTest {
    private TerminalView view;
    private ToolbarViewShadow toolbar;

    @Before public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        view = new TerminalView(activity, null);
        toolbar = Shadow.extract(view);
        view.setFocusableInTouchMode(true);
        view.setTerminalViewClient(noOp(TerminalViewClient.class));
        view.mRenderer = new TerminalRenderer(20, Typeface.MONOSPACE);
        view.mEmulator = new TerminalEmulator(new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        }, 80, 24, 10, 20, null, noOp(TerminalSessionClient.class));
        activity.setContentView(view);
        view.layout(0, 80, 800, 560);
    }

    @Test public void initialSelectionRefreshesMenuWithoutTouchingHandles() {
        select();
        assertTrue(toolbar.activeDuringCreation);
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertTrue(toolbar.mode.refreshes > 0);
        assertEquals(1, toolbar.mode.shows);
        assertTrue("A single selected cell needs nonzero width", toolbar.mode.rect.width() > 0);
        assertTrue(toolbar.mode.rect.bottom <= view.getHeight());
    }

    @Test public void dragHidesMenuUntilReleaseAndRepeatedReleasesCoalesce() {
        select();
        touch(MotionEvent.ACTION_MOVE);
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(0, toolbar.mode.shows);
        assertEquals(1, toolbar.mode.hides);
        touch(MotionEvent.ACTION_UP);
        touch(MotionEvent.ACTION_UP);
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(1, toolbar.mode.shows);
    }

    @Test public void finishedActionModeIsNotReshownByPendingCallback() {
        select();
        toolbar.mode.finish();
        assertFalse("Framework dismissal must exit selection immediately", view.isSelectingText());
        assertNull(view.getSelectedText());
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(0, toolbar.mode.shows);
    }

    @Test public void normalDismissalFinishesModeOnlyOnce() {
        select();
        Object controller = ReflectionHelpers.getField(view, "mTextSelectionCursorController");
        ReflectionHelpers.setField(controller, "mShowStartTime", 0L);
        view.stopTextSelectionMode();
        assertFalse(view.isSelectingText());
        assertEquals(1, toolbar.mode.finishes);
        view.stopTextSelectionMode();
        assertEquals(1, toolbar.mode.finishes);
    }

    @Test public void android17RefreshChangesToolbarAnchorWithoutChangingSelection() {
        // Exercise our API gate on the supported test runtime; real framework behavior
        // is checked separately on the Android 17 device.
        int sdk = Build.VERSION.SDK_INT;
        try {
            ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", 37);
            select();
            toolbar.mode.invalidateContentRect();
            Rect original = new Rect(toolbar.mode.rect);
            String selected = view.getSelectedText();
            Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            Rect refreshed = new Rect(original);
            refreshed.offset(0, -1);
            assertEquals(refreshed, toolbar.mode.rect);
            assertEquals(selected, view.getSelectedText());
            assertEquals(1, toolbar.mode.shows);
            touch(MotionEvent.ACTION_UP);
            Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertEquals(original, toolbar.mode.rect);
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", sdk);
        }
    }

    @Test public void detachedViewDoesNotRunPendingMenuRefresh() {
        select();
        view.onDetachedFromWindow();
        Shadow.<ShadowLooper>extract(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertEquals(0, toolbar.mode.shows);
    }

    private void select() {
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 40, 40, 0);
        view.startTextSelectionMode(event);
        event.recycle();
        assertNotNull(toolbar.mode);
    }

    private void touch(int action) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, 40, 40, 0);
        view.updateFloatingToolbarVisibility(event);
        event.recycle();
    }

    @SuppressWarnings("unchecked")
    private static <T> T noOp(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == float.class) return 1f;
            return null;
        });
    }

    @Implements(View.class)
    public static class ToolbarViewShadow extends ShadowView {
        @RealObject private View realView;
        TestMode mode;
        boolean activeDuringCreation;

        @Implementation
        protected ActionMode startActionMode(ActionMode.Callback callback, int type) {
            if (!(realView instanceof TerminalView)) return null;
            activeDuringCreation = ((TerminalView) realView).isSelectingText();
            mode = new TestMode((ActionMode.Callback2) callback, realView);
            return mode;
        }
    }

    private static class TestMode extends ActionMode {
        final Callback2 callback;
        final View view;
        final Rect rect = new Rect();
        int refreshes, shows, hides, finishes;
        TestMode(Callback2 callback, View view) { this.callback = callback; this.view = view; }
        @Override public void invalidateContentRect() { refreshes++; callback.onGetContentRect(this, view, rect); }
        @Override public void hide(long duration) {
            if (duration == 0) shows++;
            else hides++;
        }
        @Override public void finish() { finishes++; callback.onDestroyActionMode(this); }
        @Override public void invalidate() {}
        @Override public void setTitle(CharSequence title) {}
        @Override public void setTitle(int resId) {}
        @Override public void setSubtitle(CharSequence subtitle) {}
        @Override public void setSubtitle(int resId) {}
        @Override public void setCustomView(View view) {}
        @Override public Menu getMenu() { return null; }
        @Override public CharSequence getTitle() { return null; }
        @Override public CharSequence getSubtitle() { return null; }
        @Override public View getCustomView() { return null; }
        @Override public MenuInflater getMenuInflater() { return null; }
    }
}
