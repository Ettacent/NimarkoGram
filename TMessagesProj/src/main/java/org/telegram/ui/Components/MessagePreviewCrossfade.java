/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

public final class MessagePreviewCrossfade {
    public interface Content { void draw(Canvas canvas); }
    public interface Alignment { boolean isCentered(); }
    private final View owner;
    private final Paint outgoingPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint incomingPaint = new Paint();
    private Bitmap outgoing;
    private ValueAnimator animator;
    private float progress = 1f;
    private final Runnable onFrame;

    private final Alignment alignment;
    public MessagePreviewCrossfade(View owner) {
        this(owner, null);
    }

    public MessagePreviewCrossfade(View owner, Runnable onFrame) {
        this(owner, onFrame, null);
    }

    public MessagePreviewCrossfade(View owner, Runnable onFrame, Alignment alignment) {
        this.owner = owner;
        this.onFrame = onFrame;
        this.alignment = alignment;
        incomingPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    public void capture(Content content) {
        capture(content, true);
    }

    public void capture(Content content, boolean animated) {
        capture(content, animated, progress);
    }

    public void capture(Content content, boolean animated, float snapshotProgress) {
        if (owner.getWidth() <= 0 || owner.getHeight() <= 0) {
            finish();
            return;
        }
        final float capturedProgress = Float.isNaN(snapshotProgress) ? progress
                : Math.max(0f, Math.min(1f, snapshotProgress));
        Bitmap snapshot;
        try {
            if (outgoing != null && capturedProgress == 0f) {
                snapshot = outgoing;
            } else {
                snapshot = Bitmap.createBitmap(contentWidth(), contentHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(snapshot);
                if (isCentered()) canvas.translate((snapshot.getWidth() - owner.getWidth()) / 2f, 0);
                final float savedProgress = progress;
                progress = capturedProgress;
                try {
                    draw(canvas, content);
                } finally {
                    progress = savedProgress;
                }
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            finish();
            return;
        }
        finish();
        outgoing = snapshot;
        progress = 0f;
        if (!animated) {
            owner.invalidate();
            return;
        }
        start();
    }

    public void start() {
        if (outgoing == null || animator != null) return;
        ValueAnimator next = ValueAnimator.ofFloat(0f, 1f);
        animator = next;
        next.setDuration(300);
        next.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        next.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            owner.invalidate();
            if (onFrame != null) onFrame.run();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) finish();
            }
        });
        next.start();
        NimarkoUiAnimationClock.track(next);
    }

    public boolean isRunning() { return animator != null; }
    public float getProgress() { return progress; }
    public int getOutgoingWidth() { return outgoing == null ? 0 : outgoing.getWidth(); }
    private int contentWidth() { return Math.max(owner.getWidth(), outgoing == null ? 0 : outgoing.getWidth()); }
    private int contentHeight() { return Math.max(owner.getHeight(), outgoing == null ? 0 : outgoing.getHeight()); }

    private boolean isCentered() { return alignment != null && alignment.isCentered(); }
    public void draw(Canvas canvas, Content content) {
        if (outgoing == null || progress >= 1f) {
            content.draw(canvas);
            return;
        }
        int alpha = Math.round(255 * progress);
        float outgoingX = isCentered() ? (owner.getWidth() - outgoing.getWidth()) / 2f : 0f;
        float left = Math.min(0f, outgoingX);
        float right = Math.max(owner.getWidth(), outgoingX + outgoing.getWidth());
        int layer = canvas.saveLayer(left, 0, right, contentHeight(), null);
        try {
            outgoingPaint.setAlpha(255 - alpha);
            canvas.drawBitmap(outgoing, outgoingX, 0, outgoingPaint);
            if (alpha > 0) {
                incomingPaint.setAlpha(alpha);
                int incoming = canvas.saveLayer(0, 0, contentWidth(), contentHeight(), incomingPaint);
                try { content.draw(canvas); } finally { canvas.restoreToCount(incoming); }
            }
        } finally { canvas.restoreToCount(layer); }
    }

    public void finish() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
            animator = null;
        }
        outgoing = null;
        progress = 1f;
        owner.invalidate();
        if (onFrame != null) onFrame.run();
    }
}
