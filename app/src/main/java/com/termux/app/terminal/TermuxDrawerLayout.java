package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.drawerlayout.widget.DrawerLayout;

/** Adds a forgiving left-edge swipe without changing DrawerLayout's normal drag handling. */
public class TermuxDrawerLayout extends DrawerLayout {
    private final float mSwipeStartWidth;
    private final int mTouchSlop;
    private boolean mSwipeCandidate;
    private float mDownX;
    private float mDownY;

    public TermuxDrawerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSwipeStartWidth = 48 * getResources().getDisplayMetrics().density;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        // Preserve native edge dragging, scrim taps, and gestures on the open drawer.
        if (super.onInterceptTouchEvent(event)) {
            mSwipeCandidate = false;
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mSwipeCandidate = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                    && mDownX >= 0 && mDownX <= mSwipeStartWidth
                    && !isDrawerVisible(Gravity.LEFT)
                    && getDrawerLockMode(Gravity.LEFT) == LOCK_MODE_UNLOCKED;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mSwipeCandidate) break;
                float dx = event.getX() - mDownX;
                float dy = Math.abs(event.getY() - mDownY);
                if (event.getPointerCount() != 1
                    || getDrawerLockMode(Gravity.LEFT) != LOCK_MODE_UNLOCKED
                    || event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()
                    || dx < -mTouchSlop || (dy > mTouchSlop && dy >= dx)) {
                    mSwipeCandidate = false;
                } else if (dx > 2 * mTouchSlop && dx > 1.5f * dy) {
                    mSwipeCandidate = false;
                    openDrawer(Gravity.LEFT);
                    // ViewGroup sends CANCEL to the terminal when interception begins.
                    return true;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mSwipeCandidate = false;
                break;
        }
        return false;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (disallowIntercept) mSwipeCandidate = false;
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }
}
