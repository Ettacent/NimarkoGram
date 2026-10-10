/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;
public class UnreadCounterTextView extends View {

    private int currentCounter;
    private String currentCounterString;
    private int textWidth;
    private TextPaint textPaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF rect = new RectF();
    private int circleWidth;
    private int rippleColor;

    private Drawable icon;
    private StaticLayout textLayout;
    private Drawable iconOut;
    private StaticLayout textLayoutOut;
    private int layoutTextWidth;
    private TextPaint layoutPaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);

    Drawable selectableBackground;

    ValueAnimator replaceAnimator;
    float replaceProgress = 1f;
    boolean animatedFromBottom;
    int textColor;
    int panelBackgroundColor;
    int counterColor;
    CharSequence lastText;

    private boolean presented;
    private CharSequence pendingText;
    private boolean pendingFromBottom;
    private ValueAnimator windowPausedAnimator;
    private final NimarkoUiAnimationClock.ResumeGate animationResumeGate = value ->
            isAttachedToWindow() && getWindowVisibility() == VISIBLE;
    private CounterView.CounterSnapshot snapshot;
    private float presentedProgress = 1f;
    private String presentedCounterString;
    private int presentedCircleWidth;
    private int presentedTextWidth;
    private final Runnable resumeText = new Runnable() {
        @Override
        public void run() {
            if ((pendingText == null && windowPausedAnimator == null && (snapshot == null || !snapshot.isWindowPaused())) || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
            if (NimarkoUiAnimationClock.isPaused()) postOnAnimation(this);
            else {
                if (snapshot != null) snapshot.resumeWindow();
                if (windowPausedAnimator == replaceAnimator && replaceAnimator != null && replaceAnimator.isPaused()
                        && !NimarkoUiAnimationClock.resumeAnimation(replaceAnimator)) return;
                windowPausedAnimator = null;
                invalidate();
            }
        }
    };
    int textColorKey = Theme.key_chat_fieldOverlayText;

    public UnreadCounterTextView(Context context) {
        super(context);
        textPaint.setTextSize(AndroidUtilities.dp(13));
        textPaint.setTypeface(AndroidUtilities.bold());

        layoutPaint.setTextSize(AndroidUtilities.dp(15));
        layoutPaint.setTypeface(AndroidUtilities.bold());
    }

    public void setText(CharSequence text, boolean animatedFromBottom) {
        if (presented && isAttachedToWindow()
                && (NimarkoUiAnimationClock.isPaused() || getWindowVisibility() != VISIBLE)) {
            if (pendingText == null && !android.text.TextUtils.equals(lastText, text)
                    && (replaceAnimator != null || (snapshot != null && snapshot.hasSnapshot()))) {
                if (snapshot == null) snapshot = new CounterView.CounterSnapshot(this);
                float saved = replaceProgress;
                String savedCounterString = currentCounterString;
                int savedCircleWidth = circleWidth;
                int savedTextWidth = textWidth;
                replaceProgress = presentedProgress;
                currentCounterString = presentedCounterString;
                circleWidth = presentedCircleWidth;
                textWidth = presentedTextWidth;
                snapshot.capture(this::drawContent, getMeasuredWidth(), getMeasuredHeight());
                replaceProgress = saved;
                currentCounterString = savedCounterString;
                circleWidth = savedCircleWidth;
                textWidth = savedTextWidth;
            }
            boolean returnToSnapshotTarget = pendingText != null && replaceAnimator == null && snapshot != null && snapshot.hasSnapshot();
            if (pendingText != null && android.text.TextUtils.equals(lastText, text) && !returnToSnapshotTarget && snapshot != null) snapshot.finish();
            pendingText = android.text.TextUtils.equals(lastText, text) && !returnToSnapshotTarget ? null : android.text.TextUtils.stringOrSpannedString(text);
            pendingFromBottom = animatedFromBottom;
            removeCallbacks(resumeText);
            if (getWindowVisibility() == VISIBLE && (pendingText != null || windowPausedAnimator != null
                    || (snapshot != null && snapshot.isWindowPaused()))) postOnAnimation(resumeText);
            invalidate();
            return;
        }
        pendingText = null;
        removeCallbacks(resumeText);
        if (!isAttachedToWindow() || (!presented && NimarkoUiAnimationClock.isPaused())) {
            setText(text);
            return;
        }
        if (android.text.TextUtils.equals(lastText, text)) {
            if (getWindowVisibility() == VISIBLE && (windowPausedAnimator != null
                    || (snapshot != null && snapshot.isWindowPaused()))) postOnAnimation(resumeText);
            return;
        }
        if (snapshot != null) snapshot.finish();
        finishReplacement();
        lastText = text;
        this.animatedFromBottom = animatedFromBottom;
        textLayoutOut = textLayout;
        iconOut = icon;
        layoutPaint.setTypeface(AndroidUtilities.bold());
        layoutTextWidth = (int) Math.ceil(layoutPaint.measureText(text, 0, text.length()));
        icon = null;
        textLayout = new StaticLayout(text, layoutPaint, layoutTextWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true);
        setContentDescription(text);
        invalidate();

        if (textLayoutOut != null || iconOut != null) {
            replaceProgress = 0;
            replaceAnimator = ValueAnimator.ofFloat(0,1f);
            replaceAnimator.addUpdateListener(animation -> {
                if (replaceAnimator != animation) return;
                replaceProgress = (float) animation.getAnimatedValue();
                invalidate();
            });
            replaceAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (replaceAnimator != animation) return;
                    replaceAnimator = null;
                    finishReplacement();
                }
            });
            replaceAnimator.setDuration(150);
            replaceAnimator.start();
            NimarkoUiAnimationClock.track(replaceAnimator, animationResumeGate);
        }
    }

    public void setText(CharSequence text) {
        if (snapshot != null) snapshot.finish();
        pendingText = null;
        removeCallbacks(resumeText);
        presented = false;
        windowPausedAnimator = null;
        finishReplacement();
        lastText = text;
        layoutPaint.setTypeface(AndroidUtilities.bold());
        layoutTextWidth = (int) Math.ceil(layoutPaint.measureText(text, 0, text.length()));
        icon = null;
        textLayout = new StaticLayout(text, layoutPaint, layoutTextWidth, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true);
        setContentDescription(text);
        invalidate();
    }

    public CharSequence getText() {
        return lastText;
    }

    public void setTextInfo(CharSequence text) {
        if (snapshot != null) snapshot.finish();
        pendingText = null;
        removeCallbacks(resumeText);
        presented = false;
        finishReplacement();
        layoutPaint.setTypeface(null);
        layoutTextWidth = (int) Math.ceil(layoutPaint.measureText(text, 0, text.length()));
        icon = null;
        textLayout = new StaticLayout(text, layoutPaint, layoutTextWidth + 1, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true);
        setContentDescription(text);
        invalidate();
    }

    public void setTextInfo(Drawable icon, CharSequence text) {
        if (snapshot != null) snapshot.finish();
        pendingText = null;
        removeCallbacks(resumeText);
        presented = false;
        finishReplacement();
        layoutPaint.setTypeface(null);
        layoutTextWidth = (int) Math.ceil(layoutPaint.measureText(text, 0, text.length()));
        this.icon = icon;
        textLayout = new StaticLayout(text, layoutPaint, layoutTextWidth + 1, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true);
        setContentDescription(text);
        invalidate();
    }

    private void finishReplacement() {
        windowPausedAnimator = null;
        ValueAnimator previous = replaceAnimator;
        replaceAnimator = null;
        if (previous != null) previous.cancel();
        replaceProgress = 1f;
        textLayoutOut = null;
        iconOut = null;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        presented = false;
        if (pendingText != null) setText(pendingText);
        if (snapshot != null) snapshot.finish();
        removeCallbacks(resumeText);
        finishReplacement();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (snapshot != null) snapshot.windowVisibilityChanged(visibility);
        removeCallbacks(resumeText);
        if (visibility != VISIBLE && replaceAnimator != null && replaceAnimator.isStarted()) {
            windowPausedAnimator = replaceAnimator;
            if (!replaceAnimator.isPaused()) replaceAnimator.pause();
        }
        if (visibility == VISIBLE && (pendingText != null || windowPausedAnimator != null
                || (snapshot != null && snapshot.isWindowPaused()))) postOnAnimation(resumeText);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (selectableBackground != null) {
            selectableBackground.setState(getDrawableState());
        }
    }

    @Override
    public boolean verifyDrawable(Drawable drawable) {
        if (selectableBackground != null) {
            return selectableBackground == drawable || super.verifyDrawable(drawable);
        }
        return super.verifyDrawable(drawable);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (selectableBackground != null) {
            selectableBackground.jumpToCurrentState();
        }
    }

    protected Theme.ResourcesProvider getResourceProvider() {
        return null;
    }

    protected void updateCounter() {
    }

    protected float getTopOffset() {
        return 0;
    }

    public void setCounter(int newCount) {
        if (currentCounter != newCount) {
            currentCounter = newCount;
            if (currentCounter == 0) {
                currentCounterString = null;
                circleWidth = 0;
            } else {
                currentCounterString = AndroidUtilities.formatWholeNumber(currentCounter, 0);
                textWidth = (int) Math.ceil(textPaint.measureText(currentCounterString));
                int newWidth = Math.max(AndroidUtilities.dp(20), AndroidUtilities.dp(12) + textWidth);
                if (circleWidth != newWidth) {
                    circleWidth = newWidth;
                }
            }
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (snapshot != null) setMeasuredDimension(snapshot.reserveWidth(getMeasuredWidth()), snapshot.reserveHeight(getMeasuredHeight()));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (canvas.isHardwareAccelerated()) NimarkoUiAnimationClock.resumeOwned(replaceAnimator);
        if (pendingText != null && canvas.isHardwareAccelerated() && isAttachedToWindow()
                && !NimarkoUiAnimationClock.isPaused() && getWindowVisibility() == VISIBLE) {
            CharSequence latest = pendingText;
            CounterView.CounterSnapshot retained = snapshot;
            snapshot = null;
            if (replaceAnimator == null && (retained == null || !retained.hasSnapshot())) setText(latest, pendingFromBottom);
            else setText(latest);
            snapshot = retained;
            if (snapshot != null && snapshot.hasSnapshot()) {
                requestLayout();
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
            presentedProgress = replaceProgress;
            presentedCounterString = currentCounterString;
            presentedCircleWidth = circleWidth;
            presentedTextWidth = textWidth;
            if (snapshot != null) snapshot.presented();
        }
    }

    private void drawContent(Canvas canvas) {
        Layout layout = textLayout;
        int color = Theme.getColor(isEnabled() ? textColorKey : Theme.key_windowBackgroundWhiteGrayText, getResourceProvider());
        if (textColor != color) {
            layoutPaint.setColor(textColor = color);
        }
        color = Theme.getColor(Theme.key_chat_messagePanelBackground, getResourceProvider());
        if (panelBackgroundColor != color) {
            textPaint.setColor(panelBackgroundColor = color);
        }
        color = Theme.getColor(Theme.key_chat_goDownButtonCounterBackground, getResourceProvider());
        if (counterColor != color) {
            paint.setColor(counterColor = color);
        }

        if (getParent() != null) {
            int contentWidth = getMeasuredWidth();
            int x = (getMeasuredWidth() - contentWidth) / 2;
            if (rippleColor != Theme.getColor(textColorKey, getResourceProvider()) || selectableBackground == null) {
                selectableBackground = Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(60), 0, ColorUtils.setAlphaComponent(rippleColor = Theme.getColor(textColorKey, getResourceProvider()), 26));
                selectableBackground.setCallback(this);
            }
            int start = (getLeft() + x) <= 0 ? x - AndroidUtilities.dp(20) : x;
            int end = x + contentWidth > ((View) getParent()).getMeasuredWidth() ? x + contentWidth + AndroidUtilities.dp(20) : x + contentWidth;
            selectableBackground.setBounds(
                    start, getMeasuredHeight() / 2 - contentWidth / 2,
                    end, getMeasuredHeight() / 2 + contentWidth / 2
            );
            selectableBackground.draw(canvas);
        }
        if (textLayout != null) {
            canvas.save();
            if (replaceProgress != 1f && textLayoutOut != null) {
                int oldAlpha = layoutPaint.getAlpha();

                canvas.save();
                canvas.translate((getMeasuredWidth() - textLayoutOut.getWidth()) / 2 - circleWidth / 2, (getMeasuredHeight() - textLayout.getHeight()) / 2 + getTopOffset());
                canvas.translate(+(iconOut != null ? iconOut.getIntrinsicWidth() / 2 + AndroidUtilities.dp(3) : 0), (animatedFromBottom ? -1f : 1f) * AndroidUtilities.dp(18) * replaceProgress);
                if (iconOut != null) {
                    iconOut.setBounds(
                            -iconOut.getIntrinsicWidth() - AndroidUtilities.dp(6),
                            (textLayout.getHeight() - iconOut.getIntrinsicHeight()) / 2 + AndroidUtilities.dp(1),
                            -AndroidUtilities.dp(6),
                            (textLayout.getHeight() + iconOut.getIntrinsicHeight()) / 2 + AndroidUtilities.dp(1)
                    );
                    iconOut.setAlpha((int) (oldAlpha * (1f - replaceProgress)));
                    iconOut.draw(canvas);
                }
                layoutPaint.setAlpha((int) (oldAlpha * (1f - replaceProgress)));
                textLayoutOut.draw(canvas);
                canvas.restore();

                canvas.save();
                canvas.translate((getMeasuredWidth() - layoutTextWidth) / 2 - circleWidth / 2, (getMeasuredHeight() - textLayout.getHeight()) / 2 + getTopOffset());
                canvas.translate(+(icon != null ? icon.getIntrinsicWidth() / 2 + AndroidUtilities.dp(3) : 0), (animatedFromBottom ? 1f : -1f) * AndroidUtilities.dp(18) * (1f - replaceProgress));
                if (icon != null) {
                    icon.setBounds(
                            -icon.getIntrinsicWidth() - AndroidUtilities.dp(6),
                            (textLayout.getHeight() - icon.getIntrinsicHeight()) / 2 + AndroidUtilities.dp(1),
                            -AndroidUtilities.dp(6),
                            (textLayout.getHeight() + icon.getIntrinsicHeight()) / 2 + AndroidUtilities.dp(1)
                    );
                    icon.setAlpha((int) (oldAlpha * (replaceProgress)));
                    icon.draw(canvas);
                }
                layoutPaint.setAlpha((int) (oldAlpha * (replaceProgress)));
                textLayout.draw(canvas);
                canvas.restore();

                layoutPaint.setAlpha(oldAlpha);
            } else {
                canvas.translate((getMeasuredWidth() - layoutTextWidth) / 2 - circleWidth / 2 + (icon != null ? icon.getIntrinsicWidth() / 2 + AndroidUtilities.dp(3) : 0), (getMeasuredHeight() - textLayout.getHeight()) / 2 + getTopOffset());
                if (icon != null) {
                    icon.setBounds(
                            -icon.getIntrinsicWidth()-AndroidUtilities.dp(6),
                            (textLayout.getHeight() - icon.getIntrinsicHeight()) / 2 + AndroidUtilities.dp(1),
                            -AndroidUtilities.dp(6),
                            (textLayout.getHeight() + icon.getIntrinsicHeight()) / 2 + AndroidUtilities.dp(1)
                    );
                    icon.setAlpha(255);
                    icon.draw(canvas);
                }
                textLayout.draw(canvas);
            }

            canvas.restore();
        }

        if (currentCounterString != null) {
            if (layout != null) {
                int lineWidth = (int) Math.ceil(layout.getLineWidth(0));
                int x = (getMeasuredWidth() - lineWidth) / 2 + lineWidth - circleWidth / 2 + AndroidUtilities.dp(6);
                rect.set(x, getMeasuredHeight() / 2 - AndroidUtilities.dp(10), x + circleWidth, getMeasuredHeight() / 2 + AndroidUtilities.dp(10));
                canvas.drawRoundRect(rect, AndroidUtilities.dp(10), AndroidUtilities.dp(10), paint);
                canvas.drawText(currentCounterString, rect.centerX() - textWidth / 2.0f, rect.top + AndroidUtilities.dp(14.5f), textPaint);
            }
        }
    }

    public void setTextColorKey(int textColorKey) {
        this.textColorKey = textColorKey;
        invalidate();
    }
}