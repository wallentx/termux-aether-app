package com.termux.app.session;

import android.os.Binder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercises the real AIDL parcel/Process adapter with a deterministic fake pipe service. */
public final class BackgroundProcessProbe {
    public static void main(String[] args) throws Exception {
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(15000); Runtime.getRuntime().halt(124); }
            catch (InterruptedException ignored) {}
        });
        deadline.setDaemon(true);
        deadline.start();
        FakeService normal = new FakeService(false, false);
        BackgroundProcess process = start(normal);
        check(process.getPid() == 12345 && process.isAlive(), "PID and running state");
        check(!process.waitFor(1, TimeUnit.MILLISECONDS), "timed wait does not report early completion");
        try { process.exitValue(); throw new AssertionError("exitValue while running"); }
        catch (IllegalThreadStateException expected) {}
        process.getOutputStream().write("hello\næ".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 23, "stdin EOF across parcel transfer and callback status");
        process.binderDied();
        check(read(process.getInputStream()).equals("hello\næ"), "stdout has no terminal transformation");
        check(read(process.getErrorStream()).equals("separate-error"), "stderr remains separate");
        check(process.exitValue() == 23, "later service death preserves completed status and buffered output");
        process.destroy(); process.destroy();

        FakeService cancelled = new FakeService(true, false);
        process = start(cancelled);
        process.getOutputStream().close();
        process.destroyForcibly();
        check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 137, "cancellation uses service and maps signal status");
        check(cancelled.stops == 1, "cancellation reached owner once");
        process.destroy();

        FakeService early = new FakeService(false, true);
        process = start(early);
        check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 17, "exit callback before start returns");
        process.destroy();

        FakeService lost = new FakeService(true, false);
        process = start(lost);
        process.getOutputStream().close();
        InputStream blockedOutput = process.getInputStream();
        CountDownLatch readStarted = new CountDownLatch(1), readEnded = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            readStarted.countDown();
            try { blockedOutput.read(); } catch (java.io.IOException expected) {}
            finally { readEnded.countDown(); }
        });
        reader.start();
        check(readStarted.await(1, TimeUnit.SECONDS), "output reader started");
        Thread.sleep(25);
        process.binderDied();
        check(process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 127, "service death wakes waiters with failure");
        check(readEnded.await(2, TimeUnit.SECONDS), "service death unblocks pipe readers");
        lost.stopSession("test-token");
        process.destroy();
        System.out.println("PASS: background Process adapter and AIDL parcels");
        System.exit(0);
    }

    private static BackgroundProcess start(FakeService service) throws Exception {
        // A Binder without a local interface forces the generated Proxy/Stub parcel path.
        Binder bridge = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                return service.asBinder().transact(code, data, reply, flags);
            }
        };
        return new BackgroundProcess(ISessionService.Stub.asInterface(bridge),
            new String[]{"/system/bin/sh", "space arg", ""}, "/", new String[]{"TEST=literal"});
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) result.write(buffer, 0, count);
        return new String(result.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        System.out.println("PASS " + description);
    }

    private static final class FakeService extends ISessionService.Stub {
        final boolean hold, early;
        final CountDownLatch stop = new CountDownLatch(1);
        volatile int stops;
        FakeService(boolean hold, boolean early) { this.hold = hold; this.early = early; }
        @Override public BackgroundHandle startBackground(String executable, String cwd, String[] argv,
                                                          String[] environment, ISessionCallback callback) {
            try {
                check(executable.equals("/system/bin/sh") && argv.length == 3 && argv[1].equals("space arg")
                    && argv[2].isEmpty() && environment[0].equals("TEST=literal"), "literal request transport");
                ParcelFileDescriptor[] in = ParcelFileDescriptor.createPipe();
                ParcelFileDescriptor[] out = ParcelFileDescriptor.createPipe();
                ParcelFileDescriptor[] err = ParcelFileDescriptor.createPipe();
                BackgroundHandle handle = new BackgroundHandle(12345, "test-token", in[1], out[0], err[0]);
                if (early) {
                    in[0].close(); out[1].close(); err[1].close(); callback.onExit(17);
                } else {
                    new Thread(() -> {
                        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(in[0]);
                             java.io.OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(out[1]);
                             java.io.OutputStream error = new ParcelFileDescriptor.AutoCloseOutputStream(err[1])) {
                            String data = read(input);
                            if (hold) stop.await();
                            else {
                                output.write(data.getBytes(StandardCharsets.UTF_8));
                                error.write("separate-error".getBytes(StandardCharsets.UTF_8));
                            }
                            callback.onExit(hold ? -9 : 23);
                        } catch (Exception e) { throw new AssertionError(e); }
                    }).start();
                }
                return handle;
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public void stopSession(String token) { stops++; stop.countDown(); }
        @Override public String getCwd(String token) { return "/"; }
        @Override public void destroy() { stop.countDown(); }
        @Override public SessionHandle startSession(String executable, String cwd, String[] argv, String[] env,
                int rows, int cols, int width, int height, ISessionCallback callback) { throw new UnsupportedOperationException(); }
    }
}
