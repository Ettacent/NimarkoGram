package org.telegram.ui.Components;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.StateSet;
import android.view.Choreographer;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.telegram.ui.Cells.BaseCell;
public class SmoothRippleDrawable extends BaseCell.RippleDrawableSafe {
    public static final int ENTER_DURATION = 150;
    public static final int EXIT_DURATION = 170;
    private final Paint feedbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layerPaint = new Paint();
    private final Rect hotspotBounds = new Rect();
    private final RectF feedbackBounds = new RectF();
    private final Rect previousFeedbackBounds = new Rect();
    private final Rect dirtyBounds = new Rect();
    private ColorStateList feedbackColors;
    private ValueAnimator animator;
    private boolean entering;
    private boolean continuousFeedback;
    private float continuousTarget = -1f;
    private float progress;
    private float feedbackVelocity;
    private boolean continuousRunning;
    private long feedbackFrameTime;
    private final Choreographer.FrameCallback feedbackFrame = this::advanceContinuousFeedback;
    private float hotspotProgress;
    private boolean active;
    private boolean pressed;
    private boolean useHotspot;
    private boolean hasHotspot;
    private float hotspotX;
    private float hotspotY;
    private int alpha = 255;
    public SmoothRippleDrawable(@NonNull ColorStateList color, @Nullable Drawable content, @Nullable Drawable mask) {
        super(color, content, mask);
        feedbackColors = color;
        maskPaint.setColor(Color.WHITE);
        feedbackPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    }
    public boolean setContinuousFeedback(boolean continuous) {
        boolean previous = continuousFeedback;
        continuousFeedback = continuous;
        return previous;
    }
    public void setContinuousFeedbackTarget(float target) {
        target = Math.max(0f, Math.min(1f, target));
        if (continuousTarget == target) return;
        continuousTarget = target;
        animateFeedback();
    }
    public void clearContinuousFeedbackTarget() {
        if (continuousTarget < 0f) return;
        continuousTarget = -1f;
        animateFeedback();
    }
    private float feedbackTarget() {
        return continuousTarget >= 0f ? continuousTarget : active ? 1f : 0f;
    }
    @Override
    protected boolean onStateChange(int[] states) {
        boolean changed = false;
        for (int i = 0; i < getNumberOfLayers(); i++) {
            changed |= getDrawable(i).setState(states);
        }
        boolean enabled = false;
        boolean focused = false;
        boolean hovered = false;
        pressed = false;
        for (int state : states) {
            if (state == android.R.attr.state_enabled) {
                enabled = true;
            } else if (state == android.R.attr.state_pressed) {
                pressed = true;
            } else if (state == android.R.attr.state_focused) {
                focused = true;
            } else if (state == android.R.attr.state_hovered) {
                hovered = true;
            }
        }
        boolean nextActive = enabled && (pressed || focused || hovered);
        if (nextActive != active) {
            float previousTarget = feedbackTarget();
            active = nextActive;
            if (active) {
                useHotspot = pressed && hasHotspot;
                if (progress == 0f) {
                    hotspotProgress = 0f;
                }
            }
            if (previousTarget != feedbackTarget()) animateFeedback();
            changed = true;
        }
        invalidateSelf();
        return changed;
    }
    public void cancelPress() {
        cancelAnimator();
        continuousTarget = -1f;
        setState(StateSet.NOTHING);
        if (animator == null && !continuousRunning) {
            animateFeedback();
        }
    }
    private void animateFeedback() {
        float target = feedbackTarget();
        if (!isVisible() || !isCallbackVisible() || Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            cancelAnimator();
            progress = isVisible() && isCallbackVisible() ? target : 0f;
            hotspotProgress = progress;
            invalidateSelf();
            return;
        }
        if (continuousFeedback || continuousRunning) {
            if (!continuousRunning) {
                cancelAnimator();
                if (progress == target) return;
                continuousRunning = true;
                feedbackFrameTime = System.nanoTime();
                Choreographer.getInstance().postFrameCallback(feedbackFrame);
            }
            return;
        }
        boolean minimumFeedback = !continuousFeedback && !active && entering && animator != null && animator.isRunning() && progress < 0.35f;
        if (minimumFeedback) {
            target = 0.35f;
        }
        cancelAnimator();
        if (progress == target) {
            return;
        }
        animator = ValueAnimator.ofFloat(progress, target);
        entering = target > progress;
        long duration = minimumFeedback ? Math.max(1, Math.round(ENTER_DURATION * (target - progress))) : active ? ENTER_DURATION : EXIT_DURATION;
        animator.setDuration(duration);
        animator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
        animator.addUpdateListener(animation -> {
            if (!isCallbackVisible()) {
                cancelAnimator();
                progress = 0f;
                hotspotProgress = 0f;
                invalidateSelf();
                return;
            }
            progress = (float) animation.getAnimatedValue();
            if (entering) {
                hotspotProgress = Math.max(hotspotProgress, progress);
            }
            invalidateSelf();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator != animation) {
                    return;
                }
                animator = null;
                if (!active && progress > 0f) {
                    animateFeedback();
                }
            }
        });
        animator.start();
    }
    private void cancelAnimator() {
        if (continuousRunning) {
            Choreographer.getInstance().removeFrameCallback(feedbackFrame);
            continuousRunning = false;
        }
        feedbackVelocity = 0f;
        if (animator != null) {
            ValueAnimator previous = animator;
            animator = null;
            previous.cancel();
            previous.removeAllUpdateListeners();
            previous.removeAllListeners();
        }
    }
    private void advanceContinuousFeedback(long frameTimeNanos) {
        if (!continuousRunning) return;
        if (!isVisible() || !isCallbackVisible()
                || Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) {
            cancelAnimator();
            progress = isVisible() && isCallbackVisible() ? feedbackTarget() : 0f;
            hotspotProgress = progress;
            invalidateSelf();
            return;
        }
        float dt = Math.max(0f, (frameTimeNanos - feedbackFrameTime) / 1_000_000_000f);
        feedbackFrameTime = Math.max(feedbackFrameTime, frameTimeNanos);
        stepContinuousFeedback(dt);
        hotspotProgress = Math.max(hotspotProgress, progress);
        invalidateSelf();
        float target = feedbackTarget();
        if (Math.abs(progress - target) < .001f && Math.abs(feedbackVelocity) < .02f) {
            progress = target;
            feedbackVelocity = 0f;
            continuousRunning = false;
        } else {
            Choreographer.getInstance().postFrameCallback(feedbackFrame);
        }
    }
    private void stepContinuousFeedback(float dt) {
        float target = feedbackTarget();
        float omega = 32f;
        float offset = progress - target;
        float step = (feedbackVelocity + omega * offset) * dt;
        float decay = (float) Math.exp(-omega * dt);
        progress = Math.max(0f, Math.min(1f, target + (offset + step) * decay));
        feedbackVelocity = (feedbackVelocity - omega * step) * decay;
    }
    private boolean isCallbackVisible() {
        Callback callback = getCallback();
        if (callback instanceof View) {
            View view = (View) callback;
            return view.isAttachedToWindow() && view.getWindowVisibility() == View.VISIBLE;
        }
        return true;
    }
    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!isVisible()) {
            return;
        }
        super.draw(canvas);
        if (progress <= 0f || alpha == 0 || feedbackColors == null) {
            return;
        }
        Drawable mask = findDrawableByLayerId(android.R.id.mask);
        updateFeedbackBounds(mask);
        if (feedbackBounds.isEmpty()) {
            return;
        }
        int color = feedbackColors.getColorForState(getState(), feedbackColors.getDefaultColor());
        feedbackPaint.setColor(color);
        feedbackPaint.setAlpha(Math.round(Color.alpha(color) * progress));
        layerPaint.setAlpha(alpha);
        int save = canvas.saveLayer(feedbackBounds, layerPaint);
        try {
            if (mask != null) {
                mask.draw(canvas);
            } else if (getNumberOfLayers() > 0) {
                for (int i = 0; i < getNumberOfLayers(); i++) {
                    Drawable layer = getDrawable(i);
                    int layerAlpha = layer.getAlpha();
                    try {
                        layer.setAlpha(255);
                        layer.draw(canvas);
                    } finally {
                        layer.setAlpha(layerAlpha);
                    }
                }
            } else {
                canvas.drawCircle(feedbackBounds.centerX(), feedbackBounds.centerY(), feedbackBounds.width() / 2f, maskPaint);
            }
            canvas.drawRect(feedbackBounds, feedbackPaint);
        } finally {
            canvas.restoreToCount(save);
        }
    }
    private void updateFeedbackBounds(Drawable mask) {
        if (mask != null || getNumberOfLayers() > 0) {
            feedbackBounds.set(getBounds());
            return;
        }
        getHotspotBounds(hotspotBounds);
        float x = useHotspot ? Math.max(hotspotBounds.left, Math.min(hotspotBounds.right, hotspotX)) : hotspotBounds.exactCenterX();
        float y = useHotspot ? Math.max(hotspotBounds.top, Math.min(hotspotBounds.bottom, hotspotY)) : hotspotBounds.exactCenterY();
        x += (hotspotBounds.exactCenterX() - x) * hotspotProgress;
        y += (hotspotBounds.exactCenterY() - y) * hotspotProgress;
        float radius = getRadius();
        if (radius < 0) {
            radius = (float) Math.hypot(hotspotBounds.width() / 2f, hotspotBounds.height() / 2f);
        }
        feedbackBounds.set(x - radius, y - radius, x + radius, y + radius);
    }
    @Override
    public Rect getDirtyBounds() {
        if (feedbackBounds == null) {
            return super.getDirtyBounds();
        }
        dirtyBounds.set(getBounds());
        dirtyBounds.union(previousFeedbackBounds);
        updateFeedbackBounds(findDrawableByLayerId(android.R.id.mask));
        feedbackBounds.roundOut(previousFeedbackBounds);
        dirtyBounds.union(previousFeedbackBounds);
        return dirtyBounds;
    }
    @Override
    public void setHotspot(float x, float y) {
        super.setHotspot(x, y);
        hotspotX = x;
        hotspotY = y;
        hasHotspot = true;
        if (active && pressed) {
            useHotspot = true;
        }
        invalidateSelf();
    }
    @Override
    public void setColor(@NonNull ColorStateList color) {
        super.setColor(color);
        feedbackColors = color;
        invalidateSelf();
    }
    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        for (int i = 0; i < getNumberOfLayers(); i++) {
            if (getId(i) != android.R.id.mask) {
                getDrawable(i).setAlpha(alpha);
            }
        }
        invalidateSelf();
    }
    @Override
    public int getAlpha() {
        return alpha;
    }
    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();
        cancelAnimator();
        progress = isVisible() && isCallbackVisible() ? feedbackTarget() : 0f;
        hotspotProgress = progress;
        invalidateSelf();
    }
    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) {
            cancelAnimator();
            progress = 0f;
            hotspotProgress = 0f;
            hasHotspot = false;
            useHotspot = false;
        } else if (changed || restart) {
            animateFeedback();
        }
        return changed;
    }
    @Override
    public ConstantState getConstantState() {
        return null;
    }
}
