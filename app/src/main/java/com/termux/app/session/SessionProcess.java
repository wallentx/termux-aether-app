package com.termux.app.session;

import android.os.IBinder;
import android.os.RemoteException;
import com.termux.terminal.TerminalSessionProcess;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

/** The app waits for a callback, not a rish I/O thread or a PID it cannot reap. */
final class SessionProcess implements TerminalSessionProcess, IBinder.DeathRecipient {
    private final ISessionService service;
    private final CountDownLatch exited = new CountDownLatch(1);
    private volatile int status = 127;
    private SessionHandle handle;
    private final ISessionCallback callback = new ISessionCallback.Stub() {
        @Override public void onExit(int code) { status = code; exited.countDown(); }
    };

    SessionProcess(ISessionService service, String executable, String cwd, String[] argv, String[] environment,
                   int rows, int columns, int cellWidth, int cellHeight) throws Exception {
        this.service = service;
        service.asBinder().linkToDeath(this, 0);
        try {
            handle = service.startSession(executable, cwd, argv, environment, rows, columns,
                cellWidth, cellHeight, callback);
            if (handle == null || handle.master == null || handle.pid <= 0) throw new IOException("Invalid session handle");
        } catch (Exception error) {
            service.asBinder().unlinkToDeath(this, 0);
            throw error;
        }
    }

    @Override public void binderDied() {
        // The native lifetime monitor owns cleanup; it does not rely on PTY hangup.
        exited.countDown();
    }
    @Override public int getPid() { return handle.pid; }
    @Override public int takeMasterFd() { return handle.master.detachFd(); }
    @Override public int waitFor() {
        try { exited.await(); } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); stop(); return 127;
        }
        return status;
    }
    @Override public void stop() {
        try { service.stopSession(handle.token); }
        catch (RemoteException ignored) { exited.countDown(); }
    }
    @Override public String getCwd() {
        try { return service.getCwd(handle.token); } catch (RemoteException ignored) { return null; }
    }
    @Override public void close() {
        service.asBinder().unlinkToDeath(this, 0);
        try { handle.master.close(); } catch (IOException ignored) {}
    }
}
