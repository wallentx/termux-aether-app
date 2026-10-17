package com.termux.app.session;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import com.termux.shared.termux.TermuxConstants;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.util.ReflectionHelpers;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = SessionManagerTest.ShadowShizuku.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SessionManagerTest {
    private static final int MANAGER_UID = 12345;
    private Activity activity;
    private ShadowActivity activityShadow;
    private ShadowApplication applicationShadow;
    private ShadowPackageManager packages;
    private ShadowLooper main;
    private SessionManager manager;
    private AlertDialog dialog;
    private int retries;
    private int requestsRead;

    @Before public void setUp() {
        ShadowShizuku.reset();
        ReflectionHelpers.setStaticField(SessionManager.class, "instance", null);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activityShadow = Shadow.extract(activity);
        applicationShadow = Shadow.extract(activity.getApplication());
        packages = Shadow.extract(activity.getPackageManager());
        main = Shadow.extract(Looper.getMainLooper());
        activity.getApplicationInfo().flags |= ApplicationInfo.FLAG_DEBUGGABLE;
        PackageInfo info = new PackageInfo();
        info.packageName = ShizukuProvider.MANAGER_APPLICATION_ID;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = info.packageName;
        info.applicationInfo.uid = MANAGER_UID;
        packages.installPackage(info);
        ShadowBinder.setCallingUid(MANAGER_UID);
        manager = SessionManager.get(activity);
    }

    @Test public void startupRequestsMissingBinderAndContinuesAuthorizedSession() throws Exception {
        showPrompt();
        Intent request = nextRequest();
        assertEquals(ShizukuProvider.MANAGER_APPLICATION_ID, request.getPackage());
        assertEquals(TermuxConstants.SHIZUKU_REQUEST_BINDER_ACTION, request.getAction());
        assertNotEquals(0, request.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        assertTrue(reply(request, new Binder()));
        assertEquals(activity.getPackageName(), ShadowShizuku.attachedPackage);
        assertEquals(1, ShadowShizuku.binds);
        assertTrue(manager.isReady());
        assertFalse(dialog.isShowing());
        assertEquals(1, retries);
        assertNull(activityShadow.getNextStartedActivity());
    }

    @Test public void connectRequestsAgainAfterManagerReportsStopped() throws Exception {
        showPrompt();
        reply(nextRequest(), null);
        assertTrue(message().contains("not running"));
        assertEquals(0, ShadowShizuku.binds);
        clickConnect();
        reply(nextRequest(), new Binder());
        assertTrue(manager.isReady());
        assertEquals(1, retries);
    }

    @Test public void connectRetainsPermissionRequestAcrossBinderDiscovery() throws Exception {
        ShadowShizuku.permission = PackageManager.PERMISSION_DENIED;
        showPrompt();
        reply(nextRequest(), null);
        clickConnect();
        reply(nextRequest(), new Binder());
        assertEquals(1, ShadowShizuku.permissionRequests);
        assertEquals(0, ShadowShizuku.binds);
        assertFalse(manager.isReady());
        ShadowShizuku.permission = PackageManager.PERMISSION_GRANTED;
        ShadowShizuku.permissionListener.onRequestPermissionResult(ShadowShizuku.permissionCode,
            PackageManager.PERMISSION_GRANTED);
        main.idle();
        assertTrue(manager.isReady());
        assertEquals(1, retries);
    }

    @Test public void deniedPermissionDoesNotStartSession() throws Exception {
        ShadowShizuku.permission = PackageManager.PERMISSION_DENIED;
        showPrompt();
        reply(nextRequest(), new Binder());
        assertEquals(0, ShadowShizuku.permissionRequests);
        clickConnect();
        ShadowShizuku.permissionListener.onRequestPermissionResult(ShadowShizuku.permissionCode,
            PackageManager.PERMISSION_DENIED);
        main.idle();
        assertTrue(message().contains("denied"));
        assertEquals(0, ShadowShizuku.binds);
        assertEquals(0, retries);
    }

    @Test public void discoveryTimesOutAndIgnoresOldReplyAfterRetry() throws Exception {
        showPrompt();
        Intent oldRequest = nextRequest();
        clickConnect();
        assertTrue(message().contains("Requesting a connection"));
        assertNull(activityShadow.getNextStartedActivity());
        assertNoRequest();
        main.idleFor(Duration.ofSeconds(15));
        assertTrue(message().contains("did not respond"));
        clickConnect();
        Intent newRequest = nextRequest();
        reply(oldRequest, new Binder());
        assertFalse(manager.isReady());
        reply(newRequest, new Binder());
        assertTrue(manager.isReady());
        main.idleFor(Duration.ofSeconds(15));
        assertEquals("Connected.", message());
        assertEquals(1, retries);
    }

    @Test public void rejectsBinderFromAnotherUid() throws Exception {
        showPrompt();
        Intent request = nextRequest();
        ShadowBinder.setCallingUid(MANAGER_UID + 1);
        assertFalse(reply(request, new Binder()));
        assertFalse(manager.isReady());
        assertNull(ShadowShizuku.attachedPackage);
        ShadowBinder.setCallingUid(MANAGER_UID);
        reply(request, new Binder());
        assertTrue(manager.isReady());
    }

    @Test public void existingBinderDoesNotOpenManager() {
        ShadowShizuku.binder = new Binder();
        showPrompt();
        main.idle();
        assertTrue(manager.isReady());
        assertNull(activityShadow.getNextStartedActivity());
        assertNoRequest();
        assertEquals(1, retries);
    }

    @Test public void missingManagerShowsInstallationGuidance() {
        packages.removePackage(ShizukuProvider.MANAGER_APPLICATION_ID);
        showPrompt();
        assertTrue(message().contains("Install the Shizuku app"));
        assertNull(activityShadow.getNextStartedActivity());
        assertNoRequest();
        clickConnect();
        assertTrue(message().contains("Install the Shizuku app"));
    }

    @Test public void dismissedPromptIgnoresPendingDiscovery() throws Exception {
        ShadowShizuku.permission = PackageManager.PERMISSION_DENIED;
        showPrompt();
        Intent request = nextRequest();
        clickConnect();
        manager.dismissPrompt();
        main.idle();
        reply(request, new Binder());
        main.idleFor(Duration.ofSeconds(15));
        assertNull(ShadowShizuku.attachedPackage);
        assertEquals(0, ShadowShizuku.permissionRequests);
        assertEquals(0, retries);
    }

    @Test public void nonDebuggableBuildDoesNotRequestBinder() {
        activity.getApplicationInfo().flags &= ~ApplicationInfo.FLAG_DEBUGGABLE;
        showPrompt();
        assertTrue(message().contains("Install a debuggable Aether build"));
        assertNull(activityShadow.getNextStartedActivity());
        assertNoRequest();
    }

    @Test public void staleDisconnectAndDeathDoNotClearReconnectedService() {
        ShadowShizuku.binder = new Binder();
        ShadowShizuku.autoConnect = false;
        showPrompt();
        ServiceConnection old = ShadowShizuku.connections.get(0);
        DeathTrackingBinder oldBinder = new DeathTrackingBinder();
        old.onServiceConnected(serviceName(), oldBinder);
        main.idle();
        assertTrue(manager.isReady());
        IBinder.DeathRecipient oldDeath = oldBinder.recipient;

        old.onServiceDisconnected(serviceName());
        main.idle();
        assertFalse(manager.isReady());
        assertEquals(1, ShadowShizuku.detached.size());
        assertSame(old, ShadowShizuku.detached.get(0));
        showPrompt();
        ServiceConnection current = ShadowShizuku.connections.get(1);
        assertNotSame(old, current);
        Binder currentBinder = new Binder();
        current.onServiceConnected(serviceName(), currentBinder);
        main.idle();

        old.onServiceDisconnected(serviceName());
        oldDeath.binderDied();
        main.idle();
        assertConnectedTo(currentBinder);
        assertEquals(2, retries);
    }

    @Test public void timedOutConnectionCannotReplaceNewService() {
        ShadowShizuku.binder = new Binder();
        ShadowShizuku.autoConnect = false;
        showPrompt();
        ServiceConnection old = ShadowShizuku.connections.get(0);
        main.idleFor(Duration.ofSeconds(15));
        assertTrue(message().contains("did not connect"));
        assertSame(old, ShadowShizuku.detached.get(0));
        clickConnect();
        ServiceConnection current = ShadowShizuku.connections.get(1);

        old.onServiceConnected(serviceName(), new Binder());
        main.idle();
        assertFalse(manager.isReady());
        assertTrue(dialog.isShowing());
        Binder currentBinder = new Binder();
        current.onServiceConnected(serviceName(), currentBinder);
        main.idle();
        old.onServiceConnected(serviceName(), new Binder());
        old.onServiceDisconnected(serviceName());
        main.idleFor(Duration.ofSeconds(15));
        assertConnectedTo(currentBinder);
        assertEquals(1, retries);
    }

    @Test public void oldTimeoutDoesNotCancelPendingRetry() {
        ShadowShizuku.binder = new Binder();
        ShadowShizuku.autoConnect = false;
        showPrompt();
        ServiceConnection old = ShadowShizuku.connections.get(0);
        main.idleFor(Duration.ofSeconds(6));
        old.onServiceDisconnected(serviceName());
        main.idle();
        clickConnect();
        ServiceConnection current = ShadowShizuku.connections.get(1);
        main.idleFor(Duration.ofSeconds(9));
        assertTrue(message().contains("Connecting to the session service"));
        assertEquals(1, ShadowShizuku.detached.size());
        Binder currentBinder = new Binder();
        current.onServiceConnected(serviceName(), currentBinder);
        main.idle();
        assertConnectedTo(currentBinder);
    }

    @Test public void shizukuDeathDiscardsPendingConnection() {
        ShadowShizuku.binder = new Binder();
        ShadowShizuku.autoConnect = false;
        showPrompt();
        ServiceConnection old = ShadowShizuku.connections.get(0);
        ShadowShizuku.binder = null;
        ShadowShizuku.deadListener.onBinderDead();
        main.idle();
        assertTrue(message().contains("Shizuku stopped"));
        assertSame(old, ShadowShizuku.detached.get(0));
        old.onServiceConnected(serviceName(), new Binder());
        main.idle();
        assertNull(ReflectionHelpers.getField(manager, "service"));

        ShadowShizuku.binder = new Binder();
        clickConnect();
        Binder currentBinder = new Binder();
        ShadowShizuku.connections.get(1).onServiceConnected(serviceName(), currentBinder);
        main.idle();
        assertConnectedTo(currentBinder);
    }

    @Test public void bindingFailureDiscardsQueuedConnection() {
        ShadowShizuku.binder = new Binder();
        ShadowShizuku.failBind = true;
        showPrompt();
        main.idle();
        assertFalse(manager.isReady());
        assertTrue(message().contains("Cannot connect to Shizuku"));
        assertSame(ShadowShizuku.connections.get(0), ShadowShizuku.detached.get(0));
        ShadowShizuku.failBind = false;
        clickConnect();
        main.idle();
        assertTrue(manager.isReady());
        assertEquals(1, retries);
    }

    private static ComponentName serviceName() { return new ComponentName("test", "SessionUserService"); }

    private void assertConnectedTo(IBinder binder) {
        assertTrue(manager.isReady());
        ISessionService service = ReflectionHelpers.getField(manager, "service");
        assertSame(binder, service.asBinder());
        assertEquals("Connected.", message());
    }

    private void showPrompt() {
        assertFalse(manager.ensureReady(activity, () -> retries++, () -> fail("Unexpected recovery")));
        dialog = ShadowAlertDialog.getLatestAlertDialog();
    }

    private Intent nextRequest() {
        List<Intent> broadcasts = applicationShadow.getBroadcastIntents();
        assertTrue("Expected a Shizuku binder request broadcast", requestsRead < broadcasts.size());
        return broadcasts.get(requestsRead++);
    }

    private void assertNoRequest() { assertEquals(requestsRead, applicationShadow.getBroadcastIntents().size()); }

    private void clickConnect() { dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); }

    private String message() { return ReflectionHelpers.getField(manager, "message"); }

    private boolean reply(Intent request, IBinder binder) throws Exception {
        IBinder receiver = request.getBundleExtra(TermuxConstants.SHIZUKU_BINDER_REQUEST_DATA)
            .getBinder(TermuxConstants.SHIZUKU_BINDER_REQUEST_BINDER);
        Parcel data = Parcel.obtain();
        try {
            data.writeStrongBinder(binder);
            data.writeString("/unused/manager.apk");
            boolean result = receiver.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY);
            main.idle();
            return result;
        } finally {
            data.recycle();
        }
    }

    private static class DeathTrackingBinder extends Binder {
        IBinder.DeathRecipient recipient;
        @Override public void linkToDeath(IBinder.DeathRecipient recipient, int flags) { this.recipient = recipient; }
        @Override public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
            this.recipient = null;
            return true;
        }
    }

    @Implements(Shizuku.class)
    public static class ShadowShizuku {
        static IBinder binder;
        static String attachedPackage;
        static int permission, permissionRequests, permissionCode, binds;
        static boolean autoConnect, failBind;
        static final List<ServiceConnection> connections = new ArrayList<>();
        static final List<ServiceConnection> detached = new ArrayList<>();
        static Shizuku.OnBinderReceivedListener binderListener;
        static Shizuku.OnBinderDeadListener deadListener;
        static Shizuku.OnRequestPermissionResultListener permissionListener;

        static void reset() {
            binder = null;
            attachedPackage = null;
            permission = PackageManager.PERMISSION_GRANTED;
            permissionRequests = permissionCode = binds = 0;
            autoConnect = true;
            failBind = false;
            connections.clear();
            detached.clear();
            binderListener = null;
            deadListener = null;
            permissionListener = null;
        }

        @Implementation protected static boolean pingBinder() { return binder != null; }
        @Implementation protected static int checkSelfPermission() { return permission; }
        @Implementation protected static void addBinderReceivedListenerSticky(Shizuku.OnBinderReceivedListener listener) {
            binderListener = listener;
            if (binder != null) listener.onBinderReceived();
        }
        @Implementation protected static void addBinderDeadListener(Shizuku.OnBinderDeadListener listener) {
            deadListener = listener;
        }
        @Implementation protected static void addRequestPermissionResultListener(Shizuku.OnRequestPermissionResultListener listener) {
            permissionListener = listener;
        }
        @Implementation protected static void onBinderReceived(IBinder received, String packageName) {
            binder = received;
            attachedPackage = packageName;
            binderListener.onBinderReceived();
        }
        @Implementation protected static void requestPermission(int code) {
            permissionCode = code;
            permissionRequests++;
        }
        @Implementation protected static void bindUserService(Shizuku.UserServiceArgs args, ServiceConnection connection) {
            binds++;
            connections.add(connection);
            if (autoConnect) connection.onServiceConnected(serviceName(), new Binder());
            if (failBind) throw new IllegalStateException("Test bind failure");
        }
        @Implementation protected static void unbindUserService(Shizuku.UserServiceArgs args, ServiceConnection connection,
            boolean remove) {
            assertFalse("Detaching a callback must not stop running sessions", remove);
            detached.add(connection);
        }
    }
}
