/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.pip;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.utils.WindowVisibilityManager;

import java.util.ArrayList;

public final class PipCoordinator {
    static final String TAG = "PIP_DEBUG";

    private static PipCoordinator instance;

    public static PipCoordinator getInstance() {
        if (instance == null) {
            instance = new PipCoordinator();
        }
        return instance;
    }

    private final ArrayList<PipSession> sessions = new ArrayList<>();
    private final ArrayList<ResumeAction> onResumeActions = new ArrayList<>();

    private static final class ResumeAction {
        final PipSession session;
        final Activity activity;
        final int generation;
        final Runnable action;
        final Runnable cancelled;

        ResumeAction(PipSession session, Activity activity, int generation, Runnable action, Runnable cancelled) {
            this.session = session;
            this.activity = activity;
            this.generation = generation;
            this.action = action;
            this.cancelled = cancelled;
        }
    }
    private final SystemHost systemHost = new SystemHost(this);

    private @Nullable Activity activity;
    private boolean activityStarted;
    private boolean activityResumed;
    private int activityGeneration;

    private PipCoordinator() {}

    public @NonNull PipSession register(@NonNull PipContent content) {
        final PipSession session = new PipSession(this, content);
        sessions.add(session);
        log("register " + content);
        invalidateSystem();
        return session;
    }

    public @Nullable PipSession findFloating() {
        for (PipSession session : new ArrayList<>(sessions)) {
            if (!isRegistered(session)) continue;
            if (session.isFloating()) {
                return session;
            }
        }
        return null;
    }

    @Nullable Activity getActivity() {
        return activity;
    }

    ArrayList<PipSession> getSessions() {
        return sessions;
    }

    boolean isRegistered(PipSession session) {
        return session.isAlive() && sessions.contains(session);
    }

    void cancelResumeActions(PipSession session) {
        for (int i = onResumeActions.size() - 1; i >= 0; i--) {
            if (onResumeActions.get(i).session == session) onResumeActions.remove(i).cancelled.run();
        }
    }

    private void cancelAllResumeActions() {
        final ArrayList<ResumeAction> actions = new ArrayList<>(onResumeActions);
        onResumeActions.clear();
        for (ResumeAction action : actions) action.cancelled.run();
    }

    boolean isActivityStarted() {
        return activityStarted;
    }

    void dismissOtherFloating(PipSession except) {
        for (PipSession session : new ArrayList<>(sessions)) {
            if (!isRegistered(session)) continue;
            if (session != except && session.isFloating()) {
                session.dismiss();
            }
        }
    }

    void onSessionDismissed(PipSession session, int prevState) {
        log("dismissed, prevState=" + prevState);
        sessions.remove(session);
        cancelResumeActions(session);
        systemHost.onSessionDismissed(session, prevState);
        invalidateSystem();
    }

    boolean enterSystemPip(PipSession session) {
        return systemHost.enterNow(session);
    }

    public static boolean canEnterSystemPip() {
        return PipEnvironment.canUseSystemPip(ApplicationLoader.applicationContext);
    }

    void invalidateSystem() {
        systemHost.update();
    }

    boolean runWhenAppResumed(PipSession session, Runnable action, Runnable cancelled) {
        if (!isRegistered(session) || activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (activityResumed && !AndroidUtilities.isInPictureInPictureMode(activity)) {
            action.run();
            return true;
        }
        onResumeActions.add(new ResumeAction(session, activity, activityGeneration, action, cancelled));
        if (activity != null && !activityStarted) {
            try {
                final Intent intent = new Intent(ApplicationLoader.applicationContext, activity.getClass());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ApplicationLoader.applicationContext.startActivity(intent);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return true;
    }

    public void onActivityCreate(@NonNull Activity activity, @Nullable WindowVisibilityManager.Controller contentVisibility) {
        final int generation = ++activityGeneration;
        cancelAllResumeActions();
        if (this.activity != null && this.activity != activity) {
            systemHost.detach();
        }
        if (activityGeneration != generation) return;
        this.activity = activity;
        activityStarted = false;
        activityResumed = false;
        systemHost.attach(activity, contentVisibility);
        PipEnvironment.startWatching(ApplicationLoader.applicationContext, this::onPermissionsChanged);
    }

    private void onPermissionsChanged() {
        for (PipSession session : new ArrayList<>(sessions)) {
            if (!isRegistered(session)) continue;
            if (session.floating != null && session.state == PipSession.STATE_FLOATING) {
                session.floating.checkWindowType();
            }
        }
        invalidateSystem();
    }

    public void onActivityStart(@NonNull Activity activity) {
        if (activity != this.activity) {
            return;
        }
        activityStarted = true;
        for (PipSession session : new ArrayList<>(sessions)) {
            if (this.activity != activity) return;
            if (!isRegistered(session)) continue;
            if (session.state == PipSession.STATE_FLOATING && session.floating != null && !session.floating.isOverlay()) {
                session.floating.onAppShown();
            }
        }
    }

    public void onActivityResume(@NonNull Activity activity) {
        if (activity != this.activity) {
            return;
        }
        activityResumed = true;
        systemHost.onResume();
        for (PipSession session : new ArrayList<>(sessions)) {
            if (this.activity != activity) return;
            if (!isRegistered(session)) continue;
            if (session.floating != null && session.state == PipSession.STATE_FLOATING) {
                session.floating.checkWindowType();
            }
        }
        if (!onResumeActions.isEmpty()) {
            final ArrayList<ResumeAction> actions = new ArrayList<>(onResumeActions);
            onResumeActions.clear();
            for (ResumeAction action : actions) {
                if (this.activity == activity && action.activity == activity && action.generation == activityGeneration
                        && activityResumed && isRegistered(action.session) && !activity.isFinishing() && !activity.isDestroyed()) {
                    action.action.run();
                } else {
                    action.cancelled.run();
                }
            }
        }
        invalidateSystem();
    }

    public void onActivityPause(@NonNull Activity activity) {
        if (activity != this.activity) {
            return;
        }
        activityResumed = false;
        systemHost.onPause();
        for (PipSession session : new ArrayList<>(sessions)) {
            if (this.activity != activity) return;
            if (!isRegistered(session)) continue;
            if (session.state == PipSession.STATE_FLOATING && session.floating != null && !session.floating.isOverlay()) {
                session.floating.onAppHidden();
            }
        }
    }

    public void onActivityStop(@NonNull Activity activity) {
        if (activity != this.activity) {
            return;
        }
        activityStarted = false;
        systemHost.onStop();
        for (PipSession session : new ArrayList<>(sessions)) {
            if (this.activity != activity) return;
            if (!isRegistered(session)) continue;
            if (session.state == PipSession.STATE_FLOATING && session.floating != null && !session.floating.isOverlay()) {
                session.content.pause();
                session.invalidate();
            }
        }
    }

    public void onActivityDestroy(@NonNull Activity activity) {
        if (this.activity != activity) {
            return;
        }
        final int generation = ++activityGeneration;
        activityStarted = false;
        activityResumed = false;
        cancelAllResumeActions();
        for (PipSession session : new ArrayList<>(sessions)) {
            if (this.activity != activity || activityGeneration != generation) return;
            if (isRegistered(session)) session.dismiss();
        }
        if (this.activity != activity || activityGeneration != generation) return;
        systemHost.detach();
        if (this.activity == activity && activityGeneration == generation) this.activity = null;
    }

    public void onUserLeaveHint() {
        systemHost.onUserLeaveHint();
    }

    public void onPictureInPictureRequested() {
        systemHost.onUserLeaveHint();
    }

    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        systemHost.onPictureInPictureModeChanged(isInPictureInPictureMode);
    }

    public void onPictureInPictureUiStateChanged(@NonNull Object pipState) {
        systemHost.onPictureInPictureUiStateChanged(pipState);
    }

    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (activity != null && AndroidUtilities.isInPictureInPictureMode(activity)) {
            return;
        }
        for (PipSession session : new ArrayList<>(sessions)) {
            if (!isRegistered(session)) continue;
            if (session.floating != null) {
                session.floating.onConfigurationChanged();
            }
        }
    }

    static void log(String message) {
        Log.d(TAG, "[utils.pip] " + message);
    }
}
