package com.termux.app.session;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.List;

/** Current-user discovery only; discovering a manager never grants permission. */
public final class ShizukuManagers {
    public static final String PLUS = "af.shizuku.plus.api";
    public static final String STOCK = "moe.shizuku.privileged.api";
    private ShizukuManagers() {}

    public static List<ApplicationInfo> installed(PackageManager packages) {
        List<ApplicationInfo> found = new ArrayList<>();
        for (String name : new String[]{PLUS, STOCK}) {
            try {
                ApplicationInfo info = packages.getApplicationInfo(name, 0);
                if (info.enabled) found.add(info);
            } catch (PackageManager.NameNotFoundException absent) { /* Try the next supported manager. */ }
        }
        return found;
    }

    public static Intent launchIntent(PackageManager packages) {
        for (ApplicationInfo info : installed(packages)) {
            Intent intent = packages.getLaunchIntentForPackage(info.packageName);
            if (intent != null) return intent;
        }
        return null;
    }
}
