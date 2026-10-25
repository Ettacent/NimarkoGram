/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class AppResumeFade extends Drawable implements ViewTreeObserver.OnPreDrawListener {
    public interface Exclusions {
        void collect(Rect banner, Rect notification);
        default View header() { return null; }
        default View folders() { return null; }
        default View infoCard() { return null; }
        default void infoCardShape(View root, Path out) { out.rewind(); }
    }

    private static final long DURATION = 700;
    private static final long MAX_PIXELS = 4_000_000;
    private final Window window;
    private final ViewGroup root;
    private final BooleanSupplier safe;
    private final BooleanSupplier ready;
    private final Exclusions exclusions;
    private final Supplier<View> composer;
    private final ResumeListRegions lists;
    private final ResumeFrameCapture frameCapture;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect oldBanner = new Rect();
    private final Rect oldNotification = new Rect();
    private final Rect liveBanner = new Rect();
    private final Rect liveNotification = new Rect();
    private final Rect oldComposer = new Rect();
    private final Rect liveComposer = new Rect();
    private final Rect oldHeader = new Rect();
    private final Rect liveHeader = new Rect();
    private final Rect oldFolders = new Rect();
    private final Rect liveFolders = new Rect();
    private final Rect oldInfoCard = new Rect();
    private final Rect liveInfoCard = new Rect();
    private View capturedInfoCard;
    private final Path oldInfoCardShape = new Path();
    private final Path liveInfoCardShape = new Path();
    private final Path infoCardShapeDifference = new Path();
    private final Rect captureBounds = new Rect();
    private final int[] location = new int[2];
    private Bitmap snapshot;
    private BooleanSupplier current;
    private ViewTreeObserver observer;
    private ValueAnimator animator;
    private Object windowToken;
    private int generation;
    private boolean paused;
    private boolean activityPaused;
    private boolean topStateKnown;
    private boolean topResumed;
    private boolean lifecycleReturn;
    private boolean gateActive;
    private boolean finishingForInput;
    private long inputDeadline;
    private boolean copying;
    private boolean presenting;
    private boolean pendingResume;
    private long captureDeadline;
    private long readyDeadline;
    private long enterDeadline;
    private boolean enterComplete;
    private float progress;

    public AppResumeFade(Window window, ViewGroup root, BooleanSupplier safe,
                                Exclusions exclusions, Supplier<View> composer, BooleanSupplier ready) {
        this.window = window;
        this.root = root;
        this.safe = safe;
        this.ready = ready;
        this.exclusions = exclusions;
        this.composer = composer;
        lists = new ResumeListRegions(root);
        frameCapture = new ResumeFrameCapture(root);
    }

    public void onPause(BooleanSupplier valid) {
        activityPaused = true;
        prepareReturn(valid);
        lifecycleReturn = true;
    }

    public void onTopResumedActivityChanged(boolean top, BooleanSupplier valid) {
        boolean lostTop = topStateKnown && topResumed && !top;
        topStateKnown = true;
        topResumed = top;
        if (lostTop && !activityPaused && valid != null) {
            prepareReturn(valid);
            lifecycleReturn = false;
        } else if (!top) {
            pausePresentation();
        }
        if (top && !activityPaused) requestResume();
    }

    private void prepareReturn(BooleanSupplier valid) {
        if (!ready.getAsBoolean()) {
            cancel();
            paused = true;
            enterComplete = false;
            return;
        }
        if (animator == null && current != null && matches()
                && !(activityPaused && lists.needsRecapture())) {
            clearPresentation();
            paused = true;
            enterComplete = false;
            return;
        }
        cancel();
        paused = true;
        enterComplete = false;
        if (copying || Build.VERSION.SDK_INT < 29 || !safe.getAsBoolean() || !valid.getAsBoolean()
                || !root.isAttachedToWindow() || !root.isHardwareAccelerated()
                || root.getWidth() <= 0 || root.getHeight() <= 0 || secure()) return;
        final int token = generation;
        captureBounds.set(0, 0, root.getWidth(), root.getHeight());
        windowToken = root.getWindowToken();
        current = valid;
        lists.stopScrolling(exclusions.folders());
        frameCapture.start(() -> {
            if (token != generation || !matches() || !paused) {
                cancel();
                return;
            }
            captureGeometry();
        }, () -> token == generation && captureGeometryCurrent(),
                () -> copySnapshot(token), () -> {
            if (token == generation) cancel();
        });
    }

    private void captureGeometry() {
        captureBounds.set(0, 0, root.getWidth(), root.getHeight());
        exclusions.collect(oldBanner, oldNotification);
        bounds(composer.get(), oldComposer);
        bounds(exclusions.header(), oldHeader);
        View folders = exclusions.folders();
        bounds(folders, oldFolders);
        capturedInfoCard = exclusions.infoCard();
        bounds(capturedInfoCard, oldInfoCard);
        exclusions.infoCardShape(root, oldInfoCardShape);
        lists.capture(folders);
    }

    private boolean captureGeometryCurrent() {
        if (!ready.getAsBoolean() || !matches() || !lists.isCaptureCurrent()) return false;
        exclusions.collect(liveBanner, liveNotification);
        bounds(composer.get(), liveComposer);
        bounds(exclusions.header(), liveHeader);
        bounds(exclusions.folders(), liveFolders);
        View infoCard = exclusions.infoCard();
        bounds(infoCard, liveInfoCard);
        exclusions.infoCardShape(root, liveInfoCardShape);
        boolean sameCardShape = infoCardShapeDifference.op(oldInfoCardShape, liveInfoCardShape, Path.Op.XOR)
                && infoCardShapeDifference.isEmpty();
        return oldBanner.equals(liveBanner) && oldNotification.equals(liveNotification)
                && oldComposer.equals(liveComposer) && oldHeader.equals(liveHeader)
                && oldFolders.equals(liveFolders) && capturedInfoCard == infoCard
                && oldInfoCard.equals(liveInfoCard) && sameCardShape;
    }

    private void copySnapshot(int token) {
        if (token != generation || !matches() || !paused || !frameCapture.committed()) {
            cancel();
            return;
        }
        final double scale = Math.min(1d, Math.sqrt(MAX_PIXELS / (double)
                ((long) captureBounds.width() * captureBounds.height())));
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(Math.max(1, (int) (captureBounds.width() * scale)),
                    Math.max(1, (int) (captureBounds.height() * scale)), Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            cancel();
            return;
        }
        root.getLocationInWindow(location);
        copying = true;
        try {
            PixelCopy.request(window, new Rect(location[0], location[1],
                    location[0] + captureBounds.width(), location[1] + captureBounds.height()), bitmap, result -> {
                copying = false;
                if (token != generation || !frameCapture.committed()
                        || (!paused && !pendingResume) || result != PixelCopy.SUCCESS || !matches()
                        || pendingResume && gateActive && SystemClock.uptimeMillis() >= captureDeadline) {
                    bitmap.recycle();
                    if (token == generation) cancel();
                    return;
                }
                if (!captureGeometryCurrent()) {
                    bitmap.recycle();
                    cancel();
                    return;
                }
                frameCapture.stop();
                lists.validateCapture();
                snapshot = bitmap;
                bitmap.prepareToDraw();
                if (pendingResume) present();
            }, handler);
        } catch (RuntimeException ignored) {
            copying = false;
            bitmap.recycle();
            cancel();
        }
    }

    public void onResume() {
        activityPaused = false;
        requestResume();
    }

    private void requestResume() {
        if (presenting || pendingResume) {
            if (!matches()) cancel();
            else {
                startGate();
                root.invalidate();
            }
            return;
        }
        if (!paused) return;
        if (frameCapture.awaitingFrame()) {
            cancel();
            return;
        }
        paused = false;
        if (!matches() || snapshot == null && !copying) {
            cancel();
            return;
        }
        pendingResume = true;
        if (!lifecycleReturn) enterComplete = true;
        startGate();
        observer = root.getViewTreeObserver();
        observer.addOnPreDrawListener(this);
        if (snapshot != null) present();
        root.invalidate();
    }

    private void present() {
        if (presenting || !pendingResume || snapshot == null || !matches()) return;
        pendingResume = false;
        progress = 0f;
        setBounds(0, 0, root.getWidth(), root.getHeight());
        presenting = true;
        root.getOverlay().add(this);
        root.invalidate();
    }

    public void onWindowFocusChanged(boolean focused) {
        if (!focused) pausePresentation();
        else if (!activityPaused) requestResume();
        if (presenting || pendingResume) root.invalidate();
    }

    private boolean foreground() {
        return !activityPaused && root.hasWindowFocus() && root.getWindowVisibility() == View.VISIBLE
                && (!topStateKnown || topResumed);
    }

    private void startGate() {
        if (gateActive || !foreground()) return;
        long now = SystemClock.uptimeMillis();
        captureDeadline = now + 32;
        enterDeadline = now + 1200;
        readyDeadline = now + 5000;
        gateActive = true;
    }

    private void pausePresentation() {
        if (animator != null && animator.isStarted() && !animator.isPaused()) animator.pause();
        gateActive = false;
    }

    public void onEnterAnimationComplete() {
        if (paused) return;
        enterComplete = true;
        if (presenting || pendingResume) root.invalidate();
    }

    public void onUserInteraction() {
        if (presenting || pendingResume) cancel();
    }

    public void onTouchInteraction() {
        if (pendingResume || presenting && (!foreground() || !matches())) {
            cancel();
        } else if (presenting && !finishingForInput) {
            finishingForInput = true;
            long duration = Math.max(1L, Math.round(120 * (1f - progress)));
            inputDeadline = SystemClock.uptimeMillis() + duration;
            startAnimator(progress, duration);
        }
    }

    @Override
    public boolean onPreDraw() {
        if (!matches()) {
            cancel();
            return true;
        }
        if (finishingForInput && SystemClock.uptimeMillis() >= inputDeadline) {
            cancel();
            return true;
        }
        if (paused || !foreground()) {
            pausePresentation();
            return true;
        }
        startGate();
        if (pendingResume) {
            if (copying && SystemClock.uptimeMillis() < captureDeadline) {
                root.postInvalidateOnAnimation();
                return false;
            }
            cancel();
            return true;
        }
        if (!presenting || snapshot == null) return true;
        setBounds(0, 0, root.getWidth(), root.getHeight());
        if (UiAnimationClock.isPaused()) {
            if (animator != null && animator.isStarted() && !animator.isPaused()) animator.pause();
            root.postInvalidateOnAnimation();
            return true;
        }
        if (!finishingForInput && !ready.getAsBoolean()) {
            if (animator != null && animator.isStarted() && !animator.isPaused()) animator.pause();
            if (SystemClock.uptimeMillis() >= readyDeadline) {
                cancel();
                return true;
            }
            root.postInvalidateOnAnimation();
            return true;
        }
        if (animator != null) {
            if (animator.isPaused()) animator.resume();
            return true;
        }
        if (!enterComplete && SystemClock.uptimeMillis() < enterDeadline) {
            root.postInvalidateOnAnimation();
            return true;
        }
        startAnimator(0f, DURATION);
        return true;
    }

    private void startAnimator(float from, long duration) {
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
        }
        final ValueAnimator next = ValueAnimator.ofFloat(from, 1f);
        animator = next;
        next.setDuration(duration);
        next.setInterpolator(finishingForInput ? new LinearInterpolator() : new AccelerateDecelerateInterpolator());
        next.addUpdateListener(value -> {
            if (animator != next) return;
            progress = (float) value.getAnimatedValue();
            invalidateSelf();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (animator == animation) cancel();
            }
        });
        next.start();
    }

    private boolean secure() {
        return (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    private boolean matches() {
        return current != null && safe.getAsBoolean() && current.getAsBoolean() && !secure()
                && root.isAttachedToWindow() && root.getWindowToken() == windowToken
                && root.getWidth() == captureBounds.width();
    }

    private void bounds(View view, Rect out) {
        out.setEmpty();
        if (view == null || !view.isShown() || view.getAlpha() <= 0f
                || view.getWindowToken() != root.getWindowToken()) return;
        if (!view.getGlobalVisibleRect(out)) {
            out.setEmpty();
            return;
        }
        root.getLocationInWindow(location);
        out.offset(-location[0], -location[1]);
        if (!out.intersect(0, 0, root.getWidth(), root.getHeight())) out.setEmpty();
    }

    public void cancel() {
        generation++;
        frameCapture.stop();
        clearPresentation();
        snapshot = null;
        current = null;
        windowToken = null;
        lists.clear();
        oldBanner.setEmpty();
        oldNotification.setEmpty();
        oldComposer.setEmpty();
        oldHeader.setEmpty();
        oldFolders.setEmpty();
        oldInfoCard.setEmpty();
        capturedInfoCard = null;
        oldInfoCardShape.rewind();
        liveInfoCardShape.rewind();
        infoCardShapeDifference.rewind();
        root.invalidate();
    }

    private void clearPresentation() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
            animator = null;
        }
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        if (presenting) root.getOverlay().remove(this);
        presenting = false;
        pendingResume = false;
        gateActive = false;
        finishingForInput = false;
        inputDeadline = 0;
        root.invalidate();
    }

    private void exclude(Canvas canvas, Rect rect) {
        if (!rect.isEmpty()) canvas.clipOutRect(rect);
    }

    @Override
    public void draw(Canvas canvas) {
        if (!presenting || paused || snapshot == null || !matches()) return;
        if (finishingForInput && SystemClock.uptimeMillis() >= inputDeadline) return;
        exclusions.collect(liveBanner, liveNotification);
        bounds(composer.get(), liveComposer);
        bounds(exclusions.header(), liveHeader);
        View folders = exclusions.folders();
        bounds(folders, liveFolders);
        View infoCard = exclusions.infoCard();
        bounds(infoCard, liveInfoCard);
        lists.setLiveExclusion(folders);
        paint.setAlpha(Math.round(255f * (1f - progress)));
        int save = canvas.save();
        try {
            canvas.clipRect(0, 0, root.getWidth(), root.getHeight());
            exclude(canvas, liveBanner);
            exclude(canvas, liveNotification);
            exclude(canvas, liveHeader);
            exclude(canvas, liveComposer);
            exclude(canvas, liveFolders);
            int content = canvas.save();
            exclude(canvas, liveInfoCard);
            int base = canvas.save();
            try {
                exclude(canvas, oldBanner);
                exclude(canvas, oldNotification);
                lists.clipLists(canvas);
                exclude(canvas, oldComposer);
                exclude(canvas, oldHeader);
                exclude(canvas, oldFolders);
                exclude(canvas, oldInfoCard);
                canvas.drawBitmap(snapshot, null, captureBounds, paint);
            } finally {
                canvas.restoreToCount(base);
            }
            int rows = canvas.save();
            try {
                exclude(canvas, liveComposer);
                lists.draw(canvas, snapshot, paint, captureBounds.width(), captureBounds.height(), oldBanner, oldNotification, oldComposer, oldHeader, oldFolders, oldInfoCard);
            } finally {
                canvas.restoreToCount(rows);
            }
            canvas.restoreToCount(content);
            if (capturedInfoCard == infoCard && !oldInfoCard.isEmpty() && !liveInfoCard.isEmpty()
                    && !oldInfoCardShape.isEmpty()
                    && oldInfoCard.width() == liveInfoCard.width()
                    && oldInfoCard.height() == liveInfoCard.height()) {
                canvas.clipRect(liveInfoCard);
                canvas.translate(liveInfoCard.left - oldInfoCard.left, liveInfoCard.top - oldInfoCard.top);
                canvas.clipRect(oldInfoCard);
                canvas.clipPath(oldInfoCardShape);
                exclude(canvas, oldBanner);
                exclude(canvas, oldNotification);
                exclude(canvas, oldComposer);
                exclude(canvas, oldHeader);
                exclude(canvas, oldFolders);
                canvas.drawBitmap(snapshot, null, captureBounds, paint);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
