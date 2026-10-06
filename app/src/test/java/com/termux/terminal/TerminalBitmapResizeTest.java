package com.termux.terminal;

import android.app.Application;
import android.graphics.Bitmap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.Random;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TerminalBitmapResizeTest {
    @Test public void nativeCopyMatchesPixelCopyForGrowthCroppingAlphaAndDensity() {
        Random random = new Random(123);
        for (Bitmap.Config config : new Bitmap.Config[]{Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565}) {
            Bitmap source = Bitmap.createBitmap(31, 19, config);
            int[] input = new int[31 * 19];
            for (int i = 0; i < input.length; i++) input[i] = random.nextInt();
            input[0] = 0; input[1] = 0xffffffff; input[2] = 0xff000000;
            source.setPixels(input, 0, 31, 0, 0, 31, 19);
            source.setDensity(640);
            for (int[] size : new int[][]{{31, 19}, {43, 29}, {11, 7}, {11, 29}, {43, 7}}) {
                Bitmap expected = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                int[] pixels = new int[31 * 19];
                source.getPixels(pixels, 0, 31, 0, 0, 31, 19);
                expected.setPixels(pixels, 0, 31, 0, 0, Math.min(31, size[0]), Math.min(19, size[1]));
                Bitmap actual = TerminalBitmap.resizeBitmap("test", "test", null, source, size[0], size[1]);
                assertNotNull(actual);
                assertFalse(source.isRecycled());
                assertTrue("Pixels and transparent margins must match at " + size[0] + "x" + size[1],
                    expected.sameAs(actual));
            }
        }
    }
}
