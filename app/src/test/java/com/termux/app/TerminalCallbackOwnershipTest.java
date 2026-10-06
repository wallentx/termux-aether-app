package com.termux.app;

import android.app.Application;
import android.content.Intent;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TerminalCallbackOwnershipTest {
    private TermuxService service;
    private TerminalSession terminal;
    private CountingClient oldClient, currentClient;

    @Before public void setUp() {
        // Do not start native processes, notifications or the real application bootstrap.
        service = Robolectric.buildService(TermuxService.class).get();
        TermuxShellManager manager = new TermuxShellManager(service);
        ReflectionHelpers.setField(service, "mShellManager", manager);
        oldClient = new CountingClient();
        currentClient = new CountingClient();
        terminal = new TerminalSession("/bin/unused", "/", new String[0], new String[0], null, oldClient);
        TermuxSession session = ReflectionHelpers.callConstructor(TermuxSession.class,
            from(TerminalSession.class, terminal), from(ExecutionCommand.class, new ExecutionCommand(1)),
            from(TermuxSession.TermuxSessionClient.class, null), from(boolean.class, false));
        manager.mTermuxSessions.add(session);
        service.setTermuxTerminalSessionClient(oldClient);
    }

    @Test public void oldActivityDestructionCannotDetachReplacement() {
        service.setTermuxTerminalSessionClient(currentClient);
        oldClient.destroyed = true;
        service.unsetTermuxTerminalSessionClient(oldClient);
        assertSame(currentClient, service.getTermuxTerminalSessionClient());
        update();
        assertEquals(1, currentClient.updates);
        assertEquals(0, oldClient.updates);
    }

    @Test public void delayedUnbindKeepsLiveActivityCallbacks() {
        service.setTermuxTerminalSessionClient(currentClient);
        service.onUnbind(new Intent());
        update();
        assertEquals(1, currentClient.updates);
    }

    @Test public void destroyedOwnerIsReleasedAndForegroundCanReattach() {
        oldClient.destroyed = true;
        service.onUnbind(new Intent());
        assertNotSame(oldClient, service.getTermuxTerminalSessionClient());
        update();
        assertEquals(0, oldClient.updates);
        service.setTermuxTerminalSessionClient(currentClient);
        update();
        assertEquals(1, currentClient.updates);
        service.unsetTermuxTerminalSessionClient(currentClient);
        update();
        assertEquals(1, currentClient.updates);
    }

    private void update() { ReflectionHelpers.callInstanceMethod(terminal, "notifyScreenUpdate"); }

    private static final class CountingClient extends TermuxTerminalSessionActivityClient {
        int updates;
        boolean destroyed;
        CountingClient() { super(Robolectric.buildActivity(TermuxActivity.class).get()); }
        @Override public boolean isActivityDestroyed() { return destroyed; }
        @Override public void onTextChanged(TerminalSession changed) { updates++; }
    }
}
