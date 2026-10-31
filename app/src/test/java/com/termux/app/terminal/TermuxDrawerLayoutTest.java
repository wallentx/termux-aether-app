package com.termux.app.terminal;

import android.app.Application;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.drawerlayout.widget.DrawerLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TermuxDrawerLayoutTest {
    private TestDrawer mDrawer;
    private float mDensity;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        mDensity = context.getResources().getDisplayMetrics().density;
        mDrawer = new TestDrawer(context);
        mDrawer.addView(new View(context), new DrawerLayout.LayoutParams(-1, -1));
        mDrawer.addView(new View(context), new DrawerLayout.LayoutParams(240, -1, Gravity.LEFT));
        mDrawer.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        mDrawer.layout(0, 0, 800, 1000);
    }

    @Test public void swipeInsideWiderZoneOpensDrawer() {
        touch(MotionEvent.ACTION_DOWN, 40, 100, 0);
        assertTrue(touch(MotionEvent.ACTION_MOVE, 80, 103, 80));
        assertTrue(mDrawer.opened);
    }

    @Test public void swipeOutsideZoneDoesNotOpenDrawer() {
        touch(MotionEvent.ACTION_DOWN, 80, 100, 0);
        assertFalse(touch(MotionEvent.ACTION_MOVE, 120, 100, 80));
        assertFalse(mDrawer.opened);
    }

    @Test public void verticalScrollCannotTurnIntoDrawerSwipe() {
        touch(MotionEvent.ACTION_DOWN, 40, 100, 0);
        assertFalse(touch(MotionEvent.ACTION_MOVE, 42, 140, 40));
        assertFalse(touch(MotionEvent.ACTION_MOVE, 100, 140, 80));
        assertFalse(mDrawer.opened);
    }

    @Test public void lockedSelectionModeDoesNotOpenDrawer() {
        mDrawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        touch(MotionEvent.ACTION_DOWN, 40, 100, 0);
        assertFalse(touch(MotionEvent.ACTION_MOVE, 80, 100, 80));
        assertFalse(mDrawer.opened);
    }

    @Test public void longPressDoesNotBecomeDrawerSwipe() {
        touch(MotionEvent.ACTION_DOWN, 40, 100, 0);
        assertFalse(touch(MotionEvent.ACTION_MOVE, 80, 100, ViewConfiguration.getLongPressTimeout() + 1));
        assertFalse(mDrawer.opened);
    }

    @Test public void cancellationClearsCandidate() {
        touch(MotionEvent.ACTION_DOWN, 40, 100, 0);
        touch(MotionEvent.ACTION_CANCEL, 40, 100, 20);
        assertFalse(touch(MotionEvent.ACTION_MOVE, 80, 100, 80));
        assertFalse(mDrawer.opened);
    }

    private boolean touch(int action, float x, float y, long elapsed) {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x * mDensity;
        coords.y = y * mDensity;
        MotionEvent event = MotionEvent.obtain(1000, 1000 + elapsed, action, 1,
            new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coords},
            0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            return mDrawer.onInterceptTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static class TestDrawer extends TermuxDrawerLayout {
        boolean opened;
        TestDrawer(Context context) { super(context, null); }
        @Override public void openDrawer(int gravity) {
            opened = true;
            super.openDrawer(gravity, false);
        }
    }
}
