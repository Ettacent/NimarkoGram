/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.pip;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.Utilities;

public final class PipSession {
    private static int nextActionToken;
    final int actionToken = ++nextActionToken;
    public static final int STATE_PRIMARY = 0;
    public static final int STATE_FLOATING = 1;
    public static final int STATE_SYSTEM = 2;
    public static final int STATE_DISMISSED = 3;

    public final PipContent content;
    final PipCoordinator coordinator;

    int state = STATE_PRIMARY;
    int stateBeforeSystem = STATE_PRIMARY;
    @Nullable FloatingHost floating;
    boolean ownFloating;
    boolean ownFloatingOverlay;
    private int expandGeneration;
    private boolean expandPending;

    PipSession(PipCoordinator coordinator, PipContent content) {
        this.coordinator = coordinator;
        this.content = content;
    }

    public int getState() {
        return state;
    }

    public boolean isFloating() {
        return floating != null && (state == STATE_FLOATING || state == STATE_SYSTEM && stateBeforeSystem == STATE_FLOATING);
    }

    public void setOwnFloating(boolean shown, boolean overlay) {
        if (state == STATE_DISMISSED) {
            return;
        }
        ownFloating = shown;
        ownFloatingOverlay = overlay;
        if (state == STATE_SYSTEM) {
            stateBeforeSystem = shown ? STATE_FLOATING : STATE_PRIMARY;
        } else {
            state = shown ? STATE_FLOATING : STATE_PRIMARY;
        }
        coordinator.invalidateSystem();
    }

    public boolean isAlive() {
        return state != STATE_DISMISSED;
    }

    public @Nullable PipTextureView startFloating(@NonNull Activity activity, @Nullable Bitmap placeholder, boolean animateAppear) {
        if (state != STATE_PRIMARY) {
            throw new IllegalStateException("startFloating in state " + state);
        }
        coordinator.dismissOtherFloating(this);
        if (state != STATE_PRIMARY || !coordinator.isRegistered(this)) return null;
        cancelPendingExpand();
        floating = new FloatingHost(this, activity);
        final PipTextureView target = floating.show(placeholder, animateAppear);
        state = STATE_FLOATING;
        coordinator.invalidateSystem();
        return target;
    }

    public boolean getFloatingRect(@NonNull RectF out) {
        if (floating == null) {
            return false;
        }
        floating.getRect(out);
        return true;
    }

    public static void getFloatingTargetRect(int contentWidth, int contentHeight, @NonNull RectF out) {
        FloatingHost.getTargetRect(contentWidth, contentHeight, out);
    }

    public static float getFloatingCornerRadius() {
        return FloatingHost.getCornerRadius();
    }

    public void expand() {
        if (state != STATE_FLOATING || expandPending) {
            return;
        }
        final int generation = ++expandGeneration;
        expandPending = true;
        if (!coordinator.runWhenAppResumed(this, () -> {
            if (generation != expandGeneration) return;
            expandPending = false;
            if (state == STATE_FLOATING && coordinator.isRegistered(this)) {
                content.onExpandRequested();
            }
        }, () -> {
            if (generation == expandGeneration) {
                ++expandGeneration;
                expandPending = false;
            }
        })) expandPending = false;
    }

    private void cancelPendingExpand() {
        ++expandGeneration;
        expandPending = false;
        coordinator.cancelResumeActions(this);
    }

    public void completeExpand(@NonNull Utilities.Callback<SurfaceTexture> onTexture) {
        if (state != STATE_FLOATING || floating == null) {
            return;
        }
        cancelPendingExpand();
        final FloatingHost host = floating;
        floating = null;
        state = STATE_PRIMARY;
        host.removeForExpand(onTexture);
        coordinator.invalidateSystem();
    }

    public void dismiss() {
        if (state == STATE_DISMISSED) {
            return;
        }
        cancelPendingExpand();
        final int prevState = state;
        state = STATE_DISMISSED;
        if (floating != null) {
            final FloatingHost host = floating;
            floating = null;
            host.dismiss(prevState == STATE_FLOATING, content::onDismissed);
            coordinator.onSessionDismissed(this, prevState);
        } else {
            content.onDismissed();
            coordinator.onSessionDismissed(this, prevState);
        }
    }

    public void release() {
        if (state == STATE_DISMISSED) {
            return;
        }
        if (state != STATE_PRIMARY) {
            dismiss();
            return;
        }
        cancelPendingExpand();
        state = STATE_DISMISSED;
        coordinator.onSessionDismissed(this, STATE_PRIMARY);
    }

    public boolean enterSystemPip() {
        return state != STATE_DISMISSED && coordinator.enterSystemPip(this);
    }

    public void invalidate() {
        if (floating != null) {
            floating.invalidateState();
        }
        coordinator.invalidateSystem();
    }

    public void onVideoCompleted() {
        if (floating != null) {
            floating.onVideoCompleted();
        }
        coordinator.invalidateSystem();
    }

    public void onRewindStart(boolean forward) {
        if (floating != null) {
            floating.onRewindStart(forward);
        }
    }

    public void onRewindProgress(long timeDiff, float progress, boolean byBackSeek) {
        if (floating != null) {
            floating.onRewindProgress(progress, byBackSeek);
        }
    }

    public void onRewindCanceled() {
        if (floating != null) {
            floating.onRewindCanceled();
        }
    }
}
