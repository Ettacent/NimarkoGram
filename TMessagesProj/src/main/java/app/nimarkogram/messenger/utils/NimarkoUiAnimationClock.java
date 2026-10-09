/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.SystemClock;
import android.view.Choreographer;

import org.telegram.ui.Components.ForegroundDetector;

import java.util.ArrayList;
import java.util.WeakHashMap;

public final class NimarkoUiAnimationClock {
    private static final State CLOCK = new State();
    private static final WeakHashMap<Animator, Boolean> ANIMATORS = new WeakHashMap<>();
    private static ForegroundDetector detector;
    private static boolean resumePending;
    private static final AnimatorListenerAdapter CLEANUP = new AnimatorListenerAdapter() {
        @Override public void onAnimationEnd(Animator animator) { ANIMATORS.remove(animator); }
    };
    private static final Choreographer.FrameCallback RESUME = timeNs -> {
        resumePending = false;
        if (detector == null || !detector.isForeground()) return;
        CLOCK.resume(SystemClock.elapsedRealtime());
        for (Animator animator : new ArrayList<>(ANIMATORS.keySet())) {
            if (!animator.isStarted()) ANIMATORS.remove(animator);
            else if (Boolean.TRUE.equals(ANIMATORS.get(animator))) {
                ANIMATORS.put(animator, false);
                if (animator.isPaused()) animator.resume();
            }
        }
    };

    private NimarkoUiAnimationClock() {}

    public static void install(ForegroundDetector value) {
        if (detector != null || value == null) return;
        detector = value;
        value.addListener(new ForegroundDetector.Listener() {
            @Override public void onBecameForeground() {
                if (CLOCK.isPaused() && !resumePending) {
                    resumePending = true;
                    Choreographer.getInstance().postFrameCallback(RESUME);
                }
            }
            @Override public void onBecameBackground() {
                if (resumePending) Choreographer.getInstance().removeFrameCallback(RESUME);
                resumePending = false;
                CLOCK.pause(SystemClock.elapsedRealtime());
                for (Animator animator : new ArrayList<>(ANIMATORS.keySet())) pauseOwned(animator);
            }
        });
        if (value.isBackground()) CLOCK.pause(SystemClock.elapsedRealtime());
    }

    public static long now() { return CLOCK.now(SystemClock.elapsedRealtime()); }
    public static int epoch() { return CLOCK.epoch(); }
    public static boolean isPaused() { return CLOCK.isPaused(); }

    public static void track(Animator animator) {
        if (animator == null || ANIMATORS.containsKey(animator)) return;
        ANIMATORS.put(animator, false);
        animator.removeListener(CLEANUP);
        animator.addListener(CLEANUP);
        if (CLOCK.isPaused()) pauseOwned(animator);
    }

    private static void pauseOwned(Animator animator) {
        if (!animator.isStarted()) ANIMATORS.remove(animator);
        else if (!animator.isPaused()) {
            ANIMATORS.put(animator, true);
            animator.pause();
        }
    }

    static final class State {
        private long pausedAt;
        private long excluded;
        private boolean paused;
        private int epoch;

        synchronized long now(long raw) { return (paused ? pausedAt : raw) - excluded; }
        synchronized void pause(long raw) {
            if (paused) return;
            pausedAt = raw;
            paused = true;
        }
        synchronized void resume(long raw) {
            if (!paused) return;
            excluded += Math.max(0L, raw - pausedAt);
            paused = false;
            epoch++;
        }
        synchronized boolean isPaused() { return paused; }
        synchronized int epoch() { return epoch; }
    }
}
