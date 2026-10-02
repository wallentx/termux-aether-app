package com.termux.app.session;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import com.termux.shared.shell.command.runner.app.AppShellProcess;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A pipe-based Process adapter, so AppShell retains its existing result delivery. */
final class BackgroundProcess extends AppShellProcess implements IBinder.DeathRecipient {
    private final ISessionService service;
    private final CountDownLatch exited = new CountDownLatch(1);
    private volatile int status = 127;
    private boolean serviceLost;
    private BackgroundHandle handle;
    private OutputStream stdin;
    private InputStream stdout, stderr;
    private final ISessionCallback callback = new ISessionCallback.Stub() {
        @Override public void onExit(int code) { complete(code); }
    };

    BackgroundProcess(ISessionService service, String[] command, String cwd, String[] environment) throws IOException {
        this.service = service;
        try {
            service.asBinder().linkToDeath(this, 0);
            BackgroundHandle started = service.startBackground(command[0], cwd, command, environment, callback);
            synchronized (this) {
                handle = started;
                if (handle == null || handle.pid <= 0 || handle.token == null
                    || handle.stdin == null || handle.stdout == null || handle.stderr == null)
                    throw new IOException("Invalid background process handle");
                if (serviceLost) throw new IOException("Shizuku stopped while starting the background job");
                stdin = new ParcelFileDescriptor.AutoCloseOutputStream(handle.stdin);
                stdout = new ParcelFileDescriptor.AutoCloseInputStream(handle.stdout);
                stderr = new ParcelFileDescriptor.AutoCloseInputStream(handle.stderr);
            }
        } catch (RemoteException | RuntimeException | IOException error) {
            destroy();
            throw new IOException("Cannot start Shizuku background job", error);
        }
    }

    private synchronized void complete(int code) {
        if (exited.getCount() == 0) return;
        // Native session status uses negative signals; Process consumers expect shell-style status.
        status = code < 0 ? 128 - code : code;
        exited.countDown();
    }

    @Override public synchronized void binderDied() {
        // The native lifetime monitor terminates the job/session even if no handle was delivered.
        // A completed child may still have buffered output to drain; it no longer needs the service.
        if (exited.getCount() == 0) return;
        serviceLost = true;
        complete(127);
        if (handle != null) handle.close();
    }

    @Override public int getPid() { return handle.pid; }
    @Override public OutputStream getOutputStream() { return stdin; }
    @Override public InputStream getInputStream() { return stdout; }
    @Override public InputStream getErrorStream() { return stderr; }
    @Override public int waitFor() throws InterruptedException { exited.await(); return status; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        return exited.await(timeout, unit);
    }
    @Override public int exitValue() {
        if (exited.getCount() != 0) throw new IllegalThreadStateException("Background job is still running");
        return status;
    }
    @Override public boolean isAlive() { return exited.getCount() != 0; }
    @Override public synchronized void kill() {
        if (handle == null || exited.getCount() == 0) return;
        try { service.stopSession(handle.token); }
        catch (RemoteException | RuntimeException error) { binderDied(); }
    }
    @Override public synchronized void destroy() {
        kill();
        try { service.asBinder().unlinkToDeath(this, 0); }
        catch (java.util.NoSuchElementException ignored) { /* Link failed or was already removed. */ }
        if (handle != null) handle.close();
    }
    @Override public Process destroyForcibly() { kill(); return this; }
}
