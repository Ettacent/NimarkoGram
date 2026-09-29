package org.telegram.ui.Components;

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
    private final View owner;
    private final Paint outgoingPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint incomingPaint = new Paint();
    private Bitmap outgoing;
    private ValueAnimator animator;
    private float progress = 1f;
    private final Runnable onFrame;

    public MessagePreviewCrossfade(View owner) {
        this(owner, null);
    }

    public MessagePreviewCrossfade(View owner, Runnable onFrame) {
        this.owner = owner;
        this.onFrame = onFrame;
        incomingPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    public void capture(Content content) {
        if (owner.getWidth() <= 0 || owner.getHeight() <= 0) return;
        Bitmap snapshot;
        try {
            if (outgoing != null && progress == 0f) {
                snapshot = outgoing;
            } else {
                snapshot = Bitmap.createBitmap(contentWidth(), contentHeight(), Bitmap.Config.ARGB_8888);
                draw(new Canvas(snapshot), content);
            }
        } catch (RuntimeException | OutOfMemoryError error) {
            finish();
            return;
        }
        finish();
        outgoing = snapshot;
        progress = 0f;
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
    }

    public boolean isRunning() { return animator != null; }
    public float getProgress() { return progress; }
    private int contentWidth() { return Math.max(owner.getWidth(), outgoing == null ? 0 : outgoing.getWidth()); }
    private int contentHeight() { return Math.max(owner.getHeight(), outgoing == null ? 0 : outgoing.getHeight()); }

    public void draw(Canvas canvas, Content content) {
        if (outgoing == null || progress >= 1f) {
            content.draw(canvas);
            return;
        }
        int alpha = Math.round(255 * progress);
        int layer = canvas.saveLayer(0, 0, contentWidth(), contentHeight(), null);
        try {
            outgoingPaint.setAlpha(255 - alpha);
            canvas.drawBitmap(outgoing, 0, 0, outgoingPaint);
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
