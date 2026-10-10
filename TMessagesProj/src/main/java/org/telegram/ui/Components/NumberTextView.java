/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.Locale;

import androidx.annotation.Keep;

public class NumberTextView extends View {

    private ArrayList<StaticLayout> letters = new ArrayList<>();
    private ArrayList<StaticLayout> oldLetters = new ArrayList<>();
    private TextPaint textPaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
    private ObjectAnimator animator;
    private float progress = 0.0f;
    private int currentNumber = 1;
    private boolean addNumber;
    private boolean center;
    private float textWidth;
    private float oldTextWidth;

    private boolean presented;
    private boolean hasPendingNumber;
    private int pendingNumber;
    private ObjectAnimator windowPausedAnimator;
    private final NimarkoUiAnimationClock.ResumeGate animationResumeGate = value ->
            isAttachedToWindow() && getWindowVisibility() == VISIBLE;
    private CounterView.CounterSnapshot snapshot;
    private float snapshotTextWidth;
    private float presentedProgress;
    private final Runnable resumeNumber = new Runnable() {
        @Override
        public void run() {
            if ((!hasPendingNumber && windowPausedAnimator == null && (snapshot == null || !snapshot.isWindowPaused())) || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
            if (NimarkoUiAnimationClock.isPaused()) postOnAnimation(this);
            else {
                if (snapshot != null) snapshot.resumeWindow();
                if (windowPausedAnimator == animator && animator != null && animator.isPaused()
                        && !NimarkoUiAnimationClock.resumeAnimation(animator)) return;
                windowPausedAnimator = null;
                invalidate();
            }
        }
    };
    private OnTextWidthProgressChangedListener onTextWidthProgressChangedListener;

    public NumberTextView(Context context) {
        super(context);
    }

    public void setOnTextWidthProgressChangedListener(OnTextWidthProgressChangedListener onTextWidthProgressChangedListener) {
        this.onTextWidthProgressChangedListener = onTextWidthProgressChangedListener;
    }

    @Keep
    public void setProgress(float value) {
        if (progress == value) {
            return;
        }
        progress = value;
        if (onTextWidthProgressChangedListener != null) {
            onTextWidthProgressChangedListener.onTextWidthProgress(oldTextWidth, textWidth, progress);
        }
        invalidate();
    }

    @Keep
    public float getProgress() {
        return progress;
    }

    public void setAddNumber() {
        addNumber = true;
    }

    public void setNumber(int number, boolean animated) {
        if (animated && presented && isAttachedToWindow()
                && (NimarkoUiAnimationClock.isPaused() || getWindowVisibility() != VISIBLE)) {
            if (!hasPendingNumber && currentNumber != number
                    && (animator != null || (snapshot != null && snapshot.hasSnapshot()))) {
                if (snapshot == null) snapshot = new CounterView.CounterSnapshot(this, () -> {
                    if (onTextWidthProgressChangedListener != null) onTextWidthProgressChangedListener.onTextWidthProgress(oldTextWidth, textWidth, progress);
                });
                float saved = progress;
                progress = presentedProgress;
                float glyphWidth = Math.max(getTextWidth(), oldLetters.isEmpty() ? 0f : oldTextWidth);
                if (snapshot.capture(this::drawContent, getMeasuredWidth(), getMeasuredHeight())) {
                    snapshotTextWidth = glyphWidth;
                }
                progress = saved;
            }
            boolean returnToSnapshotTarget = hasPendingNumber && animator == null && snapshot != null && snapshot.hasSnapshot();
            if (hasPendingNumber && currentNumber == number && !returnToSnapshotTarget && snapshot != null) snapshot.finish();
            pendingNumber = number;
            hasPendingNumber = currentNumber != number || returnToSnapshotTarget;
            removeCallbacks(resumeNumber);
            if (getWindowVisibility() == VISIBLE && (hasPendingNumber || windowPausedAnimator != null
                    || (snapshot != null && snapshot.isWindowPaused()))) postOnAnimation(resumeNumber);
            invalidate();
            return;
        }
        hasPendingNumber = false;
        removeCallbacks(resumeNumber);
        if (!isAttachedToWindow()) animated = false;
        if (NimarkoUiAnimationClock.isPaused() && !presented) animated = false;
        if (!animated) presented = false;
        if (currentNumber == number && animated) {
            if (getWindowVisibility() == VISIBLE && (windowPausedAnimator != null
                    || (snapshot != null && snapshot.isWindowPaused()))) postOnAnimation(resumeNumber);
            return;
        }
        if (snapshot != null) snapshot.finish();
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        windowPausedAnimator = null;
        oldLetters.clear();
        oldLetters.addAll(letters);
        letters.clear();
        String oldText;
        String text;
        boolean forwardAnimation;
        if (addNumber) {
            oldText = String.format(Locale.US, "#%d", currentNumber);
            text = String.format(Locale.US, "#%d", number);
            forwardAnimation = number < currentNumber;
        } else {
            oldText = String.format(Locale.US, "%d", currentNumber);
            text = String.format(Locale.US, "%d", number);
            forwardAnimation = number > currentNumber;
        }
        boolean replace = false;
        textWidth = textPaint.measureText(text);
        oldTextWidth = textPaint.measureText(oldText);
        if (center) {
            if (textWidth != oldTextWidth) {
                replace = true;
            }
        }

        currentNumber = number;
        progress = 0;
        for (int a = 0; a < text.length(); a++) {
            String ch = text.substring(a, a + 1);
            String oldCh = !oldLetters.isEmpty() && a < oldText.length() ? oldText.substring(a, a + 1) : null;
            if (!replace && oldCh != null && oldCh.equals(ch)) {
                letters.add(oldLetters.get(a));
                oldLetters.set(a, null);
            } else {
                if (replace && oldCh == null) {
                    oldLetters.add(new StaticLayout("", textPaint, 0, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false));
                }
                StaticLayout layout = new StaticLayout(ch, textPaint, (int) Math.ceil(textPaint.measureText(ch)), Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, false);
                letters.add(layout);
            }
        }
        if (animated && !oldLetters.isEmpty()) {
            animator = ObjectAnimator.ofFloat(this, "progress", forwardAnimation ? -1 : 1, 0);
            animator.setDuration(addNumber ? 180 : 150);
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator != animation) return;
                    animator = null;
                    oldLetters.clear();
                }
            });
            animator.start();
            NimarkoUiAnimationClock.track(animator, animationResumeGate);
        } else {
            oldLetters.clear();
            if (onTextWidthProgressChangedListener != null) {
                onTextWidthProgressChangedListener.onTextWidthProgress(oldTextWidth, textWidth, progress);
            }
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        presented = false;
        if (snapshot != null) snapshot.finish();
        windowPausedAnimator = null;
        if (hasPendingNumber) setNumber(pendingNumber, false);
        removeCallbacks(resumeNumber);
        ObjectAnimator previous = animator;
        animator = null;
        if (previous != null) previous.cancel();
        oldLetters.clear();
        setProgress(0);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (snapshot != null) snapshot.windowVisibilityChanged(visibility);
        removeCallbacks(resumeNumber);
        if (visibility != VISIBLE && animator != null && animator.isStarted()) {
            windowPausedAnimator = animator;
            if (!animator.isPaused()) animator.pause();
        }
        if (visibility == VISIBLE && (hasPendingNumber || windowPausedAnimator != null
                || (snapshot != null && snapshot.isWindowPaused()))) postOnAnimation(resumeNumber);
    }

    public void setTextSize(int size) {
        textPaint.setTextSize(AndroidUtilities.dp(size));
        oldLetters.clear();
        letters.clear();
        setNumber(currentNumber, false);
    }

    public void setTextColor(int value) {
        textPaint.setColor(value);
        invalidate();
    }

    public void setTypeface(Typeface typeface) {
        textPaint.setTypeface(typeface);
        oldLetters.clear();
        letters.clear();
        setNumber(currentNumber, false);
    }

    public void setCenterAlign(boolean center) {
        this.center = center;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (snapshot != null) setMeasuredDimension(snapshot.reserveWidth(getMeasuredWidth()), snapshot.reserveHeight(getMeasuredHeight()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (canvas.isHardwareAccelerated()) NimarkoUiAnimationClock.resumeOwned(animator);
        if (hasPendingNumber && canvas.isHardwareAccelerated() && isAttachedToWindow()
                && !NimarkoUiAnimationClock.isPaused() && getWindowVisibility() == VISIBLE) {
            CounterView.CounterSnapshot retained = snapshot;
            snapshot = null;
            setNumber(pendingNumber, animator == null && (retained == null || !retained.hasSnapshot()));
            snapshot = retained;
            if (snapshot != null && snapshot.hasSnapshot()) {
                requestLayout();
                if (onTextWidthProgressChangedListener != null) onTextWidthProgressChangedListener.onTextWidthProgress(oldTextWidth, getTextWidth(), progress);
                snapshot.start();
            }
        }
        if (snapshot != null) {
            snapshot.setSize(getMeasuredWidth(), getMeasuredHeight());
            snapshot.draw(canvas, this::drawContent);
        }
        else drawContent(canvas);
        if (canvas.isHardwareAccelerated() && isAttachedToWindow() && getWindowVisibility() == VISIBLE
                && !NimarkoUiAnimationClock.isPaused()) {
            presented = true;
            presentedProgress = progress;
            if (snapshot != null) snapshot.presented();
        }
    }

    private void drawContent(Canvas canvas) {
        if (letters.isEmpty()) {
            return;
        }
        float height = letters.get(0).getHeight();
        float translationHeight = addNumber ? AndroidUtilities.dp(4) : height;

        float x = 0;
        float oldDx = 0;
        if (center) {
            x = (getMeasuredWidth() - textWidth) / 2f;
            oldDx = (getMeasuredWidth() - oldTextWidth) / 2f - x;
        }
        canvas.save();
        canvas.translate(getPaddingLeft() + x, (getMeasuredHeight() - height) / 2);
        int count = Math.max(letters.size(), oldLetters.size());
        for (int a = 0; a < count; a++) {
            canvas.save();
            StaticLayout old = a < oldLetters.size() ? oldLetters.get(a) : null;
            StaticLayout layout = a < letters.size() ? letters.get(a) : null;
            if (progress > 0) {
                if (old != null) {
                    textPaint.setAlpha((int) (255 * progress));
                    canvas.save();
                    canvas.translate(oldDx, (progress - 1.0f) * translationHeight);
                    old.draw(canvas);
                    canvas.restore();
                    if (layout != null) {
                        textPaint.setAlpha((int) (255 * (1.0f - progress)));
                        canvas.translate(0, progress * translationHeight);
                    }
                } else {
                    textPaint.setAlpha(255);
                }
            } else if (progress < 0) {
                if (old != null) {
                    textPaint.setAlpha((int) (255 * -progress));
                    canvas.save();
                    canvas.translate(oldDx, (1.0f + progress) * translationHeight);
                    old.draw(canvas);
                    canvas.restore();
                }
                if (layout != null) {
                    if (a == count - 1 || old != null) {
                        textPaint.setAlpha((int) (255 * (1.0f + progress)));
                        canvas.translate(0, progress * translationHeight);
                    } else {
                        textPaint.setAlpha(255);
                    }
                }
            } else if (layout != null) {
                textPaint.setAlpha(255);
            }
            if (layout != null) {
                layout.draw(canvas);
            }
            canvas.restore();
            canvas.translate(layout != null ? layout.getLineWidth(0) : old.getLineWidth(0) + AndroidUtilities.dp(1), 0);
            if (layout != null && old != null) {
                oldDx += old.getLineWidth(0) - layout.getLineWidth(0);
            }
        }
        canvas.restore();
    }

    public float getOldTextWidth() {
        return oldTextWidth;
    }

    public float getTextWidth() {
        return snapshot != null && snapshot.hasSnapshot() ? Math.max(snapshotTextWidth, textWidth) : textWidth;
    }

    public interface OnTextWidthProgressChangedListener {
        /**
         * Notifies layout that text width has changed
         * @param fromWidth Old text width value
         * @param toWidth New text width value
         * @param progress Progress for the animation
         */
        void onTextWidthProgress(float fromWidth, float toWidth, float progress);
    }
}
