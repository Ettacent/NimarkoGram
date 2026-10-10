/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.os.SystemClock;
import android.view.Choreographer;

import org.telegram.ui.Components.ForegroundDetector;

import java.util.ArrayList;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

public final class NimarkoUiAnimationClock {
    public interface ResumeGate {
        boolean canResume(Animator animator);
    }
    private static final State CLOCK = new State();
    private static final WeakHashMap<Animator, Boolean> ANIMATORS = new WeakHashMap<>();
    private static final WeakHashMap<Animator, WeakReference<ResumeGate>> RESUME_GATES = new WeakHashMap<>();
    private static ForegroundDetector detector;
    private static Choreographer.FrameCallback pendingResume;
    private static long resumeGeneration;
    private static boolean activityLifecycleKnown;
    private static boolean activityResumed;
    private static final AnimatorListenerAdapter CLEANUP = new AnimatorListenerAdapter() {
        @Override public void onAnimationEnd(Animator animator) {
            ANIMATORS.remove(animator);
            RESUME_GATES.remove(animator);
        }
    };
    private static boolean canResume() {
        return detector != null && detector.isForeground()
                && (!activityLifecycleKnown || activityResumed);
    }

    private static void requestResume() {
        if (!CLOCK.isPaused() || pendingResume != null || !canResume()) return;
        final long generation = ++resumeGeneration;
        pendingResume = timeNs -> resume(generation);
        Choreographer.getInstance().postFrameCallback(pendingResume);
    }

    private static void resume(long generation) {
        if (generation != resumeGeneration || pendingResume == null) return;
        pendingResume = null;
        if (!canResume()) return;
        CLOCK.resume(SystemClock.elapsedRealtime());
        for (Animator animator : new ArrayList<>(ANIMATORS.keySet())) {
            if (generation != resumeGeneration || CLOCK.isPaused() || !canResume()) return;
            if (!animator.isStarted()) {
                ANIMATORS.remove(animator);
                RESUME_GATES.remove(animator);
            }
            else if (Boolean.TRUE.equals(ANIMATORS.get(animator))) {
                resumeAnimation(animator);
            }
        }
    }

    private static void pause() {
        resumeGeneration++;
        if (pendingResume != null) {
            Choreographer.getInstance().removeFrameCallback(pendingResume);
            pendingResume = null;
        }
        CLOCK.pause(SystemClock.elapsedRealtime());
        for (Animator animator : new ArrayList<>(ANIMATORS.keySet())) pauseOwned(animator);
    }

    private NimarkoUiAnimationClock() {}

    public static void install(ForegroundDetector value) {
        if (detector != null || value == null) return;
        detector = value;
        value.addListener(new ForegroundDetector.Listener() {
            @Override public void onBecameForeground() {
                requestResume();
            }
            @Override public void onBecameBackground() {
                pause();
            }
        });
        if (value.isBackground()) CLOCK.pause(SystemClock.elapsedRealtime());
    }

    public static long now() { return CLOCK.now(SystemClock.elapsedRealtime()); }
    public static int epoch() { return CLOCK.epoch(); }
    public static boolean isPaused() { return CLOCK.isPaused(); }

    public static void onActivityPaused() {
        activityLifecycleKnown = true;
        activityResumed = false;
        pause();
    }

    public static void onActivityResumed() {
        activityLifecycleKnown = true;
        activityResumed = true;
        requestResume();
    }
    public static void track(Animator animator) {
        track(animator, null);
    }

    public static void track(Animator animator, ResumeGate gate) {
        if (animator == null) return;
        if (gate != null) RESUME_GATES.put(animator, new WeakReference<>(gate));
        if (ANIMATORS.containsKey(animator)) return;
        ANIMATORS.put(animator, false);
        animator.removeListener(CLEANUP);
        animator.addListener(CLEANUP);
        if (CLOCK.isPaused()) pauseOwned(animator);
    }

    public static boolean resumeAnimation(Animator animator) {
        if (animator == null || !animator.isStarted() || CLOCK.isPaused()) return false;
        WeakReference<ResumeGate> reference = RESUME_GATES.get(animator);
        if (reference != null) {
            ResumeGate gate = reference.get();
            if (gate == null || !gate.canResume(animator) || CLOCK.isPaused()) return false;
        }
        if (ANIMATORS.containsKey(animator)) ANIMATORS.put(animator, false);
        if (animator.isPaused()) animator.resume();
        return true;
    }

    public static void resumeOwned(Animator animator) {
        if (Boolean.TRUE.equals(ANIMATORS.get(animator))) resumeAnimation(animator);
    }
    private static void pauseOwned(Animator animator) {
        if (!animator.isStarted()) {
            ANIMATORS.remove(animator);
            RESUME_GATES.remove(animator);
        }
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
