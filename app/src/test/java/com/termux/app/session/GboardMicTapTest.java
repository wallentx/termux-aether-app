package com.termux.app.session;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class GboardMicTapTest {
    @Test public void fingerprintIgnoresScreenOutsideTheControl() throws Exception {
        Bitmap image = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.BLACK);
        String before = GboardMicTap.fingerprint(image, 80, 80);
        image.setPixel(0, 0, Color.WHITE);
        assertEquals(before, GboardMicTap.fingerprint(image, 80, 80));
        image.recycle();
    }

    @Test public void fingerprintChangesWhenControlChanges() throws Exception {
        Bitmap image = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.BLACK);
        String before = GboardMicTap.fingerprint(image, 80, 80);
        image.setPixel(80, 80, Color.WHITE);
        assertNotEquals(before, GboardMicTap.fingerprint(image, 80, 80));
        image.recycle();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCropOutsideScreen() throws Exception {
        Bitmap image = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        try { GboardMicTap.fingerprint(image, 1, 80); }
        finally { image.recycle(); }
    }

    @Test public void cancelledRequestDoesNotCaptureOrTap() {
        IGboardTapGuard guard = new IGboardTapGuard.Stub() {
            @Override public boolean isAllowed() { return false; }
        };
        assertFalse(GboardMicTap.tap("com.termux", 80, 80, 160, 160,
            "0000000000000000000000000000000000000000000000000000000000000000", guard));
    }
}
