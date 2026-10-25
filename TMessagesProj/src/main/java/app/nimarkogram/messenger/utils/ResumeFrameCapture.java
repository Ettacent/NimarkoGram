/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.function.BooleanSupplier;

final class ResumeFrameCapture {
    private final View root;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ViewTreeObserver observer;
    private ViewTreeObserver.OnDrawListener drawListener;
    private Runnable commitCallback;
    private Runnable timeout;
    private int generation;
    private int phase;
    private long deadline;

    ResumeFrameCapture(View root) {
        this.root = root;
    }

    void start(Runnable prepare, BooleanSupplier unchanged, Runnable copy, Runnable rejected) {
        stop();
        if (Build.VERSION.SDK_INT < 29 || !root.isAttachedToWindow()
                || !root.isHardwareAccelerated()) {
            rejected.run();
            return;
        }
        int token = generation;
        observer = root.getViewTreeObserver();
        if (!observer.isAlive()) {
            rejected.run();
            return;
        }
        phase = 1;
        deadline = SystemClock.uptimeMillis() + 250;
        timeout = () -> {
            if (token != generation) return;
            stop();
            rejected.run();
        };
        drawListener = () -> {
            if (token != generation || phase == 0) return;
            if (SystemClock.uptimeMillis() >= deadline) {
                phase = 0;
                handler.post(timeout);
                return;
            }
            if (phase != 1) {
                if (!unchanged.getAsBoolean()) {
                    phase = 0;
                    handler.post(timeout);
                }
                return;
            }
            phase = 2;
            prepare.run();
        };
        commitCallback = () -> handler.post(() -> {
            if (token != generation || phase != 2) return;
            if (SystemClock.uptimeMillis() >= deadline || !unchanged.getAsBoolean()) {
                timeout.run();
                return;
            }
            phase = 3;
            copy.run();
        });
        observer.addOnDrawListener(drawListener);
        observer.registerFrameCommitCallback(commitCallback);
        handler.postDelayed(timeout, 250);
        root.postInvalidateOnAnimation();
    }

    boolean awaitingFrame() {
        return phase == 1 || phase == 2;
    }

    boolean committed() {
        return phase == 3 && SystemClock.uptimeMillis() < deadline;
    }

    void stop() {
        generation++;
        phase = 0;
        if (timeout != null) handler.removeCallbacks(timeout);
        final ViewTreeObserver previous = observer;
        final ViewTreeObserver.OnDrawListener listener = drawListener;
        if (previous != null) {
            if (Build.VERSION.SDK_INT >= 29 && commitCallback != null && previous.isAlive()) {
                previous.unregisterFrameCommitCallback(commitCallback);
            } else if (Build.VERSION.SDK_INT >= 29 && commitCallback != null
                    && root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().unregisterFrameCommitCallback(commitCallback);
            }
            if (listener != null) handler.post(() -> {
                if (previous.isAlive()) previous.removeOnDrawListener(listener);
                else if (root.getViewTreeObserver().isAlive()) {
                    root.getViewTreeObserver().removeOnDrawListener(listener);
                }
            });
        }
        observer = null;
        drawListener = null;
        commitCallback = null;
        timeout = null;
    }
}
