/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class CounterView extends View {

    static final class CounterSnapshot {
        interface Content { void draw(Canvas canvas); }
        private final View owner;
        private final NimarkoUiAnimationClock.ResumeGate animationResumeGate;
        private final Runnable onFinish;
        private Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint incomingPaint = new Paint();
        private ValueAnimator animator;
        private float progress = 1f;
        private float presentedProgress = 1f;
        private boolean windowPaused;
        private int contentWidth;
        private int contentHeight;

        CounterSnapshot(View owner) { this(owner, null); }

        CounterSnapshot(View owner, Runnable onFinish) {
            this.owner = owner;
            animationResumeGate = value -> owner.isAttachedToWindow() && owner.getWindowVisibility() == View.VISIBLE;
            this.onFinish = onFinish;
            incomingPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        }

        boolean capture(Content content, int width, int height) {
            if (width <= 0 || height <= 0 || width > 2048 || height > 256 || (long) width * height > 262144) return false;
            Bitmap next;
            float saved = progress;
            try {
                next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                progress = presentedProgress;
                draw(new Canvas(next), content);
            } catch (RuntimeException | OutOfMemoryError error) {
                return false;
            } finally {
                progress = saved;
            }
            finish();
            bitmap = next;
            contentWidth = width;
            contentHeight = height;
            progress = presentedProgress = 0f;
            return true;
        }

        void start() {
            if (bitmap == null || animator != null) return;
            ValueAnimator next = ValueAnimator.ofFloat(0, 1);
            animator = next;
            next.setDuration(180);
            next.addUpdateListener(value -> {
                if (animator != value) return;
                progress = (float) value.getAnimatedValue();
                owner.invalidate();
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator == animation) {
                        finish();
                        if (onFinish != null) onFinish.run();
                    }
                }
            });
            next.start();
            NimarkoUiAnimationClock.track(next, animationResumeGate);
        }

        boolean hasSnapshot() { return bitmap != null; }
        int reserveWidth(int width) { return Math.max(width, bitmap == null ? 0 : bitmap.getWidth()); }
        int reserveHeight(int height) { return Math.max(height, bitmap == null ? 0 : bitmap.getHeight()); }
        void setSize(int width, int height) { contentWidth = width; contentHeight = height; }
        boolean isWindowPaused() { return windowPaused; }

        void windowVisibilityChanged(int visibility) {
            if (visibility != View.VISIBLE && animator != null && animator.isStarted()) {
                windowPaused = true;
                if (!animator.isPaused()) animator.pause();
            }
        }

        void resumeWindow() {
            if (windowPaused && animator != null && animator.isPaused()
                    && !NimarkoUiAnimationClock.resumeAnimation(animator)) return;
            windowPaused = false;
        }

        void draw(Canvas canvas, Content content) {
            if (canvas.isHardwareAccelerated()) NimarkoUiAnimationClock.resumeOwned(animator);
            if (bitmap == null) {
                content.draw(canvas);
                return;
            }
            int alpha = Math.round(255 * progress);
            int outer = canvas.saveLayer(0, 0, reserveWidth(contentWidth), reserveHeight(contentHeight), null);
            try {
                paint.setAlpha(255 - alpha);
                canvas.drawBitmap(bitmap, 0, 0, paint);
                if (alpha > 0) {
                    incomingPaint.setAlpha(alpha);
                    int incoming = canvas.saveLayer(0, 0, reserveWidth(contentWidth), reserveHeight(contentHeight), incomingPaint);
                    try {
                        content.draw(canvas);
                    } finally {
                        canvas.restoreToCount(incoming);
                    }
                }
            } finally {
                canvas.restoreToCount(outer);
            }
        }

        void presented() { presentedProgress = progress; }

        void finish() {
            boolean hadSnapshot = bitmap != null;
            ValueAnimator previous = animator;
            animator = null;
            if (previous != null) previous.cancel();
            bitmap = null;
            windowPaused = false;
            progress = presentedProgress = 1f;
            if (hadSnapshot) owner.requestLayout();
        }
    }
    public CounterDrawable counterDrawable;
    private final Theme.ResourcesProvider resourcesProvider;

    public CounterView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setVisibility(View.GONE);
        counterDrawable = new CounterDrawable(this, true, resourcesProvider);
        counterDrawable.updateVisibility = true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (counterDrawable.snapshot != null) {
            setMeasuredDimension(counterDrawable.snapshot.reserveWidth(getMeasuredWidth()), counterDrawable.snapshot.reserveHeight(getMeasuredHeight()));
        }
        counterDrawable.setSize(getMeasuredHeight(), getMeasuredWidth());
    }


    @Override
    protected void onDraw(Canvas canvas) {
        counterDrawable.draw(canvas);
    }


    public void setColors(int textKey, int circleKey) {
        counterDrawable.textColorKey = textKey;
        counterDrawable.circleColorKey = circleKey;
    }

    public void setGravity(int gravity) {
        counterDrawable.gravity = gravity;
    }

    public void setReverse(boolean b) {
        counterDrawable.reverseAnimation = b;
    }

    public void setCount(int count, boolean animated) {
        counterDrawable.setCount(count, animated);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        counterDrawable.presented = false;
        if (counterDrawable.snapshot != null) counterDrawable.snapshot.finish();
        counterDrawable.windowPausedAnimator = null;
        counterDrawable.applyPending(false);
        removeCallbacks(counterDrawable.resumeCount);
        counterDrawable.finishAnimation();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (counterDrawable == null) return;
        if (counterDrawable.snapshot != null) counterDrawable.snapshot.windowVisibilityChanged(visibility);
        removeCallbacks(counterDrawable.resumeCount);
        if (visibility != VISIBLE && counterDrawable.countAnimator != null
                && counterDrawable.countAnimator.isStarted()) {
            counterDrawable.windowPausedAnimator = counterDrawable.countAnimator;
            if (!counterDrawable.countAnimator.isPaused()) counterDrawable.countAnimator.pause();
        }
        if (visibility == VISIBLE && (counterDrawable.pendingText != null || counterDrawable.windowPausedAnimator != null
                || (counterDrawable.snapshot != null && counterDrawable.snapshot.isWindowPaused()))) postOnAnimation(counterDrawable.resumeCount);
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public static class CounterDrawable {

        private final static int ANIMATION_TYPE_IN = 0;
        private final static int ANIMATION_TYPE_OUT = 1;
        private final static int ANIMATION_TYPE_REPLACE = 2;
        public boolean shortFormat;
        public float circleScale = 1f;

        int animationType = -1;

        public Paint circlePaint;
        public TextPaint textPaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
        public RectF rectF = new RectF();
        public boolean addServiceGradient;

        int currentCount;
        CharSequence currentText;
        private boolean countAnimationIncrement;
        private ValueAnimator countAnimator;
        public float countChangeProgress = 1f;
        private float countLayoutWidth;
        private StaticLayout countLayout;
        private StaticLayout countOldLayout;
        private StaticLayout countAnimationStableLayout;
        private StaticLayout countAnimationInLayout;

        private boolean presented;
        private CharSequence pendingText;
        private int pendingCount;
        private boolean pendingIsText;
        private ValueAnimator windowPausedAnimator;
        private final NimarkoUiAnimationClock.ResumeGate animationResumeGate = value -> this.parent == null
                || this.parent.isAttachedToWindow() && this.parent.getWindowVisibility() == View.VISIBLE;
        private CounterSnapshot snapshot;
        private float presentedCountProgress = 1f;
        private float snapshotTextWidth;
        private final Runnable resumeCount = new Runnable() {
            @Override
            public void run() {
                if ((pendingText == null && windowPausedAnimator == null && (snapshot == null || !snapshot.isWindowPaused())) || parent == null || !parent.isAttachedToWindow()
                        || parent.getWindowVisibility() != View.VISIBLE) return;
                if (NimarkoUiAnimationClock.isPaused()) parent.postOnAnimation(this);
                else {
                    if (snapshot != null) snapshot.resumeWindow();
                    if (windowPausedAnimator == countAnimator && countAnimator != null && countAnimator.isPaused()
                            && !NimarkoUiAnimationClock.resumeAnimation(countAnimator)) return;
                    windowPausedAnimator = null;
                    parent.invalidate();
                }
            }
        };
        private int countWidthOld;
        private int countWidth;

        private int circleColor;
        private int textColor;
        private int textColorKey = Theme.key_chat_goDownButtonCounter;
        private int circleColorKey = Theme.key_chat_goDownButtonCounterBackground;

        int lastH;
        int width;
        public int gravity = Gravity.CENTER;
        float countLeft;
        float x;
        public float radius = 11.5f;

        private boolean reverseAnimation;
        public float horizontalPadding;
        private boolean drawBackground = true;

        public boolean updateVisibility;

        private View parent;

        public final static int TYPE_DEFAULT = 0;
        public final static int TYPE_CHAT_PULLING_DOWN = 1;
        public final static int TYPE_CHAT_REACTIONS = 2;

        int type = TYPE_DEFAULT;
        private final Theme.ResourcesProvider resourcesProvider;

        public CounterDrawable(View parent, boolean drawBackground, Theme.ResourcesProvider resourcesProvider) {
            this.parent = parent;
            this.resourcesProvider = resourcesProvider;
            this.drawBackground = drawBackground;
            if (drawBackground) {
                circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                circlePaint.setColor(Color.BLACK);
            }
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setTextSize(AndroidUtilities.dp(13));
        }

        public void setSize(int h, int w) {
            lastH = h;
            width = w;
        }


        private void drawInternal(Canvas canvas) {
            float size = radius * 2;
            float countTop = (lastH - AndroidUtilities.dp(size)) / 2f;
            updateX(countWidth);
            rectF.set(x, countTop, x + countWidth + AndroidUtilities.dp(radius - 0.5f), countTop + AndroidUtilities.dp(size));
            if (circlePaint != null && drawBackground) {
                boolean needRestore = false;
                if (circleScale != 1f) {
                    canvas.save();
                    canvas.scale(circleScale, circleScale, rectF.centerX(), rectF.centerY());
                    needRestore = true;
                }
                canvas.drawRoundRect(rectF, radius * AndroidUtilities.density, radius * AndroidUtilities.density, circlePaint);
                if (addServiceGradient && Theme.hasGradientService()) {
                    canvas.drawRoundRect(rectF, radius * AndroidUtilities.density, radius * AndroidUtilities.density, Theme.chat_actionBackgroundGradientDarkenPaint);
                }
                if (needRestore) {
                    canvas.restore();
                }
            }
            if (countLayout != null) {
                canvas.save();
                canvas.translate(countLeft + getCounterTextOffset(countLayout), countTop + AndroidUtilities.dp(4));
                countLayout.draw(canvas);
                canvas.restore();
            }
        }

        public void setCount(int count, boolean animated) {
            setText(getStringOfCCount(count), animated, count, false);
        }

        public void setText(CharSequence text, boolean animated) {
            setText(text, animated, 1, true);
        }

        public void setText(CharSequence text, boolean animated, int count, boolean isText) {
            if (animated && presented && parent != null && parent.isAttachedToWindow()
                    && parent.getVisibility() == View.VISIBLE
                    && (NimarkoUiAnimationClock.isPaused() || parent.getWindowVisibility() != View.VISIBLE)) {
                if (pendingText == null && !(TextUtils.equals(text, currentText) && count == currentCount)
                        && (countAnimator != null || (snapshot != null && snapshot.hasSnapshot()))) {
                    if (snapshot == null) snapshot = new CounterSnapshot(parent, () -> {
                        if (currentCount == 0 && updateVisibility) parent.setVisibility(View.GONE);
                    });
                    float saved = countChangeProgress;
                    snapshotTextWidth = Math.max(countLayoutWidth, countWidthOld);
                    countChangeProgress = presentedCountProgress;
                    snapshot.capture(this::drawContent, width, lastH);
                    countChangeProgress = saved;
                }
                boolean returnToSnapshotTarget = pendingText != null && countAnimator == null && snapshot != null && snapshot.hasSnapshot();
                boolean hadPending = pendingText != null;
                pendingText = TextUtils.equals(text, currentText) && count == currentCount && !returnToSnapshotTarget ? null : TextUtils.stringOrSpannedString(text);
                if (hadPending && pendingText == null && snapshot != null) snapshot.finish();
                pendingCount = count;
                pendingIsText = isText;
                if (parent.getWindowVisibility() != View.VISIBLE && countAnimator != null
                        && countAnimator.isStarted()) {
                    windowPausedAnimator = countAnimator;
                    if (!countAnimator.isPaused()) countAnimator.pause();
                }
                parent.removeCallbacks(resumeCount);
                if (parent.getWindowVisibility() == View.VISIBLE && (pendingText != null || windowPausedAnimator != null
                        || (snapshot != null && snapshot.isWindowPaused()))) parent.postOnAnimation(resumeCount);
                parent.invalidate();
                return;
            }
            pendingText = null;
            if (parent != null) parent.removeCallbacks(resumeCount);
            if (parent != null && !parent.isAttachedToWindow()) animated = false;
            if (NimarkoUiAnimationClock.isPaused() && !presented) animated = false;
            if (!animated) presented = false;
            if (TextUtils.equals(text, currentText)) {
                if (!animated) {
                    if (snapshot != null) snapshot.finish();
                    finishAnimation();
                } else if (parent != null && parent.getWindowVisibility() == View.VISIBLE
                        && (windowPausedAnimator != null || (snapshot != null && snapshot.isWindowPaused()))) {
                    parent.postOnAnimation(resumeCount);
                }
                return;
            }
            if (snapshot != null) snapshot.finish();
            finishAnimation();
            if (count > 0 && updateVisibility && parent != null) {
                parent.setVisibility(View.VISIBLE);
            }
            if (Math.abs(count - currentCount) > 99) {
                animated = false;
            }
            if (!animated) {
                currentCount = count;
                currentText = text;
                if (count == 0) {
                    if (updateVisibility && parent != null) {
                        parent.setVisibility(View.GONE);
                    }
                    return;
                }
                CharSequence newStr = text; // getStringOfCCount(count);
                countWidth = Math.max(AndroidUtilities.dp(12), (int) Math.ceil(textPaint.measureText(newStr.toString())));
                countLayout = new StaticLayout(newStr, textPaint, countWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
                countLayoutWidth = countLayout.getLineCount() >= 1 ? countLayout.getLineWidth(0) : 0;
                if (parent != null) {
                    parent.invalidate();
                }
                return;
            }
            CharSequence newStr = text; // getStringOfCCount(count);

            if (animated) {
                countChangeProgress = 0f;
                countAnimator = ValueAnimator.ofFloat(0, 1f);
                countAnimator.addUpdateListener(valueAnimator -> {
                    if (countAnimator != valueAnimator) return;
                    countChangeProgress = (float) valueAnimator.getAnimatedValue();
                    if (parent != null) {
                        parent.invalidate();
                    }
                });
                countAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (countAnimator != animation) return;
                        countAnimator = null;
                        countChangeProgress = 1f;
                        countOldLayout = null;
                        countAnimationStableLayout = null;
                        countAnimationInLayout = null;
                        if (parent != null) {
                            if (currentCount == 0 && updateVisibility) {
                                parent.setVisibility(View.GONE);
                            }
                            parent.invalidate();
                        }
                        animationType = -1;
                    }
                });
                if (currentCount <= 0) {
                    animationType = ANIMATION_TYPE_IN;
                    countAnimator.setDuration(220);
                    countAnimator.setInterpolator(new OvershootInterpolator());
                } else if (count == 0) {
                    animationType = ANIMATION_TYPE_OUT;
                    countAnimator.setDuration(150);
                    countAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                } else {
                    animationType = ANIMATION_TYPE_REPLACE;
                    countAnimator.setDuration(430);
                    countAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                }
                if (countLayout != null) {
                    CharSequence oldStr = currentText; // getStringOfCCount(currentCount);

                    if (oldStr.length() == newStr.length() && !isText) {
                        SpannableStringBuilder oldSpannableStr = new SpannableStringBuilder(oldStr);
                        SpannableStringBuilder newSpannableStr = new SpannableStringBuilder(newStr);
                        SpannableStringBuilder stableStr = new SpannableStringBuilder(newStr);
                        for (int i = 0; i < oldStr.length(); i++) {
                            if (oldStr.charAt(i) == newStr.charAt(i)) {
                                oldSpannableStr.setSpan(new EmptyStubSpan(), i, i + 1, 0);
                                newSpannableStr.setSpan(new EmptyStubSpan(), i, i + 1, 0);
                            } else {
                                stableStr.setSpan(new EmptyStubSpan(), i, i + 1, 0);
                            }
                        }

                        int countOldWidth = Math.max(AndroidUtilities.dp(12), (int) Math.ceil(textPaint.measureText(oldStr.toString())));
                        if (type == TYPE_CHAT_REACTIONS) {
                            countOldWidth = Math.max(countOldWidth, (int) Math.ceil(Math.max(
                                    Layout.getDesiredWidth(oldSpannableStr, textPaint), Math.max(
                                            Layout.getDesiredWidth(stableStr, textPaint),
                                            Layout.getDesiredWidth(newSpannableStr, textPaint)))));
                        }
                        countOldLayout = new StaticLayout(oldSpannableStr, textPaint, countOldWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
                        countAnimationStableLayout = new StaticLayout(stableStr, textPaint, countOldWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
                        countAnimationInLayout = new StaticLayout(newSpannableStr, textPaint, countOldWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
                    } else {
                        countOldLayout = countLayout;
                    }
                }
                countWidthOld = countWidth;
                countAnimationIncrement = count > currentCount;
            }
            if (count > 0) {
                countWidth = Math.max(AndroidUtilities.dp(12), (int) Math.ceil(textPaint.measureText(newStr.toString())));
                countLayout = new StaticLayout(newStr, textPaint, countWidth, Layout.Alignment.ALIGN_CENTER, 1.0f, 0.0f, false);
                countLayoutWidth = countLayout.getLineCount() >= 1 ? countLayout.getLineWidth(0) : 0;
            }

            currentCount = count;
            currentText = newStr;
            countAnimator.start();
            NimarkoUiAnimationClock.track(countAnimator, animationResumeGate);
            if (parent != null) {
                parent.invalidate();
            }
        }

        public int getCurrentWidth() {
            return (int) Math.ceil(snapshot != null && snapshot.hasSnapshot() ? Math.max(countLayoutWidth, snapshotTextWidth) : countLayoutWidth);
        }

        private void finishAnimation() {
            windowPausedAnimator = null;
            ValueAnimator previous = countAnimator;
            countAnimator = null;
            if (previous != null) previous.cancel();
            countChangeProgress = 1f;
            animationType = -1;
            countOldLayout = null;
            countAnimationStableLayout = null;
            countAnimationInLayout = null;
            if (parent != null) {
                if (currentCount == 0 && updateVisibility) parent.setVisibility(View.GONE);
                parent.invalidate();
            }
        }

        private void applyPending(boolean animated) {
            if (pendingText == null) return;
            CharSequence latest = pendingText;
            pendingText = null;
            CounterSnapshot retained = snapshot;
            snapshot = null;
            setText(latest, animated, pendingCount, pendingIsText);
            snapshot = retained;
            if (snapshot != null && snapshot.hasSnapshot()) {
                if (parent != null) parent.requestLayout();
                if (currentCount == 0 && updateVisibility && parent != null) parent.setVisibility(View.VISIBLE);
                snapshot.start();
            }
        }

        private String getStringOfCCount(int count) {
            if (shortFormat) {
                return AndroidUtilities.formatWholeNumber(count, 0);
            }
            return String.valueOf(count);
        }

        public void draw(Canvas canvas) {
            if (canvas.isHardwareAccelerated()) NimarkoUiAnimationClock.resumeOwned(countAnimator);
            if (canvas.isHardwareAccelerated() && (windowPausedAnimator != null || (snapshot != null && snapshot.isWindowPaused()))
                    && !NimarkoUiAnimationClock.isPaused()
                    && parent != null && parent.isAttachedToWindow() && parent.getWindowVisibility() == View.VISIBLE) resumeCount.run();
            if (pendingText != null && canvas.isHardwareAccelerated() && !NimarkoUiAnimationClock.isPaused()
                    && parent != null && parent.isAttachedToWindow()
                    && parent.getWindowVisibility() == View.VISIBLE) applyPending(countAnimator == null && (snapshot == null || !snapshot.hasSnapshot()));
            if (snapshot != null) {
                snapshot.setSize(width, lastH);
                snapshot.draw(canvas, this::drawContent);
            }
            else drawContent(canvas);
            if (canvas.isHardwareAccelerated() && parent != null && parent.isAttachedToWindow()
                    && parent.getWindowVisibility() == View.VISIBLE && !NimarkoUiAnimationClock.isPaused()) {
                presented = true;
                presentedCountProgress = countChangeProgress;
                if (snapshot != null) snapshot.presented();
            }
        }

        private void drawContent(Canvas canvas) {
            if (currentCount == 0 && countChangeProgress == 1f) return;
            if (type != TYPE_CHAT_PULLING_DOWN && type != TYPE_CHAT_REACTIONS) {
                int textColor = getThemedColor(textColorKey);
                int circleColor = getThemedColor(circleColorKey);
                if (this.textColor != textColor) {
                    this.textColor = textColor;
                    textPaint.setColor(textColor);
                }
                if (circlePaint != null && this.circleColor != circleColor) {
                    this.circleColor = circleColor;
                    circlePaint.setColor(circleColor);
                }
            }
            if (countChangeProgress != 1f) {
                if (animationType == ANIMATION_TYPE_IN || animationType == ANIMATION_TYPE_OUT) {
                    updateX(countWidth);
                    float cx = countLeft + countWidth / 2f;
                    float cy = lastH / 2f;
                    canvas.save();
                    float progress = animationType == ANIMATION_TYPE_IN ? countChangeProgress : (1f - countChangeProgress);
                    canvas.scale(progress, progress, cx, cy);
                    drawInternal(canvas);
                    canvas.restore();
                } else {
                    float progressHalf = countChangeProgress * 2;
                    if (progressHalf > 1f) {
                        progressHalf = 1f;
                    }

                    float countTop = (lastH - AndroidUtilities.dp(radius * 2)) / 2f;
                    float countWidth;
                    if (this.countWidth == this.countWidthOld) {
                        countWidth = this.countWidth;
                    } else {
                        countWidth = this.countWidth * progressHalf + this.countWidthOld * (1f - progressHalf);
                    }
                    updateX(countWidth);

                    float scale = 1f;
                    if (countAnimationIncrement && type != TYPE_CHAT_REACTIONS) {
                        if (countChangeProgress <= 0.5f) {
                            scale += 0.1f * CubicBezierInterpolator.EASE_OUT.getInterpolation(countChangeProgress * 2);
                        } else {
                            scale += 0.1f * CubicBezierInterpolator.EASE_IN.getInterpolation((1f - (countChangeProgress - 0.5f) * 2));
                        }
                    }

                    rectF.set(x, countTop, x + countWidth + AndroidUtilities.dp(radius - 0.5f), countTop + AndroidUtilities.dp(radius * 2));
                    canvas.save();
                    canvas.scale(scale, scale, rectF.centerX(), rectF.centerY());
                    boolean needRestore = false;
                    if (circleScale != 1f) {
                        needRestore = true;
                        canvas.save();
                        canvas.scale(circleScale, circleScale, rectF.centerX(), rectF.centerY());
                    }
                    if (drawBackground && circlePaint != null) {
                        canvas.drawRoundRect(rectF, radius * AndroidUtilities.density, radius * AndroidUtilities.density, circlePaint);
                        if (addServiceGradient && Theme.hasGradientService()) {
                            canvas.drawRoundRect(rectF, radius * AndroidUtilities.density, radius * AndroidUtilities.density, Theme.chat_actionBackgroundGradientDarkenPaint);
                        }
                    }
                    if (needRestore) {
                        canvas.restore();
                    }
                    canvas.clipRect(rectF);

                    boolean increment = reverseAnimation != countAnimationIncrement;
                    if (countAnimationInLayout != null) {
                        canvas.save();
                        canvas.translate(countLeft + getCounterTextOffset(countAnimationInLayout), countTop + AndroidUtilities.dp(4) + (increment ? AndroidUtilities.dp(13) : -AndroidUtilities.dp(13)) * (1f - progressHalf));
                        textPaint.setAlpha((int) (255 * progressHalf));
                        countAnimationInLayout.draw(canvas);
                        canvas.restore();
                    } else if (countLayout != null) {
                        canvas.save();
                        canvas.translate(countLeft + getCounterTextOffset(countLayout), countTop + AndroidUtilities.dp(4) + (increment ? AndroidUtilities.dp(13) : -AndroidUtilities.dp(13)) * (1f - progressHalf));
                        textPaint.setAlpha((int) (255 * progressHalf));
                        countLayout.draw(canvas);
                        canvas.restore();
                    }

                    if (countOldLayout != null) {
                        canvas.save();
                        canvas.translate(countLeft + getCounterTextOffset(countOldLayout), countTop + AndroidUtilities.dp(4) + (increment ? -AndroidUtilities.dp(13) : AndroidUtilities.dp(13)) * (progressHalf));
                        textPaint.setAlpha((int) (255 * (1f - progressHalf)));
                        countOldLayout.draw(canvas);
                        canvas.restore();
                    }

                    if (countAnimationStableLayout != null) {
                        canvas.save();
                        canvas.translate(countLeft + getCounterTextOffset(countAnimationStableLayout), countTop + AndroidUtilities.dp(4));
                        textPaint.setAlpha(255);
                        countAnimationStableLayout.draw(canvas);
                        canvas.restore();
                    }
                    textPaint.setAlpha(255);
                    canvas.restore();
                }
            } else {
                drawInternal(canvas);
            }
        }

        private float getCounterTextOffset(StaticLayout layout) {
            return type == TYPE_CHAT_REACTIONS && layout != null && layout.getLineCount() > 0
                    ? -layout.getLineLeft(0) : 0f;
        }

        public void updateBackgroundRect() {
            if (countChangeProgress != 1f) {
                if (animationType == ANIMATION_TYPE_IN || animationType == ANIMATION_TYPE_OUT) {
                    updateX(countWidth);
                    float countTop = (lastH - AndroidUtilities.dp(radius * 2)) / 2f;
                    rectF.set(x, countTop, x + countWidth + AndroidUtilities.dp(11), countTop + AndroidUtilities.dp(23));
                } else {
                    float progressHalf = countChangeProgress * 2;
                    if (progressHalf > 1f) {
                        progressHalf = 1f;
                    }
                    float countTop = (lastH - AndroidUtilities.dp(radius * 2)) / 2f;
                    float countWidth;
                    if (this.countWidth == this.countWidthOld) {
                        countWidth = this.countWidth;
                    } else {
                        countWidth = this.countWidth * progressHalf + this.countWidthOld * (1f - progressHalf);
                    }
                    updateX(countWidth);
                    rectF.set(x, countTop, x + countWidth + AndroidUtilities.dp(11), countTop + AndroidUtilities.dp(23));
                }
            } else {
                updateX(countWidth);
                float countTop = (lastH - AndroidUtilities.dp(radius * 2)) / 2f;
                rectF.set(x, countTop, x + countWidth + AndroidUtilities.dp(11), countTop + AndroidUtilities.dp(23));
            }
        }

        private void updateX(float countWidth) {
            float padding = drawBackground ? AndroidUtilities.dp(5.5f) : 0f;
            if (gravity == Gravity.RIGHT) {
                countLeft = width - padding;
                if (horizontalPadding != 0) {
                    countLeft -= Math.max(horizontalPadding + countWidth / 2f, countWidth);
                } else {
                    countLeft -= countWidth;
                }
            } else if (gravity == Gravity.LEFT) {
                countLeft = padding;
            } else {
                countLeft = (int) ((width - countWidth) / 2f);
            }
            x = countLeft - padding;
        }

        public float getCenterX() {
            updateX(countWidth);
            return countLeft + countWidth / 2f;
        }

        public void setType(int type) {
            this.type = type;
        }

        public void setParent(View parent) {
            this.parent = parent;
        }

        protected int getThemedColor(int key) {
            return Theme.getColor(key, resourcesProvider);
        }

        public int getWidth() {
            return currentCount == 0 ? 0 : (countWidth + AndroidUtilities.dp(radius - 0.5f));
        }
    }

    public float getEnterProgress() {
        if (counterDrawable.countChangeProgress != 1f && (counterDrawable.animationType == CounterDrawable.ANIMATION_TYPE_IN || counterDrawable.animationType == CounterDrawable.ANIMATION_TYPE_OUT)) {
            if (counterDrawable.animationType == CounterDrawable.ANIMATION_TYPE_IN) {
                return counterDrawable.countChangeProgress;
            } else {
                return 1f - counterDrawable.countChangeProgress;
            }
        } else {
            return counterDrawable.currentCount == 0 ? 0 : 1f;
        }
    }

    public boolean isInOutAnimation() {
        return counterDrawable.animationType == CounterDrawable.ANIMATION_TYPE_IN || counterDrawable.animationType == CounterDrawable.ANIMATION_TYPE_OUT;
    }

}
