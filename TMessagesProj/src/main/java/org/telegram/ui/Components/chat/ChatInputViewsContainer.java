package org.telegram.ui.Components.chat;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.inset.InAppKeyboardInsetView;
import org.telegram.ui.Components.inset.WindowInsetsProvider;

public class ChatInputViewsContainer extends FrameLayout {
    public static final int INPUT_BUBBLE_RADIUS = 22;
    public static final int INPUT_KEYBOARD_RADIUS = 29;

    public static final int INPUT_BUBBLE_BOTTOM = 9;
    public static final int SEPARATED_COMPOSER_SIDE_SIZE = 44;
    public static final int SEPARATED_COMPOSER_GAP = 4;

    private static final int INPUT_BUBBLE_DRAWABLE_PADDING = 7;

    private WindowInsetsProvider windowInsetsProvider;

    private final View fadeView;
    private final FrameLayout inputIslandBubbleContainer;
    private final FrameLayout inAppKeyboardBubbleContainer;

    public ChatInputViewsContainer(@NonNull Context context) {
        super(context);

        inputIslandBubbleContainer = new FrameLayout(context);
        addView(inputIslandBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        inAppKeyboardBubbleContainer = new FrameLayout(context) {
            @Override
            public void addView(View child, int width, int height) {
                super.addView(child, width, height);
                checkViewsPositions();
            }
        };
        addView(inAppKeyboardBubbleContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        fadeView = new View(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (backgroundWithFadeDrawable != null) {
                    backgroundWithFadeDrawable.draw(canvas);
                }
                super.dispatchDraw(canvas);
            }
        };
    }

    public View getFadeView() {
        return fadeView;
    }

    public void setWindowInsetsProvider(WindowInsetsProvider windowInsetsProvider) {
        this.windowInsetsProvider = windowInsetsProvider;
    }

    public boolean drawInputBackground = true;
    private boolean drawInputCenterBackground = true;
    public BlurredBackgroundDrawable blurredBackgroundDrawable;
    private BlurredBackgroundDrawable leadingComposerDrawable;
    private BlurredBackgroundDrawable trailingComposerDrawable;
    private BlurredBackgroundDrawable underKeyboardBackgroundDrawable;
    public void setInputIslandBubbleDrawable(BlurredBackgroundDrawable drawable) {
        blurredBackgroundDrawable = drawable;
        blurredBackgroundDrawable.setPadding(dp(INPUT_BUBBLE_DRAWABLE_PADDING));
        blurredBackgroundDrawable.setRadius(dp(INPUT_BUBBLE_RADIUS));
    }

    public void setSeparatedComposerDrawables(
            BlurredBackgroundDrawable leadingDrawable,
            BlurredBackgroundDrawable trailingDrawable) {
        leadingComposerDrawable = leadingDrawable;
        trailingComposerDrawable = trailingDrawable;
        configureComposerSideDrawable(leadingComposerDrawable);
        configureComposerSideDrawable(trailingComposerDrawable);
    }

    private void configureComposerSideDrawable(BlurredBackgroundDrawable drawable) {
        if (drawable != null) {
            drawable.setPadding(dp(INPUT_BUBBLE_DRAWABLE_PADDING));
            drawable.setRadius(dp(SEPARATED_COMPOSER_SIDE_SIZE / 2f));
            drawable.setAlpha(inputBubbleAlpha);
        }
    }

    public void setComposerLiquidGlassIntensity(float intensity) {
        intensity = Math.max(0f, intensity);
        if (blurredBackgroundDrawable != null) {
            blurredBackgroundDrawable.setIntensity(intensity);
        }
        if (leadingComposerDrawable != null) {
            leadingComposerDrawable.setIntensity(intensity);
        }
        if (trailingComposerDrawable != null) {
            trailingComposerDrawable.setIntensity(intensity);
        }
        invalidate();
    }

    public void setUnderKeyboardBackgroundDrawable(BlurredBackgroundDrawable drawable) {
        underKeyboardBackgroundDrawable = drawable;
        underKeyboardBackgroundDrawable.enableInAppKeyboardOptimization();
        underKeyboardBackgroundDrawable.setRadius(dp(INPUT_KEYBOARD_RADIUS), dp(INPUT_KEYBOARD_RADIUS), 0, 0);
        underKeyboardBackgroundDrawable.setThickness(dp(32));
        underKeyboardBackgroundDrawable.setIntensity(0.4f);
    }

    public void updateColors() {
        blurredBackgroundDrawable.updateColors();
        if (leadingComposerDrawable != null) {
            leadingComposerDrawable.updateColors();
        }
        if (trailingComposerDrawable != null) {
            trailingComposerDrawable.updateColors();
        }
        underKeyboardBackgroundDrawable.updateColors();
        invalidate();
    }

    @NonNull
    public FrameLayout getInputIslandBubbleContainer() {
        return inputIslandBubbleContainer;
    }

    @NonNull
    public FrameLayout getInAppKeyboardBubbleContainer() {
        return inAppKeyboardBubbleContainer;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        checkViewsPositions();
        checkInAppKeyboardChild();
    }

    private void checkInAppKeyboardViewHeight() {
        LayoutParams lp = (LayoutParams) inAppKeyboardBubbleContainer.getLayoutParams();

        final int oldHeight = lp.height;
        final int newHeight = windowInsetsProvider.getInAppKeyboardRecommendedViewHeight();

        if (oldHeight != newHeight) {
            lp.height = newHeight;
            requestLayout();
        }
    }

    private final Path underKeyboardPath = new Path();

    private int currentBlurredHeight;
    private void checkBlurredHeight(boolean force) {
        checkViewsPositions();

        final int blurredHeight = inputBubbleHeightRound + dp(INPUT_BUBBLE_BOTTOM) + Math.round(maxBottomInset);
        if (currentBlurredHeight != blurredHeight || force) {
            currentBlurredHeight = blurredHeight;

            final int r = dp(INPUT_KEYBOARD_RADIUS);
            tmpRectF.set(0, getMeasuredHeight() - imeBottomInset, getMeasuredWidth(), getMeasuredHeight());
            underKeyboardPath.rewind();
            underKeyboardPath.addRoundRect(tmpRectF, new float[] {r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
            underKeyboardPath.close();
            invalidate();
        }
    }

    private float maxBottomInset;
    private float imeBottomInset;
    private boolean needDrawInAppKeyboard;

    public void checkInsets() {
        maxBottomInset = windowInsetsProvider.getAnimatedMaxBottomInset();
        imeBottomInset = windowInsetsProvider.getAnimatedImeBottomInset();

        needDrawInAppKeyboard = windowInsetsProvider.inAppViewIsVisible();

        if ((inAppKeyboardBubbleContainer.getVisibility() == VISIBLE) != needDrawInAppKeyboard) {
            inAppKeyboardBubbleContainer.setVisibility(needDrawInAppKeyboard ? VISIBLE : GONE);
        }

        checkInAppKeyboardViewHeight();
        checkBlurredHeight(false);
        checkInAppKeyboardChild();

        if (underKeyboardBackgroundDrawable != null) {
            int leftBottomRadius = 0;
            int rightBottomRadius = 0;
            if (Build.VERSION.SDK_INT >= 31) {
                final WindowInsets insets = getRootWindowInsets();
                if (insets != null) {
                    final RoundedCorner bottomLeft = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
                    final RoundedCorner bottomRight = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT);
                    leftBottomRadius = bottomLeft == null ? 0 : bottomLeft.getRadius();
                    rightBottomRadius = bottomRight == null ? 0 : bottomRight.getRadius();
                }
            }
            underKeyboardBackgroundDrawable.setRadius(dp(INPUT_KEYBOARD_RADIUS), dp(INPUT_KEYBOARD_RADIUS), rightBottomRadius, leftBottomRadius, true);
        }
    }

    private void checkViewsPositions() {
        inputIslandBubbleContainer.setTranslationY(-maxBottomInset - dp(INPUT_BUBBLE_BOTTOM));
        inAppKeyboardBubbleContainer.setTranslationY(inAppKeyboardBubbleContainer.getMeasuredHeight() - imeBottomInset);
    }

    private void checkInAppKeyboardChild() {
        final int navbarHeight = windowInsetsProvider.getCurrentNavigationBarInset();
        final float keyboardHeight = windowInsetsProvider.getAnimatedImeBottomInset();

        for (int a = 0, N = inAppKeyboardBubbleContainer.getChildCount(); a < N; a++) {
            final View child = inAppKeyboardBubbleContainer.getChildAt(a);
            if (child instanceof InAppKeyboardInsetView) {
                InAppKeyboardInsetView insetView = (InAppKeyboardInsetView) child;
                insetView.applyNavigationBarHeight(navbarHeight);
                insetView.applyInAppKeyboardAnimatedHeight(keyboardHeight);
            }
        }
    }

    private float inputBubbleOffsetLeft;
    private float inputBubbleOffsetRight;

    private float inputBubbleHeight;
    private int inputBubbleHeightRound;
    public void setInputBubbleHeight(float height) {
        inputBubbleHeight = height;
        inputBubbleHeightRound = Math.round(inputBubbleHeight);
        checkBlurredHeight(false);
    }

    public void setInputBubbleOffsets(float left, float right) {
        inputBubbleOffsetLeft = left;
        inputBubbleOffsetRight = right;
        invalidate();
    }

    public float getInputBubbleHeight() {
        return inputBubbleHeight;
    }

    public float getInputBubbleTop() {
        return getInputBubbleBottom() - getInputBubbleHeight();
    }

    public float getInputBubbleBottom() {
        return getMeasuredHeight() - maxBottomInset - dp(INPUT_BUBBLE_BOTTOM);
    }

    public void getInputBubbleDrawableBounds(@NonNull Rect out) {
        final int blurTop = getMeasuredHeight() - currentBlurredHeight;
        final int drawablePadding = dp(INPUT_BUBBLE_DRAWABLE_PADDING);
        final int bubbleTop = blurTop + (int) bubbleInputTranlationY;
        final int bubbleBottom = bubbleTop + inputBubbleHeightRound;
        final int fullLeft = Math.round(inputBubbleOffsetLeft);
        final int fullRight = getMeasuredWidth() - Math.round(inputBubbleOffsetRight);
        final int separatedInset = dp(SEPARATED_COMPOSER_SIDE_SIZE + SEPARATED_COMPOSER_GAP);
        final float leadingExpansion = Math.max(
                recordingComposerProgress, leadingComposerExpansionProgress);
        final int separatedLeft = Math.round(lerp(
                separatedInset, fullLeft, leadingExpansion));
        final int centerLeft = Math.round(lerp(
                fullLeft, separatedLeft, separatedComposerProgress));
        final int separatedRight = Math.round(lerp(
                getMeasuredWidth() - separatedInset,
                fullRight,
                getTrailingComposerTakeoverProgress()));
        final int centerRight = Math.round(lerp(
                fullRight, separatedRight, separatedComposerProgress));
        out.set(centerLeft, bubbleTop - drawablePadding, centerRight, bubbleBottom + drawablePadding);
    }

    private float separatedComposerProgress;
    private float recordingComposerProgress;
    private float recordingComposerTarget;
    private ValueAnimator recordingComposerAnimator;
    private float leadingComposerExpansionProgress;
    private float leadingComposerExpansionTarget;
    private ValueAnimator leadingComposerExpansionAnimator;
    private View leadingComposerAnchor;
    private View trailingComposerAnchor;
    public interface LeadingComposerExpansionListener {
        void onLeadingComposerExpansionChanged(float progress);
    }
    private LeadingComposerExpansionListener leadingComposerExpansionListener;

    public void setLeadingComposerExpansionListener(
            LeadingComposerExpansionListener listener) {
        leadingComposerExpansionListener = listener;
        if (listener != null) {
            listener.onLeadingComposerExpansionChanged(
                    leadingComposerExpansionProgress);
        }
    }

    private void dispatchLeadingComposerExpansionChanged() {
        if (leadingComposerExpansionListener != null) {
            leadingComposerExpansionListener.onLeadingComposerExpansionChanged(
                    leadingComposerExpansionProgress);
        }
    }

    public void setSeparatedComposerLeadingAnchor(View anchor) {
        leadingComposerAnchor = anchor;
        invalidate();
    }

    public void setSeparatedComposerTrailingAnchor(View anchor) {
        trailingComposerAnchor = anchor;
        invalidate();
    }

    private float getTrailingComposerTakeoverProgress() {
        if (trailingComposerAnchor == null
                || trailingComposerAnchor.getVisibility() != VISIBLE) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, trailingComposerAnchor.getAlpha()));
    }

    private float getLeadingComposerVisibility() {
        if (leadingComposerAnchor == null) {
            return 1f;
        }
        if (leadingComposerAnchor.getVisibility() != VISIBLE) {
            return 0f;
        }
        float contentAlpha = 1f;
        if (leadingComposerAnchor instanceof ViewGroup) {
            contentAlpha = 0f;
            final ViewGroup group = (ViewGroup) leadingComposerAnchor;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View child = group.getChildAt(i);
                if (child != null && child.getVisibility() == VISIBLE) {
                    contentAlpha = Math.max(contentAlpha, child.getAlpha());
                }
            }
        }
        return Math.max(0f, Math.min(1f,
                leadingComposerAnchor.getAlpha() * contentAlpha));
    }

    public void setSeparatedComposerProgress(float progress) {
        progress = Math.max(0f, Math.min(1f, progress));
        if (separatedComposerProgress != progress) {
            separatedComposerProgress = progress;
            invalidate();
        }
    }

    public void setRecordingComposer(boolean recording, boolean animated) {
        final float target = recording ? 1f : 0f;
        if (recordingComposerAnimator != null
                && Math.abs(recordingComposerTarget - target) < 0.001f) {
            return;
        }
        if (recordingComposerAnimator != null) {
            recordingComposerAnimator.removeAllListeners();
            recordingComposerAnimator.cancel();
            recordingComposerAnimator = null;
        }
        recordingComposerTarget = target;
        if (!animated || !isLaidOut()
                || Math.abs(recordingComposerProgress - target) < 0.001f) {
            recordingComposerProgress = target;
            invalidate();
            return;
        }
        recordingComposerAnimator = ValueAnimator.ofFloat(recordingComposerProgress, target);
        recordingComposerAnimator.setDuration(recording ? 150L : 180L);
        recordingComposerAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        recordingComposerAnimator.addUpdateListener(animation -> {
            recordingComposerProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        recordingComposerAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (recordingComposerAnimator == animation) {
                    recordingComposerProgress = target;
                    recordingComposerAnimator = null;
                    invalidate();
                }
            }
        });
        recordingComposerAnimator.start();
    }

    public void setRecordingComposerProgress(float progress) {
        progress = Math.max(0f, Math.min(1f, progress));
        if (recordingComposerAnimator != null) {
            recordingComposerAnimator.removeAllListeners();
            recordingComposerAnimator.cancel();
            recordingComposerAnimator = null;
        }
        recordingComposerTarget = progress;
        if (Math.abs(recordingComposerProgress - progress) > 0.001f) {
            recordingComposerProgress = progress;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        checkBlurredHeight(true);
        checkDrawableBounds();
        checkViewsPositions();
        checkInAppKeyboardChild();
    }

    private final Rect tmpRect = new Rect();
    private final RectF tmpRectF = new RectF();
    private final Rect inputCenterTouchBounds = new Rect();
    private final Rect inputLeadingTouchBounds = new Rect();
    private final Rect inputTrailingTouchBounds = new Rect();
    private int inputBubbleAlpha = 255;

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        underKeyboardBackgroundDrawable.setBounds(
            0,
            getMeasuredHeight() - (int) imeBottomInset,
            getMeasuredWidth(),
            Math.max(getMeasuredHeight(), getMeasuredHeight() - (int) imeBottomInset + dp(INPUT_KEYBOARD_RADIUS * 2))
        );

        if (drawInputBackground) {
            drawComposerBackground(canvas, inputBubbleAlpha);
        } else {
            inputLeadingTouchBounds.setEmpty();
            inputTrailingTouchBounds.setEmpty();
        }

        if (needDrawInAppKeyboard) {
            underKeyboardBackgroundDrawable.draw(canvas);
        }

        super.dispatchDraw(canvas);
    }

    private void drawComposerBackground(@NonNull Canvas canvas, int alpha) {
        if (blurredBackgroundDrawable == null || alpha <= 0) {
            return;
        }
        syncLeadingComposerExpansion();
        alpha = Math.max(0, Math.min(255, alpha));
        final int drawablePadding = dp(INPUT_BUBBLE_DRAWABLE_PADDING);
        getInputBubbleDrawableBounds(tmpRect);
        final int bubbleBottom = tmpRect.bottom - drawablePadding;
        inputCenterTouchBounds.set(tmpRect);
        inputCenterTouchBounds.inset(drawablePadding, drawablePadding);

        blurredBackgroundDrawable.setAlpha(alpha);
        blurredBackgroundDrawable.setBounds(tmpRect);
        if (drawInputCenterBackground) {
            blurredBackgroundDrawable.draw(canvas);
        }

        if (separatedComposerProgress > 0f) {
            final int sideOuterSize = dp(SEPARATED_COMPOSER_SIDE_SIZE) + drawablePadding * 2;
            int leadingContentHeight = dp(SEPARATED_COMPOSER_SIDE_SIZE);
            if (leadingComposerAnchor != null) {
                final ViewGroup.LayoutParams anchorParams = leadingComposerAnchor.getLayoutParams();
                if (anchorParams != null && anchorParams.height > 0) {
                    leadingContentHeight = anchorParams.height;
                } else if (leadingComposerAnchor.getHeight() > 0) {
                    leadingContentHeight = leadingComposerAnchor.getHeight();
                }
            }
            final int sideOuterTop = bubbleBottom - dp(SEPARATED_COMPOSER_SIDE_SIZE) - drawablePadding;
            final int leadingOuterTop = bubbleBottom - leadingContentHeight - drawablePadding;
            final int sideOuterBottom = bubbleBottom + drawablePadding;
            final float sideScale = lerp(0.7f, 1f, separatedComposerProgress);
            final float leadingVisibility = getLeadingComposerVisibility();
            final float leadingSurfaceVisibility = Math.max(
                    leadingVisibility, 1f - leadingComposerExpansionProgress);
            final float leadingScale = sideScale * lerp(0.7f, 1f,
                    Math.max(0f, Math.min(1f, leadingSurfaceVisibility)));
            final int leadingHalf = Math.round(sideOuterSize * leadingScale / 2f);
            final int trailingHalf = Math.round(sideOuterSize * sideScale / 2f);
            final int leadingCenter = sideOuterSize / 2;
            final int trailingCenter = getMeasuredWidth() - sideOuterSize / 2;

            final Rect leadingBounds = inputLeadingTouchBounds;
            final Rect trailingBounds = inputTrailingTouchBounds;
            leadingBounds.set(leadingCenter - leadingHalf, leadingOuterTop,
                    leadingCenter + leadingHalf, sideOuterBottom);
            trailingBounds.set(trailingCenter - trailingHalf, sideOuterTop,
                    trailingCenter + trailingHalf, sideOuterBottom);

            final int sideAlpha = Math.round(alpha * separatedComposerProgress
                    * (1f - recordingComposerProgress));
            final int trailingAlpha = Math.round(sideAlpha
                    * (1f - getTrailingComposerTakeoverProgress()));
            final int leadingAlpha = Math.round(sideAlpha
                    * (1f - leadingComposerExpansionProgress)
                    * Math.max(0f, Math.min(1f, leadingSurfaceVisibility)));
            final BlurredBackgroundDrawable leadingDrawable = leadingComposerDrawable != null
                    ? leadingComposerDrawable : blurredBackgroundDrawable;
            final BlurredBackgroundDrawable trailingDrawable = trailingComposerDrawable != null
                    ? trailingComposerDrawable : blurredBackgroundDrawable;
            if (leadingAlpha > 0) {
                leadingDrawable.setAlpha(leadingAlpha);
                leadingDrawable.setBounds(leadingBounds);
                leadingDrawable.draw(canvas);
            }
            if (trailingAlpha > 0) {
                trailingDrawable.setAlpha(trailingAlpha);
                trailingDrawable.setBounds(trailingBounds);
                trailingDrawable.draw(canvas);
            }
            inputLeadingTouchBounds.inset(drawablePadding, drawablePadding);
            inputTrailingTouchBounds.inset(drawablePadding, drawablePadding);
            if (leadingAlpha == 0) {
                inputLeadingTouchBounds.setEmpty();
            }
            if (trailingAlpha == 0) {
                inputTrailingTouchBounds.setEmpty();
            }
        } else {
            inputLeadingTouchBounds.setEmpty();
            inputTrailingTouchBounds.setEmpty();
        }

        blurredBackgroundDrawable.setAlpha(inputBubbleAlpha);
        blurredBackgroundDrawable.setBounds(tmpRect);
        if (leadingComposerDrawable != null) {
            leadingComposerDrawable.setAlpha(inputBubbleAlpha);
        }
        if (trailingComposerDrawable != null) {
            trailingComposerDrawable.setAlpha(inputBubbleAlpha);
        }
    }

    private void syncLeadingComposerExpansion() {
        final float target = separatedComposerProgress > 0f
                && getLeadingComposerVisibility() <= 0.01f ? 1f : 0f;
        if (Math.abs(leadingComposerExpansionTarget - target) < 0.001f) {
            return;
        }
        leadingComposerExpansionTarget = target;
        if (leadingComposerExpansionAnimator != null) {
            leadingComposerExpansionAnimator.removeAllListeners();
            leadingComposerExpansionAnimator.cancel();
            leadingComposerExpansionAnimator = null;
        }
        if (!isLaidOut()) {
            leadingComposerExpansionProgress = target;
            dispatchLeadingComposerExpansionChanged();
            return;
        }
        leadingComposerExpansionAnimator = ValueAnimator.ofFloat(
                leadingComposerExpansionProgress, target);
        leadingComposerExpansionAnimator.setDuration(target > leadingComposerExpansionProgress
                ? 180L : 220L);
        leadingComposerExpansionAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
        leadingComposerExpansionAnimator.addUpdateListener(animation -> {
            leadingComposerExpansionProgress = (float) animation.getAnimatedValue();
            dispatchLeadingComposerExpansionChanged();
            invalidate();
        });
        leadingComposerExpansionAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (leadingComposerExpansionAnimator == animation) {
                    leadingComposerExpansionProgress = target;
                    leadingComposerExpansionAnimator = null;
                    dispatchLeadingComposerExpansionChanged();
                    invalidate();
                }
            }
        });
        leadingComposerExpansionAnimator.start();
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        final boolean needClip = child == inAppKeyboardBubbleContainer;
        if (needClip) {
            canvas.save();
            canvas.clipPath(underKeyboardBackgroundDrawable.getPath());
        }

        final boolean result = super.drawChild(canvas, child, drawingTime);
        if (needClip) {
            canvas.restore();
        }

        return result;
    }

    private BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable;

    public void setBackgroundWithFadeDrawable(BlurredBackgroundWithFadeDrawable backgroundWithFadeDrawable) {
        this.backgroundWithFadeDrawable = backgroundWithFadeDrawable;
    }

    private float blurredBottomHeight;
    public void setBlurredBottomHeight(float height) {
        if (blurredBottomHeight != height) {
            blurredBottomHeight = height;
            checkDrawableBounds();
        }
    }

    private float bubbleInputTranlationY;
    public void setInputBubbleTranslationY(float translationY) {
        this.bubbleInputTranlationY = translationY;
        invalidate();
    }

    public void setInputBubbleAlpha(int alpha) {
        inputBubbleAlpha = Math.max(0, Math.min(255, alpha));
        if (blurredBackgroundDrawable != null) {
            blurredBackgroundDrawable.setAlpha(inputBubbleAlpha);
        }
        if (leadingComposerDrawable != null) {
            leadingComposerDrawable.setAlpha(inputBubbleAlpha);
        }
        if (trailingComposerDrawable != null) {
            trailingComposerDrawable.setAlpha(inputBubbleAlpha);
        }
        invalidate();
    }

    public int getInputBubbleAlpha() {
        return inputBubbleAlpha;
    }

    public void setDrawInputCenterBackground(boolean draw) {
        if (drawInputCenterBackground != draw) {
            drawInputCenterBackground = draw;
            invalidate();
        }
    }

    public boolean isDrawInputCenterBackground() {
        return drawInputCenterBackground;
    }

    private void checkDrawableBounds() {
        if (backgroundWithFadeDrawable == null) {
            return;
        }

        final int oldBound = backgroundWithFadeDrawable.getBounds().top;
        final int newBound = getMeasuredHeight() - Math.round(blurredBottomHeight);

        if (oldBound != newBound) {
            backgroundWithFadeDrawable.setBounds(0, newBound, getMeasuredWidth(), getMeasuredHeight());
            fadeView.invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
            invalidate(0, Math.max(0, Math.min(oldBound, newBound)), getMeasuredWidth(), getMeasuredHeight());
        }
    }

    private boolean captured;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            final int x = (int) event.getX();
            final int y = (int) event.getY();

            captured = drawInputBackground
                    && blurredBackgroundDrawable != null
                    && inputBubbleAlpha > 0
                    && (inputCenterTouchBounds.contains(x, y)
                        || inputLeadingTouchBounds.contains(x, y)
                        || inputTrailingTouchBounds.contains(x, y))
                || underKeyboardBackgroundDrawable != null && underKeyboardBackgroundDrawable.getBounds().contains(x, y);

        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            captured = false;
        }

        return captured;
    }
}
