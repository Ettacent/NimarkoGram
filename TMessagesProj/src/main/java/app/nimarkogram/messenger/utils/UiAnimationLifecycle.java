/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import java.util.WeakHashMap;

public final class UiAnimationLifecycle implements Application.ActivityLifecycleCallbacks {
    private static boolean installed;
    private final WeakHashMap<Activity, Boolean> resumed = new WeakHashMap<>();

    private UiAnimationLifecycle() {}

    public static void install(Application application) {
        if (installed || application == null) return;
        application.registerActivityLifecycleCallbacks(new UiAnimationLifecycle());
        installed = true;
        UiAnimationClock.onActivityPaused();
    }

    @Override public void onActivityResumed(Activity activity) {
        resumed.put(activity, Boolean.TRUE);
        UiAnimationClock.onActivityResumed();
    }

    @Override public void onActivityPaused(Activity activity) {
        suspend(activity);
    }

    @Override public void onActivityStopped(Activity activity) {
        suspend(activity);
    }

    @Override public void onActivityDestroyed(Activity activity) {
        suspend(activity);
    }

    private void suspend(Activity activity) {
        resumed.remove(activity);
        if (resumed.isEmpty()) UiAnimationClock.onActivityPaused();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
}
