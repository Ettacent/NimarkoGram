/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Build;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;

public final class OrientationController {

    public interface Request {
        void release();
    }

    private static final int LARGE_SCREEN_DP = 600;
    private static final int NONE = Integer.MIN_VALUE;

    private final Activity activity;
    private final ArrayList<Request> portraitRequests = new ArrayList<>();
    private int baseOrientation = NONE;

    OrientationController(Activity activity) {
        this.activity = activity;
    }

    @Nullable
    public static OrientationController of(@Nullable Activity activity) {
        return activity instanceof LaunchActivity ? ((LaunchActivity) activity).getOrientationController() : null;
    }

    public Request requestPortrait() {
        final Request request = new Request() {
            @Override
            public void release() {
                if (portraitRequests.remove(this)) {
                    update();
                }
            }
        };
        portraitRequests.add(request);
        update();
        return request;
    }

    void update() {
        final boolean portrait = !portraitRequests.isEmpty() && canFixOrientation();
        try {
            if (portrait) {
                if (baseOrientation == NONE) {
                    baseOrientation = activity.getRequestedOrientation();
                }
                if (activity.getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            } else if (baseOrientation != NONE) {
                activity.setRequestedOrientation(baseOrientation);
                baseOrientation = NONE;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private boolean canFixOrientation() {
        if (activity.getResources().getConfiguration().smallestScreenWidthDp >= LARGE_SCREEN_DP) {
            return false;
        }
        return Build.VERSION.SDK_INT < 24 || !activity.isInMultiWindowMode();
    }
}
