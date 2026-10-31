package com.termux.app.session;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** No Android processes or microphone access: every command is recorded by a fake runner. */
public class GboardDictationTest {
    private static final String OWNER = "com.termux";
    private static final String FOCUS = "  mCurrentFocus=Window{123 u0 com.termux/.app.TermuxActivity}\n";

    private static IGboardTapGuard guard(BooleanSupplier allowed) {
        return new IGboardTapGuard() {
            @Override public boolean isAllowed() { return allowed.getAsBoolean(); }
            @Override public IBinder asBinder() { return null; }
        };
    }

    private static final class Runner implements GboardDictation.CommandRunner {
        final List<String[]> calls = new ArrayList<>();
        String ime = "com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME\n";
        String focus = FOCUS;
        int failingCall = -1;
        Exception failure;
        Runnable afterFocus = () -> {};

        @Override public String run(String... argv) throws Exception {
            calls.add(argv);
            if (calls.size() == failingCall) {
                if (failure != null) throw failure;
                return null;
            }
            switch (calls.size()) {
                case 1: return ime;
                case 2: afterFocus.run(); return focus;
                case 3: return "";
                default: throw new AssertionError("Unexpected retry");
            }
        }
    }

    @Test public void dispatchesExactlyOneDictateKeyAfterCheckingImeAndFocus() {
        Runner runner = new Runner();
        assertTrue(GboardDictation.start(OWNER, guard(() -> true), 36, runner));
        assertEquals(3, runner.calls.size());
        assertArrayEquals(new String[] {"/system/bin/settings", "--user", "current", "get", "secure", "default_input_method"}, runner.calls.get(0));
        assertArrayEquals(new String[] {"/system/bin/dumpsys", "window", "displays"}, runner.calls.get(1));
        assertArrayEquals(new String[] {"/system/bin/input", "keyevent", "319"}, runner.calls.get(2));
    }

    @Test public void unsupportedAndroidDoesNotRunAnyCommand() {
        Runner runner = new Runner();
        assertFalse(GboardDictation.start(OWNER, guard(() -> true), 35, runner));
        assertEquals(0, runner.calls.size());
    }

    @Test public void canceledRequestDoesNotRunAnyCommand() {
        Runner runner = new Runner();
        assertFalse(GboardDictation.start(OWNER, guard(() -> false), 36, runner));
        assertEquals(0, runner.calls.size());
    }

    @Test public void cancellationWhileCheckingFocusPreventsInjection() {
        Runner runner = new Runner();
        boolean[] allowed = {true};
        runner.afterFocus = () -> allowed[0] = false;
        assertFalse(GboardDictation.start(OWNER, guard(() -> allowed[0]), 36, runner));
        assertEquals(2, runner.calls.size());
    }

    @Test public void missingGuardPreventsCommands() {
        Runner runner = new Runner();
        assertFalse(GboardDictation.start(OWNER, null, 36, runner));
        assertEquals(0, runner.calls.size());
    }

    @Test public void deadGuardPreventsCommands() {
        Runner runner = new Runner();
        IGboardTapGuard dead = new IGboardTapGuard() {
            @Override public boolean isAllowed() throws RemoteException { throw new RemoteException(); }
            @Override public IBinder asBinder() { return null; }
        };
        assertFalse(GboardDictation.start(OWNER, dead, 36, runner));
        assertEquals(0, runner.calls.size());
    }

    @Test public void differentKeyboardPreventsInjection() {
        Runner runner = new Runner();
        runner.ime = "com.example.keyboard/.Keyboard";
        assertFalse(GboardDictation.start(OWNER, guard(() -> true), 36, runner));
        assertEquals(1, runner.calls.size());
    }

    @Test public void otherForegroundPackageOrMissingFocusPreventsInjection() {
        for (String focus : new String[] {
            "mCurrentFocus=Window{123 u0 com.termux.other/.Activity}\n",
            "mCurrentFocus=null\n",
            "mLastFocus=Window{123 u0 com.termux/.app.TermuxActivity}\n",
            FOCUS + "mCurrentFocus=Window{456 u0 com.example/.Activity}\n"
        }) {
            Runner runner = new Runner();
            runner.focus = focus;
            assertFalse(GboardDictation.start(OWNER, guard(() -> true), 36, runner));
            assertEquals(2, runner.calls.size());
        }
    }

    @Test public void failedCommandsNeverRetry() {
        for (int failingCall = 1; failingCall <= 3; failingCall++) {
            Runner runner = new Runner();
            runner.failingCall = failingCall;
            assertFalse(GboardDictation.start(OWNER, guard(() -> true), 36, runner));
            assertEquals(failingCall, runner.calls.size());
        }
    }

    @Test public void failedOrTimedOutFocusCheckPreventsInjection() {
        for (Exception error : new Exception[] {new IOException("start failed"), new TimeoutException()}) {
            Runner runner = new Runner();
            runner.failingCall = 2;
            runner.failure = error;
            assertFalse(GboardDictation.start(OWNER, guard(() -> true), 36, runner));
            assertEquals(2, runner.calls.size());
        }
    }

    @Test public void oversizedFocusOutputPreventsInjection() {
        Runner runner = new Runner();
        runner.focus = FOCUS + new String(new char[2 * 1024 * 1024]);
        assertFalse(GboardDictation.start(OWNER, guard(() -> true), 36, runner));
        assertEquals(2, runner.calls.size());
    }
}
