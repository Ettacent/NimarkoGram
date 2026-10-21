/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Stories.recorder;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.view.animation.AccelerateDecelerateInterpolator;

final class CaptionStoryFlipDrawable extends Drawable implements Animatable, Drawable.Callback {

    private final Drawable glyph;
    private final ValueAnimator animator;
    private float rotation;

    CaptionStoryFlipDrawable(Drawable glyph) {
        this.glyph = glyph;
        glyph.setCallback(this);
        animator = ValueAnimator.ofFloat(0f, -360f);
        animator.setDuration(300);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            rotation = (float) animation.getAnimatedValue();
            invalidateSelf();
        });
    }

    @Override
    public void draw(Canvas canvas) {
        final int save = canvas.save();
        try {
            final Rect bounds = getBounds();
            canvas.scale(32f / 36f, 32f / 36f, bounds.exactCenterX(), bounds.exactCenterY());
            canvas.rotate(rotation, bounds.exactCenterX(), bounds.exactCenterY());
            glyph.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        glyph.setBounds(bounds);
    }

    @Override
    public void setAlpha(int alpha) {
        glyph.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return glyph.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        glyph.setColorFilter(colorFilter);
    }

    @Override
    public ColorFilter getColorFilter() {
        return glyph.getColorFilter();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return glyph.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return glyph.getIntrinsicHeight();
    }

    @Override
    public void start() {
        if (!animator.isStarted() && getCallback() != null && isVisible()) {
            animator.start();
        }
    }

    @Override
    public void stop() {
        animator.cancel();
        rotation = 0f;
        invalidateSelf();
    }

    @Override
    public boolean isRunning() {
        return animator.isRunning();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);
        glyph.setVisible(visible, restart);
        if (!visible) {
            stop();
        }
        return changed;
    }

    @Override
    public void jumpToCurrentState() {
        stop();
        glyph.jumpToCurrentState();
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        invalidateSelf();
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        scheduleSelf(what, when);
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        unscheduleSelf(what);
    }
}
