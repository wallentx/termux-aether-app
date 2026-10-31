package com.termux.app.session;

import android.content.Context;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.IShellEnvironment;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.shell.command.runner.app.AppShellProcess;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class AppShellBackgroundTest {
    private static final IShellEnvironment ENVIRONMENT = new IShellEnvironment() {
        @Override public String getDefaultWorkingDirectoryPath() { return "/"; }
        @Override public String getDefaultBinPath() { return "/bin"; }
        @Override public String[] setupShellCommandArguments(String path, String[] args) {
            return new String[]{path, "space arg", ""};
        }
        @Override public HashMap<String, String> setupShellCommandEnvironment(Context context, ExecutionCommand command) {
            HashMap<String, String> env = new HashMap<>();
            env.put("TEST", "literal value");
            return env;
        }
    };

    private static ExecutionCommand command(String stdin) {
        return new ExecutionCommand(1, "/bin/probe", new String[0], stdin, "/",
            ExecutionCommand.Runner.APP_SHELL.getName(), false);
    }

    @Test(timeout = 5000) public void emptyInputClosesPipeAndPreservesSeparateResults() {
        ExecutionCommand command = command(null);
        FakeProcess process = new FakeProcess(false);
        AtomicInteger callbacks = new AtomicInteger();
        AppShell result = AppShell.execute(RuntimeEnvironment.getApplication(), command,
            shell -> callbacks.incrementAndGet(), ENVIRONMENT, null, true, (args, env, cwd) -> {
                assertArrayEquals(new String[]{"/bin/probe", "space arg", ""}, args);
                assertArrayEquals(new String[]{"TEST=literal value"}, env);
                assertEquals("/", cwd);
                return process;
            });
        assertNotNull(result);
        assertTrue(process.inputClosed);
        assertEquals(0, process.input.size());
        assertEquals("output\n", command.resultData.stdout.toString());
        assertEquals("error\n", command.resultData.stderr.toString());
        assertEquals(Integer.valueOf(23), command.resultData.exitCode);
        assertEquals(12345, command.mPid);
        assertEquals(1, callbacks.get());
    }

    @Test(timeout = 5000) public void suppliedInputRetainsExistingTrailingNewline() {
        ExecutionCommand command = command("hello\nworld");
        FakeProcess process = new FakeProcess(false);
        assertNotNull(AppShell.execute(RuntimeEnvironment.getApplication(), command, null,
            ENVIRONMENT, null, true, (args, env, cwd) -> process));
        assertEquals("hello\nworld\n", new String(process.input.toByteArray(), StandardCharsets.UTF_8));
        assertTrue(process.inputClosed);
    }

    @Test(timeout = 5000) public void cancellationStillKillsAfterCommandIsMarkedFailed() throws Exception {
        ExecutionCommand command = command(null);
        FakeProcess process = new FakeProcess(true);
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch callback = new CountDownLatch(1);
        AppShell shell = AppShell.execute(RuntimeEnvironment.getApplication(), command,
            done -> { callbacks.incrementAndGet(); callback.countDown(); }, ENVIRONMENT, null, false,
            (args, env, cwd) -> process);
        assertNotNull(shell);
        assertTrue(process.waiting.await(2, TimeUnit.SECONDS));
        shell.killIfExecuting(RuntimeEnvironment.getApplication(), true);
        assertEquals(1, process.kills);
        assertTrue(callback.await(2, TimeUnit.SECONDS));
        assertTrue(process.destroyed.await(2, TimeUnit.SECONDS));
        assertTrue(command.isStateFailed());
        assertEquals(Integer.valueOf(137), command.resultData.exitCode);
        assertEquals(1, callbacks.get());
    }

    @Test public void unavailableBackendFailsWithoutLocalExecution() {
        ExecutionCommand command = command(null);
        // /bin/probe is deliberately not executable here; only the supplied factory may run it.
        AppShell shell = AppShell.execute(RuntimeEnvironment.getApplication(), command, null,
            ENVIRONMENT, null, true, (args, env, cwd) -> { throw new IOException("Shizuku unavailable"); });
        assertNull(shell);
        assertTrue(command.isStateFailed());
    }

    private static final class FakeProcess extends AppShellProcess {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1), waiting = new CountDownLatch(1), destroyed = new CountDownLatch(1);
        final boolean stayAlive;
        volatile boolean inputClosed;
        volatile int kills;
        FakeProcess(boolean stayAlive) { this.stayAlive = stayAlive; }
        @Override public int getPid() { return 12345; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { input.write(value); }
                @Override public void close() { inputClosed = true; if (!stayAlive) done.countDown(); }
            };
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream("output\n".getBytes(StandardCharsets.UTF_8)); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream("error\n".getBytes(StandardCharsets.UTF_8)); }
        @Override public int waitFor() throws InterruptedException { waiting.countDown(); done.await(); return kills == 0 ? 23 : 137; }
        @Override public int exitValue() { if (done.getCount() != 0) throw new IllegalThreadStateException(); return kills == 0 ? 23 : 137; }
        @Override public void kill() { kills++; done.countDown(); }
        @Override public void destroy() { destroyed.countDown(); }
    }
}
