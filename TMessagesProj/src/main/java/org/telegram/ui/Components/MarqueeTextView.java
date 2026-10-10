/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.math.MathUtils;

import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;

@SuppressLint("AppCompatCustomView")
public class MarqueeTextView extends TextView {
    private static final int BORDER_DP = 10;
    private static final int MARGIN_DP = 40;
    private static final int SPEED_DP = 60;

    private final Matrix gradientMatrix = new Matrix();
    private LinearGradient gradient;
    private int originalWidth;
    private boolean needMarquee;
    private boolean marqueeIsStarted;
    private float scrollX;

    private String textSnapshot;
    private long remainingDelay = 1500;
    private int clockEpoch = -1;
    private long resumeElapsed;
    private boolean warmResume;
    private Runnable marqueeWakeup;
    private long delayStartedAt;
    private int wakeupGeneration;
    private ViewTreeObserver marqueeObserver;
    private final ViewTreeObserver.OnPreDrawListener marqueeVisibility = () -> {
        if (!canAnimate()) suspendMarquee();
        return true;
    };
    public MarqueeTextView(Context context) {
        super(context);
    }

    @Override
    public void setTextColor(int color) {
        super.setTextColor(color);
        invalidateGradient();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec);
        originalWidth = MeasureSpec.getSize(widthMeasureSpec);
        needMarquee = getMeasuredWidth() > (originalWidth - rightPadding);
        invalidateGradient();
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        final String snapshot = text == null ? "" : text.toString();
        final boolean changed = !TextUtils.equals(textSnapshot, snapshot);
        super.setText(text, type);
        textSnapshot = snapshot;
        if (changed) {
            stopMarqueeInternal();
        }

    }

    private void invalidateGradient() {
        if (originalWidth <= 0) return;
        final float edgeSize = Math.min((float) dp(BORDER_DP) / originalWidth, 0.49f);
        final int color = getCurrentTextColor();

        gradient = new LinearGradient(
            0, 0, originalWidth, 0,
            new int[]{
                color & 0x00FFFFF,
                color,
                color,
                color & 0x00FFFFF
            },
            new float[]{0f, edgeSize, 1f - edgeSize, 1f},
            Shader.TileMode.CLAMP
        );
        if (needMarquee) {
            getPaint().setShader(gradient);
        } else {
            getPaint().setShader(null);
        }
        gradient.setLocalMatrix(gradientMatrix);
        invalidate();
    }

    public boolean isNeedMarquee() {
        return needMarquee;
    }

    private long lastFrameTime;

    private boolean canAnimate() {
        if (NimarkoUiAnimationClock.isPaused() || !isAttachedToWindow() || !isShown()
                || getWindowVisibility() != VISIBLE) return false;
        View view = this;
        while (view != null) {
            if (view.getAlpha() <= 0f || view.getVisibility() != VISIBLE) return false;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return true;
    }

    private void suspendMarquee() {
        cancelMarqueeWakeup(true);
        lastFrameTime = 0;
        warmResume = true;
        resumeElapsed = 0;
    }

    private void cancelMarqueeWakeup(boolean preserveDelay) {
        wakeupGeneration++;
        if (marqueeWakeup != null) {
            if (preserveDelay) remainingDelay = Math.max(0L,
                    remainingDelay - Math.max(0L, NimarkoUiAnimationClock.now() - delayStartedAt));
            removeCallbacks(marqueeWakeup);
            marqueeWakeup = null;
        }
    }

    private void scheduleMarqueeWakeup() {
        if (marqueeWakeup != null) return;
        final int generation = ++wakeupGeneration;
        delayStartedAt = NimarkoUiAnimationClock.now();
        marqueeWakeup = () -> {
            if (generation != wakeupGeneration) return;
            cancelMarqueeWakeup(true);
            lastFrameTime = 0;
            if (canAnimate()) invalidate();
            else suspendMarquee();
        };
        postDelayed(marqueeWakeup, remainingDelay);
    }

    @Override
    protected void onDetachedFromWindow() {
        suspendMarquee();
        if (marqueeObserver != null && marqueeObserver.isAlive()) {
            marqueeObserver.removeOnPreDrawListener(marqueeVisibility);
        }
        marqueeObserver = null;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        marqueeObserver = getViewTreeObserver();
        marqueeObserver.addOnPreDrawListener(marqueeVisibility);
        suspendMarquee();
        invalidate();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        suspendMarquee();
        if (visibility == VISIBLE) invalidate();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        suspendMarquee();
        if (visibility == VISIBLE) invalidate();
    }

    private static double warmDistance(long elapsed) {
        final double t = Math.min(elapsed, 180L) / 180.0;
        return 180.0 * (t * t * t - .5 * t * t * t * t) + Math.max(0L, elapsed - 180L);
    }

    private void advanceMarquee(int textWidth, int textMargin) {
        if (!canAnimate()) {
            suspendMarquee();
            return;
        }
        final long time = NimarkoUiAnimationClock.now();
        final int epoch = NimarkoUiAnimationClock.epoch();
        if (clockEpoch != epoch || marqueeIsStarted && lastFrameTime != 0 && time - lastFrameTime > 120) {
            suspendMarquee();
        }
        clockEpoch = epoch;
        long dt = lastFrameTime == 0 ? 0 : Math.max(0, time - lastFrameTime);
        lastFrameTime = time;
        if (!needMarquee && scrollX == 0f) return;
        if (!marqueeIsStarted && scrollX == 0f) {
            if (remainingDelay > 0) {
                scheduleMarqueeWakeup();
                return;
            }
            dt = 0;
            marqueeIsStarted = true;
        }
        if (warmResume) {
            final long next = resumeElapsed + dt;
            scrollX += dp(SPEED_DP) * (float) ((warmDistance(next) - warmDistance(resumeElapsed)) / 1000.0);
            resumeElapsed = next;
            if (next >= 180) warmResume = false;
        } else {
            scrollX += dp(SPEED_DP) * (dt / 1000f);
        }
        if (scrollX > textWidth + textMargin) stopMarqueeInternal();
        postInvalidateOnAnimation();
    }
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final int textWidth = getMeasuredWidth();
        final int textMargin = dp(MARGIN_DP);

        advanceMarquee(textWidth, textMargin);
        if (gradient == null || originalWidth <= 0) {
            super.onDraw(canvas);
            return;
        }
        final float shadowVisibility;
        if (scrollX < textWidth) {
            shadowVisibility = MathUtils.clamp(scrollX / dp(BORDER_DP), 0, 1);
        } else {
            shadowVisibility = 0;
        }

        gradientMatrix.reset();
        gradientMatrix.postScale(1 + ((float) dp(BORDER_DP) / originalWidth) * (1f - shadowVisibility), 1f, originalWidth, 0);
        gradientMatrix.postScale(1 - ((float) rightPadding / originalWidth), 1, 0, 0);
        gradientMatrix.postTranslate(scrollX, 0);
        gradient.setLocalMatrix(gradientMatrix);
        canvas.save();
        canvas.translate(-scrollX, 0);
        super.onDraw(canvas);
        canvas.restore();

        if (textWidth > 0 && scrollX > 0 && scrollX + getWidth() > textWidth && needMarquee && marqueeIsStarted) {
            gradientMatrix.postTranslate(-scrollX - (-scrollX + textWidth + textMargin), 0);
            gradient.setLocalMatrix(gradientMatrix);
            canvas.save();
            canvas.translate(-scrollX + textWidth + textMargin, 0);
            super.onDraw(canvas);
            canvas.restore();
        }

    }

    private void stopMarqueeInternal() {
        cancelMarqueeWakeup(false);
        marqueeIsStarted = false;
        scrollX = 0f;
        remainingDelay = 1500;
        lastFrameTime = 0;
        warmResume = false;
        resumeElapsed = 0;
    }


    private int rightPadding;
    public void setCustomPaddingRight(int padding) {
        rightPadding = padding;
        needMarquee = getMeasuredWidth() > (originalWidth - rightPadding);
        if (needMarquee) {
            getPaint().setShader(gradient);
        } else {
            getPaint().setShader(null);
        }
        invalidate();
    }
}

