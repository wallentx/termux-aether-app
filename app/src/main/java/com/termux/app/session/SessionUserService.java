package com.termux.app.session;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import androidx.annotation.Keep;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Shell-UID process. Only the owning application UID can create or control its sessions. */
@Keep
public final class SessionUserService extends ISessionService.Stub {
    private final int ownerUid;
    private final String packageName;
    private final String apkPath;
    private final String nativeDirectory;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    @Keep public SessionUserService(Context context) {
        ownerUid = context.getApplicationInfo().uid;
        packageName = context.getPackageName();
        apkPath = context.getApplicationInfo().sourceDir;
        nativeDirectory = context.getApplicationInfo().nativeLibraryDir;
        SessionNative.load(nativeDirectory, apkPath);
    }

    private void checkCaller() {
        if (Binder.getCallingUid() != ownerUid) throw new SecurityException("Wrong session owner");
    }

    @Override public SessionHandle startSession(String executable, String cwd, String[] argv, String[] environment,
                                               int rows, int columns, int cellWidth, int cellHeight,
                                               ISessionCallback callback) {
        checkCaller();
        if (callback == null) throw new IllegalArgumentException("Missing session owner");
        String script = SessionCommand.script(executable, cwd, argv, environment);
        int[] child = SessionNative.start(packageName, script, SessionCommand.bootstrapEnvironment(environment),
            rows, columns, cellWidth, cellHeight);
        Session session = new Session(child[0], child[1], callback);
        sessions.put(session.token, session);
        try {
            callback.asBinder().linkToDeath(session, 0);
            ParcelFileDescriptor result = ParcelFileDescriptor.dup(session.master.getFileDescriptor());
            new Thread(session::reap, "aether-session-" + session.pid).start();
            return new SessionHandle(session.pid, session.token, result);
        } catch (IOException | RemoteException error) {
            session.stop();
            new Thread(session::reap, "aether-session-cleanup").start();
            throw new IllegalStateException("Cannot attach session", error);
        }
    }

    @Override public void stopSession(String token) {
        checkCaller();
        Session session = sessions.get(token);
        if (session != null) session.stop();
    }

    @Override public BackgroundHandle startBackground(String executable, String cwd, String[] argv,
                                                       String[] environment, ISessionCallback callback) {
        checkCaller();
        if (callback == null) throw new IllegalArgumentException("Missing job owner");
        String script = SessionCommand.script(executable, cwd, argv, environment);
        int[] child = SessionNative.startPipes(packageName, script, SessionCommand.bootstrapEnvironment(environment));
        Session session = new Session(child[0], -1, callback);
        BackgroundHandle handle = new BackgroundHandle(child[0], session.token,
            ParcelFileDescriptor.adoptFd(child[1]), ParcelFileDescriptor.adoptFd(child[2]),
            ParcelFileDescriptor.adoptFd(child[3]));
        sessions.put(session.token, session);
        try {
            callback.asBinder().linkToDeath(session, 0);
            new Thread(session::reap, "aether-background-" + session.pid).start();
            // Parcelable return-value transfer closes these service-side ends after marshaling.
            // In particular, retaining a second stdin writer would prevent EOF reaching the job.
            return handle;
        } catch (RemoteException error) {
            handle.close();
            session.stop();
            new Thread(session::reap, "aether-background-cleanup").start();
            throw new IllegalStateException("Cannot attach background job", error);
        }
    }

    @Override public String getCwd(String token) {
        checkCaller();
        Session session = sessions.get(token);
        if (session == null) return null;
        synchronized (session) {
            if (session.finished) return null;
            // The shell UID cannot read an app-UID process's cwd directly, even when it is its child.
            try {
                java.lang.Process reader = new ProcessBuilder("/system/bin/run-as", packageName,
                    "/system/bin/readlink", "/proc/" + session.pid + "/cwd")
                    .redirectError(new java.io.File("/dev/null")).start();
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream input = reader.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > 8192) return null;
                        bytes.write(buffer, 0, count);
                    }
                }
                if (reader.waitFor() != 0) return null;
                String path = new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                return path.endsWith("\n") ? path.substring(0, path.length() - 1) : path;
            } catch (Exception ignored) { return null; }
        }
    }

    @Override public void destroy() {
        int uid = Binder.getCallingUid();
        if (uid != ownerUid && uid != Process.myUid() && uid != 0) throw new SecurityException("Wrong UID");
        for (Session session : sessions.values()) session.stop();
        System.exit(0);
    }

    private final class Session implements IBinder.DeathRecipient {
        final String token = UUID.randomUUID().toString();
        final int pid;
        final ParcelFileDescriptor master;
        final ISessionCallback callback;
        boolean finished;

        Session(int pid, int fd, ISessionCallback callback) {
            this.pid = pid;
            master = fd < 0 ? null : ParcelFileDescriptor.adoptFd(fd);
            this.callback = callback;
        }

        synchronized void stop() { if (!finished) signal(true); }
        @Override public void binderDied() { stop(); }

        private void signal(boolean terminate) {
            try {
                java.lang.Process helper = new ProcessBuilder("/system/bin/run-as", packageName,
                    "/system/bin/app_process", "-Djava.class.path=" + apkPath, "/system/bin",
                    SessionSignals.class.getName(), nativeDirectory, Integer.toString(pid), terminate ? "stop" : "hangup")
                    .redirectErrorStream(true).redirectOutput(new java.io.File("/dev/null")).start();
                // Keep the leader's PID reserved until signaling finishes; never reap concurrently.
                if (helper.waitFor() != 0) android.util.Log.e("AetherSession", "Session signal helper failed");
            } catch (Exception error) {
                android.util.Log.e("AetherSession", "Cannot clean up session", error);
            }
        }

        void reap() {
            SessionNative.awaitExit(pid);
            int status;
            synchronized (this) {
                // Background descendants must not retain output pipes after the leader exits.
                signal(master == null);
                status = SessionNative.finish(pid);
                finished = true;
                if (master != null) try { master.close(); } catch (IOException ignored) {}
            }
            sessions.remove(token, this);
            try { callback.asBinder().unlinkToDeath(this, 0); }
            catch (java.util.NoSuchElementException ignored) { /* Owner died before registration. */ }
            try { callback.onExit(status); } catch (RemoteException ignored) {}
        }
    }
}
