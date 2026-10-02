package com.termux.app.session;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.widget.TextView;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSessionProcess;
import java.util.concurrent.CopyOnWriteArrayList;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;

/** Required normal-session backend. Recovery is an explicit user action, never a command retry. */
public final class SessionManager {
    private static final int REQUEST_PERMISSION = 7341;
    private static SessionManager instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Shizuku.UserServiceArgs args;
    private volatile ISessionService service;
    private boolean connecting;
    private SessionConnection connection;
    private boolean requestingBinder;
    private int binderRequest;
    private boolean requestPermissionOnConnect;
    private java.lang.ref.WeakReference<AlertDialog> prompt = new java.lang.ref.WeakReference<>(null);
    private String message = "Start Shizuku, then connect to open a terminal session.";

    public static boolean required(Context context) {
        return Build.VERSION.SDK_INT >= 29 && context.getApplicationInfo().targetSdkVersion >= 29;
    }

    public static synchronized SessionManager get(Context context) {
        if (instance == null) instance = new SessionManager(context.getApplicationContext());
        return instance;
    }

    private SessionManager(Context context) {
        this.context = context;
        args = new Shizuku.UserServiceArgs(new ComponentName(context, SessionUserService.class))
            .daemon(false).processNameSuffix("aether_sessions").debuggable(false).version(3);
        Shizuku.addBinderReceivedListenerSticky(() -> main.post(() -> {
            requestingBinder = false;
            connect(requestPermissionOnConnect);
        }));
        Shizuku.addBinderDeadListener(() -> main.post(() -> {
            if (Shizuku.pingBinder()) return;
            clearConnection();
            message = "Shizuku stopped. Start it again to open new sessions.";
            notifyChanged();
        }));
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (code == REQUEST_PERMISSION) main.post(() -> {
                if (result == PackageManager.PERMISSION_GRANTED) connect(false);
                else { message = "Shizuku access was denied. Grant access in Shizuku, then reconnect."; notifyChanged(); }
            });
        });
    }

    private final class SessionConnection implements ServiceConnection {
        private IBinder binder;
        private IBinder.DeathRecipient deathRecipient;

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            main.post(() -> {
                if (connection != this) return;
                unlinkDeathRecipient();
                this.binder = binder;
                deathRecipient = () -> main.post(() -> disconnected(this, binder));
                connecting = false;
                service = ISessionService.Stub.asInterface(binder);
                try { binder.linkToDeath(deathRecipient, 0); }
                catch (RemoteException error) { disconnected(this, binder); return; }
                message = "Connected.";
                notifyChanged();
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            main.post(() -> disconnected(this, null));
        }

        private void unlinkDeathRecipient() {
            if (binder != null && deathRecipient != null) {
                try { binder.unlinkToDeath(deathRecipient, 0); } catch (RuntimeException ignored) {}
            }
            binder = null;
            deathRecipient = null;
        }

        private void detach() {
            unlinkDeathRecipient();
            // Remove this listener without stopping the service or its running sessions.
            try { Shizuku.unbindUserService(args, this, false); } catch (RuntimeException ignored) {}
        }
    }

    private void clearConnection() {
        SessionConnection previous = connection;
        connection = null;
        service = null;
        connecting = false;
        if (previous != null) previous.detach();
    }

    private void disconnected(SessionConnection disconnected, IBinder binder) {
        if (connection != disconnected || (binder != null && disconnected.binder != binder)) return;
        clearConnection();
        message = "Session service disconnected. Reconnect to open a new session.";
        notifyChanged();
    }

    public boolean isReady() {
        ISessionService current = service;
        try {
            return current != null && current.asBinder().isBinderAlive() && Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException unavailable) { return false; }
    }

    private void notifyChanged() { for (Runnable listener : listeners) listener.run(); }

    private void connect(boolean requestPermission) {
        requestPermissionOnConnect |= requestPermission;
        if (requestingBinder && !Shizuku.pingBinder()) return;
        if (isReady() || connecting) { notifyChanged(); return; }
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            message = "This APK cannot use Android run-as. Install a debuggable Aether build.";
            notifyChanged(); return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                message = "Shizuku is not connected. Tap Connect to request its connection.";
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                message = "Allow Aether to use Shizuku to start terminal sessions.";
                if (requestPermissionOnConnect) {
                    requestPermissionOnConnect = false;
                    Shizuku.requestPermission(REQUEST_PERMISSION);
                }
            } else {
                requestPermissionOnConnect = false;
                clearConnection();
                final SessionConnection currentConnection = new SessionConnection();
                connection = currentConnection;
                connecting = true;
                message = "Connecting to the session service...";
                Shizuku.bindUserService(args, currentConnection);
                main.postDelayed(() -> {
                    if (connection == currentConnection && connecting) {
                        clearConnection();
                        message = "Session service did not connect within 15 seconds. Retry Connect.";
                        notifyChanged();
                    }
                }, 15000);
            }
        } catch (RuntimeException error) {
            clearConnection();
            message = "Cannot connect to Shizuku: " + error.getMessage();
        }
        notifyChanged();
    }

    private void connect(Activity activity, boolean requestPermission) {
        connect(requestPermission);
        if (!Shizuku.pingBinder() && !requestingBinder
            && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            requestBinder(activity);
    }

    private void requestBinder(Activity activity) {
        try {
            int managerUid = context.getPackageManager()
                .getApplicationInfo(ShizukuProvider.MANAGER_APPLICATION_ID, 0).uid;
            final int currentRequest = ++binderRequest;
            Binder receiver = new Binder() {
                @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                    if (code != IBinder.FIRST_CALL_TRANSACTION)
                        return super.onTransact(code, data, reply, flags);
                    if (Binder.getCallingUid() != managerUid) return false;
                    IBinder binder = data.readStrongBinder();
                    main.post(() -> receiveBinder(currentRequest, binder));
                    return true;
                }
            };
            Bundle data = new Bundle();
            data.putBinder(TermuxConstants.SHIZUKU_BINDER_REQUEST_BINDER, receiver);
            Intent intent = new Intent(TermuxConstants.SHIZUKU_REQUEST_BINDER_ACTION)
                .setPackage(ShizukuProvider.MANAGER_APPLICATION_ID)
                .putExtra(TermuxConstants.SHIZUKU_BINDER_REQUEST_DATA, data);
            requestingBinder = true;
            message = "Requesting a connection from Shizuku...";
            // The manager's exported receiver handles this request and sends the binder back.
            activity.sendBroadcast(intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
            main.postDelayed(() -> {
                if (requestingBinder && currentRequest == binderRequest) {
                    requestingBinder = false;
                    message = "Shizuku did not respond within 15 seconds. Open Shizuku, then retry Connect.";
                    notifyChanged();
                }
            }, 15000);
        } catch (PackageManager.NameNotFoundException error) {
            message = "Install the Shizuku app, start its service, then return here.";
        } catch (RuntimeException error) {
            requestingBinder = false;
            message = "Cannot request a connection from Shizuku: " + error.getMessage();
        }
        notifyChanged();
    }

    @SuppressLint("RestrictedApi")
    private void receiveBinder(int currentRequest, IBinder binder) {
        if (!requestingBinder || currentRequest != binderRequest) return;
        if (binder == null || !binder.pingBinder()) {
            requestingBinder = false;
            message = "Shizuku is not running. Start it in Shizuku, then tap Connect.";
            notifyChanged();
            return;
        }
        // Use the same attachment handshake as ShizukuProvider; permission checks still apply.
        Shizuku.onBinderReceived(binder, context.getPackageName());
    }

    /** Return true when the caller may create its session now. */
    public boolean ensureReady(Activity activity, Runnable retry, Runnable recovery) {
        if (isReady()) return true;
        AlertDialog showing = prompt.get();
        if (showing != null && showing.isShowing()) return false;
        TextView status = new TextView(activity);
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        status.setPadding(padding, padding, padding, padding);
        status.setText(message);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Connect Shizuku")
            .setView(status).setPositiveButton("Connect", null)
            .setNeutralButton("Open Shizuku", null)
            .setNegativeButton("Recovery shell", (d, which) -> recovery.run()).create();
        Runnable changed = () -> {
            if (activity.isFinishing() || activity.isDestroyed()) { dialog.dismiss(); return; }
            status.setText(message);
            if (isReady()) { dialog.dismiss(); retry.run(); }
        };
        listeners.add(changed);
        prompt = new java.lang.ref.WeakReference<>(dialog);
        dialog.setOnDismissListener(d -> {
            listeners.remove(changed);
            if (prompt.get() == dialog) {
                prompt.clear();
                requestingBinder = false;
                requestPermissionOnConnect = false;
            }
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> connect(activity, true));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Intent intent = activity.getPackageManager().getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID);
            if (intent == null) status.setText("Install the Shizuku app, start its service, then return here.");
            else activity.startActivity(intent);
        });
        connect(activity, false);
        return false;
    }

    public void dismissPrompt() {
        AlertDialog dialog = prompt.get();
        if (dialog != null) dialog.dismiss();
    }

    public TerminalSessionProcess.Factory factory() {
        return (executable, cwd, argv, environment, rows, columns, width, height) -> {
            ISessionService current = service;
            if (current == null || !isReady())
                throw new IllegalStateException("Shizuku session service is unavailable");
            return new SessionProcess(current, executable, SessionStorage.workingDirectory(cwd), argv,
                environment, rows, columns, width, height);
        };
    }

    public com.termux.shared.shell.command.runner.app.AppShellProcess.Factory backgroundFactory() {
        return (command, environment, cwd) -> {
            ISessionService current = service;
            if (current == null || !isReady())
                throw new java.io.IOException("Shizuku is required. Open Termux and connect Shizuku before starting a background job.");
            final String workingDirectory;
            try {
                workingDirectory = SessionStorage.workingDirectory(cwd);
            } catch (Exception error) {
                throw new java.io.IOException("Cannot prepare background working directory", error);
            }
            return new BackgroundProcess(current, command, workingDirectory, environment);
        };
    }
}
