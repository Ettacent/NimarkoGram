/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;

import org.telegram.messenger.FileLog;

import java.util.function.BooleanSupplier;

final class PhotoReturnHandoff implements ViewTreeObserver.OnPreDrawListener,
        ViewTreeObserver.OnDrawListener, View.OnAttachStateChangeListener,
        Application.ActivityLifecycleCallbacks {
    static final long TIMEOUT_MS = 250;
    static final long RETRY_MS = 32;
    static final int MAX_ATTEMPTS = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Activity activity;
    private final View source;
    private final View secondary;
    private final Rect sourceBounds;
    private final BooleanSupplier current;
    private final BooleanSupplier sourceCurrent;
    private final Runnable reveal;
    private final BooleanSupplier retire;
    private final Rect visibleRect = new Rect();
    private final Runnable timeout = this::finishNow;
    private final Runnable committed = () -> main.post(this::onCommitted);
    private ViewTreeObserver observer;
    private boolean started;
    private boolean finished;
    private boolean drawObserved;
    private boolean commitRegistered;
    private boolean revealed;
    private boolean finishing;
    private boolean retired;
    private boolean cleanupComplete;
    private int attempts;

    PhotoReturnHandoff(Activity activity, View source, View secondary, Rect sourceBounds,
            BooleanSupplier current, BooleanSupplier sourceCurrent, Runnable reveal, BooleanSupplier retire) {
        this.activity = activity;
        this.source = source;
        this.secondary = secondary;
        this.sourceBounds = sourceBounds;
        this.current = current;
        this.sourceCurrent = sourceCurrent;
        this.reveal = reveal;
        this.retire = retire;
    }

    void start() {
        requireMainThread();
        if (started || finished) {
            return;
        }
        started = true;
        try {
            main.postDelayed(timeout, TIMEOUT_MS);
            activity.getApplication().registerActivityLifecycleCallbacks(this);
            source.addOnAttachStateChangeListener(this);
            if (!canDrawSource()) {
                main.post(timeout);
                return;
            }
            observer = source.getViewTreeObserver();
            if (!observer.isAlive()) {
                main.post(timeout);
                return;
            }
            observer.addOnPreDrawListener(this);
            observer.addOnDrawListener(this);
            source.invalidate();
        } catch (Throwable e) {
            log(e);
            finishNow();
        }
    }

    private boolean canDrawSource() {
        if (!current.getAsBoolean() || !sourceCurrent.getAsBoolean()
                || activity.isFinishing() || activity.isDestroyed()
                || !source.isAttachedToWindow() || !source.isShown()
                || source.getWindowVisibility() != View.VISIBLE
                || source.getWindowToken() != activity.getWindow().getDecorView().getWindowToken()
                || !source.getLocalVisibleRect(visibleRect) || !Rect.intersects(visibleRect, sourceBounds)) {
            return false;
        }
        View view = source;
        while (view != null) {
            if (view.getAlpha() <= 0) {
                return false;
            }
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return true;
    }

    @Override
    public boolean onPreDraw() {
        try {
            if (!finished && !revealed) {
                if (!canDrawSource()) {
                    main.post(timeout);
                } else {
                    if (secondary != null && secondary.getRootView() == source.getRootView()) {
                        secondary.setVisibility(View.INVISIBLE);
                    }
                    if (!commitRegistered && Build.VERSION.SDK_INT >= 29 && source.isHardwareAccelerated()) {
                        commitRegistered = true;
                        observer.registerFrameCommitCallback(committed);
                    }
                }
            }
        } catch (Throwable e) {
            log(e);
            main.post(timeout);
        }
        return true;
    }

    @Override
    public void onDraw() {
        if (finished || drawObserved) {
            return;
        }
        try {
            if (!canDrawSource()) {
                main.post(timeout);
                return;
            }
            drawObserved = true;
            if (Build.VERSION.SDK_INT < 29 || !source.isHardwareAccelerated()) {
                main.post(this::onCommitted);
            }
        } catch (Throwable e) {
            log(e);
            main.post(timeout);
        }
    }

    private void onCommitted() {
        requireMainThread();
        if (finished) {
            return;
        }
        if (!drawObserved) {
            return;
        }
        try {
            if (!canDrawSource()) {
                finishNow();
                return;
            }
            if (!revealed) {
                revealed = true;
                reveal.run();
                if (finished || !current.getAsBoolean()) {
                    finishNow();
                    return;
                }
                finishNow();
            }
        } catch (Throwable e) {
            log(e);
            finishNow();
        }
    }

    void finishNow() {
        requireMainThread();
        if (finishing || retired && cleanupComplete) {
            return;
        }
        finishing = true;
        finished = true;
        attempts++;
        try {
            cleanupComplete = cancel();
            if (!retired) {
                boolean owned = false;
                try {
                    owned = current.getAsBoolean();
                } catch (Throwable e) {
                    log(e);
                }
                if (owned && !revealed) {
                    revealed = true;
                    attempt(reveal);
                }
                try {
                    if (!current.getAsBoolean()) {
                        retired = true;
                    }
                } catch (Throwable e) {
                    log(e);
                }
                if (!retired) {
                    try {
                        retired = retire.getAsBoolean();
                    } catch (Throwable e) {
                        log(e);
                    }
                }
            }
        } finally {
            finishing = false;
            if ((!retired || !cleanupComplete) && attempts < MAX_ATTEMPTS) {
                attempt(() -> main.postDelayed(timeout, RETRY_MS));
            }
        }
    }

    boolean isRetired() {
        return retired;
    }

    private boolean cancel() {
        boolean clean = attempt(() -> main.removeCallbacks(timeout));
        clean &= removeDrawListeners();
        clean &= attempt(() -> source.removeOnAttachStateChangeListener(this));
        clean &= attempt(() -> activity.getApplication().unregisterActivityLifecycleCallbacks(this));
        return clean;
    }

    private boolean removeDrawListeners() {
        ViewTreeObserver closingObserver = observer;
        if (closingObserver == null) {
            return true;
        }
        boolean clean = attempt(() -> {
            if (closingObserver.isAlive()) closingObserver.removeOnPreDrawListener(this);
        });
        clean &= attempt(() -> {
            if (closingObserver.isAlive()) closingObserver.removeOnDrawListener(this);
        });
        clean &= attempt(() -> {
            if (Build.VERSION.SDK_INT >= 29) {
                if (closingObserver.isAlive()) closingObserver.unregisterFrameCommitCallback(committed);
            }
        });
        if (clean) {
            observer = null;
        }
        return clean;
    }

    private static boolean attempt(Runnable operation) {
        try {
            operation.run();
            return true;
        } catch (Throwable e) {
            log(e);
            return false;
        }
    }

    private static void log(Throwable error) {
        try {
            FileLog.e(error);
        } catch (Throwable ignored) {
        }
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Photo return must run on the main looper");
        }
    }

    @Override
    public void onViewAttachedToWindow(View view) {}

    @Override
    public void onViewDetachedFromWindow(View view) {
        main.post(timeout);
    }

    @Override
    public void onActivityPaused(Activity owner) {
        if (owner == activity) {
            finishNow();
        }
    }

    @Override
    public void onActivityStopped(Activity owner) {
        if (owner == activity) {
            finishNow();
        }
    }

    @Override
    public void onActivityDestroyed(Activity owner) {
        if (owner == activity) {
            finishNow();
        }
    }

    @Override
    public void onActivityCreated(Activity owner, Bundle state) {}

    @Override
    public void onActivityStarted(Activity owner) {}

    @Override
    public void onActivityResumed(Activity owner) {}

    @Override
    public void onActivitySaveInstanceState(Activity owner, Bundle state) {}
}
