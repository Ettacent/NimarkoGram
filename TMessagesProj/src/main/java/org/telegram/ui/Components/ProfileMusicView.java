/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.ActionBar.Theme.multAlpha;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import android.view.ViewTreeObserver;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import androidx.annotation.Nullable;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

import app.nimarkogram.messenger.utils.UiAnimationClock;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public class ProfileMusicView extends View {

    private final Theme.ResourcesProvider resourcesProvider;
    private Text author, title;
    private ProfileMusicView trailingView;
    private ProfileMusicView leadingView;

    private CharSequence authorText, titleText;
    private Text outgoingAuthor, outgoingTitle;
    private float textProgress = 1f, outgoingTextAlpha;
    private long textStartedAt;
    private boolean contentPresented;
    private final Rect profileContentVisibleRect = new Rect();
    private int profileContentCaptureDepth;
    private float contentDrawWidth;
    private ProfileRowAnimation profileRowAnimation;
    public void setTrailingView(ProfileMusicView view) {
        if (trailingView == view) return;
        resetProfileAnimations();
        if (trailingView != null) trailingView.leadingView = null;
        trailingView = view;
        if (view != null) {
            view.setProfileAnimationContext(null, null);
        view.leadingView = this;
        }
        invalidateContentWidth();
    }

    public void setProfileAnimationContext(LongSupplier identity, BooleanSupplier visible) {
        if (leadingView != null) {
            leadingView.setProfileAnimationContext(identity, visible);
            return;
        }
        if (profileRowAnimation != null) {
            profileRowAnimation.stopObserving();
            profileRowAnimation.reset();
        }
        profileRowAnimation = identity == null || visible == null ? null : new ProfileRowAnimation(identity, visible);
        if (profileRowAnimation != null) profileRowAnimation.observe();
        invalidateContentWidth();
    }

    public void resetProfileAnimations() {
        ProfileRowAnimation row = getProfileRowAnimation();
        if (row != null) row.reset();
    }

    public void beginProfileContentCapture() {
        if (leadingView != null) leadingView.beginProfileContentCapture();
        else profileContentCaptureDepth++;
    }

    public void endProfileContentCapture() {
        if (leadingView != null) leadingView.endProfileContentCapture();
        else if (profileContentCaptureDepth > 0) profileContentCaptureDepth--;
    }

    private boolean isProfileContentCapture() {
        return (leadingView == null ? profileContentCaptureDepth : leadingView.profileContentCaptureDepth) > 0;
    }

    private ProfileRowAnimation getProfileRowAnimation() {
        return leadingView == null ? profileRowAnimation : leadingView.profileRowAnimation;
    }

    protected final boolean isProfileAnimationEnabled() {
        return getProfileRowAnimation() != null;
    }

    protected final boolean canAnimateProfileContent() {
        ProfileRowAnimation row = getProfileRowAnimation();
        return row != null && row.checkContext() && isProfilePillVisible() && (contentPresented || outgoingAuthor != null);
    }

    protected final void clearProfileContentPresentation() {
        contentPresented = false;
    }

    private void markProfileContentPresented(Canvas canvas) {
        ProfileRowAnimation row = getProfileRowAnimation();
        if (!isProfileContentCapture() && canvas.isHardwareAccelerated() && isHardwareAccelerated()
                && hasWindowFocus() && isProfilePillVisible() && currentHeight >= dp(21)
                && getWidth() > 0 && getHeight() > 0 && row != null && row.identityKnown
                && row.lastIdentity == row.identity.getAsLong() && row.epoch == UiAnimationClock.epoch()
                && row.isAllowed() && getLocalVisibleRect(profileContentVisibleRect) && isProfileTextVisible(canvas, rect)) {
            contentPresented = true;
        }
    }

    protected boolean isProfileTextVisible(Canvas canvas, RectF bounds) {
        float left = bounds.left + dp(8) + dp(16.6f);
        float right = Math.min(bounds.right - dp(8), left + author.getWidth() + title.getWidth());
        return textProgress > 0f && left < right
                && isProfileTextAreaVisible(canvas, left, bounds.top, right, bounds.bottom);
    }

    protected final boolean isProfileTextAreaVisible(Canvas canvas, float left, float top, float right, float bottom) {
        return left < profileContentVisibleRect.right && right > profileContentVisibleRect.left
                && top < profileContentVisibleRect.bottom && bottom > profileContentVisibleRect.top
                && !canvas.quickReject(left, top, right, bottom, Canvas.EdgeType.AA);
    }

    private boolean isProfilePillVisible() {
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE || currentHeight <= 0) return false;
        for (View view = this; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.getAlpha() <= 0f) return false;
        }
        return true;
    }

    protected void finishProfileContentAnimation() {
        outgoingAuthor = outgoingTitle = null;
        textProgress = 1f;
        outgoingTextAlpha = 0f;
        contentPresented = false;
    }

    private void sampleText(long now) {
        if (outgoingAuthor == null) return;
        textProgress = replacementProgress(now, textStartedAt);
        if (textProgress >= 1f) {
            outgoingAuthor = outgoingTitle = null;
            outgoingTextAlpha = 0f;
        }
    }

    private static float replacementProgress(long now, long startedAt) {
        return CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Math.max(0f, Math.min(1f, (now - startedAt) / 240f)));
    }

    private class ProfileRowAnimation implements ViewTreeObserver.OnPreDrawListener {
        final LongSupplier identity;
        final BooleanSupplier visible;
        final PillWidths widths = new PillWidths();
        ViewTreeObserver observer;
        boolean dirty = true, identityKnown, contextAllowed, wasLeadingVisible, wasTrailingVisible;
        long lastIdentity, widthStartedAt;
        int epoch = UiAnimationClock.epoch();
        float viewportWidth;

        ProfileRowAnimation(LongSupplier identity, BooleanSupplier visible) {
            this.identity = identity;
            this.visible = visible;
        }

        void observe() {
            if (observer == null && isAttachedToWindow()) {
                observer = getViewTreeObserver();
                observer.addOnPreDrawListener(this);
            }
        }

        void stopObserving() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }

        void reset() {
            finishProfileContentAnimation();
            snapVerticalPosition();
            if (trailingView != null) {
                trailingView.finishProfileContentAnimation();
                trailingView.snapVerticalPosition();
            }
            widths.initialized = false;
            widths.animating = false;
            dirty = true;
            invalidateContentWidth();
        }

        boolean checkContext() {
            long id = identity.getAsLong();
            int currentEpoch = UiAnimationClock.epoch();
            boolean changed = !identityKnown || id != lastIdentity || epoch != currentEpoch;
            identityKnown = true;
            lastIdentity = id;
            epoch = currentEpoch;
            boolean allowed = isAllowed();
            if (changed || !allowed && (contextAllowed || contentPresented || outgoingAuthor != null || widths.animating
                    || trailingView != null && (trailingView.contentPresented || trailingView.outgoingAuthor != null))) reset();
            contextAllowed = allowed;
            return allowed;
        }

        boolean isAllowed() {
            return !UiAnimationClock.isPaused() && SharedConfig.animationsEnabled()
                    && (Build.VERSION.SDK_INT >= 26 ? ValueAnimator.areAnimatorsEnabled() : AndroidUtilities.getAnimatorDurationScale() > 0f)
                    && visible.getAsBoolean();
        }

        @Override
        public boolean onPreDraw() {
            boolean allowed = checkContext();
            long now = UiAnimationClock.now();
            boolean changed = dirty || widths.animating || outgoingAuthor != null
                    || trailingView != null && trailingView.outgoingAuthor != null;
            if (!allowed) {
                if (animateGeometry) snapVerticalPosition();
                if (trailingView != null && trailingView.animateGeometry) trailingView.snapVerticalPosition();
            }
            updateVerticalPosition();
            if (trailingView != null) trailingView.updateVerticalPosition();
            boolean leadingVisible = isProfilePillVisible();
            boolean trailingVisible = trailingView != null && trailingView.isProfilePillVisible();
            if (!wasLeadingVisible && !wasTrailingVisible) widths.initialized = false;
            if (leadingVisible != wasLeadingVisible || trailingVisible != wasTrailingVisible) dirty = true;
            wasLeadingVisible = leadingVisible;
            wasTrailingVisible = trailingVisible;
            if (!leadingVisible) finishProfileContentAnimation();
            if (trailingView != null && !trailingVisible) trailingView.finishProfileContentAnimation();
            sampleText(now);
            if (trailingView != null) trailingView.sampleText(now);
            widths.sample(replacementProgress(now, widthStartedAt));
            float newViewportWidth = leadingVisible ? getWidth() : trailingVisible ? trailingView.getWidth() : getWidth();
            if (viewportWidth != newViewportWidth) dirty = true;
            viewportWidth = newViewportWidth;
            changed |= dirty;
            if (dirty) {
                float max = Math.max(0f, viewportWidth - dp(24));
                float gap = leadingVisible && trailingVisible ? Math.min(dp(6), max) : 0f;
                float trailingWidth = trailingVisible ? trailingView.getBoundedPillWidth(leadingVisible ? (max - gap) / 2f : max) : 0f;
                float leadingWidth = leadingVisible ? getBoundedPillWidth(Math.max(0f, max - trailingWidth - gap)) : 0f;
                if (!leadingVisible && !trailingVisible) widths.initialized = false;
                if (widths.retarget(leadingWidth, trailingWidth, gap, max, allowed && (leadingVisible || trailingVisible))) {
                    widthStartedAt = now;
                }
                dirty = false;
            }
            if (changed) {
        invalidate();
                if (trailingView != null) trailingView.invalidate();
            }
            if (widths.animating || outgoingAuthor != null || trailingView != null && trailingView.outgoingAuthor != null) {
                postInvalidateOnAnimation();
                if (trailingView != null) trailingView.postInvalidateOnAnimation();
            }
            return true;
        }
    }

    private static class PillWidths {
        float leading, trailing, gap, fromLeading, fromTrailing, fromGap, toLeading, toTrailing, toGap;
        boolean initialized, animating;

        void sample(float progress) {
            if (!animating) return;
            leading = lerp(fromLeading, toLeading, progress);
            trailing = lerp(fromTrailing, toTrailing, progress);
            gap = lerp(fromGap, toGap, progress);
            if (progress >= 1f) animating = false;
        }

        boolean retarget(float newLeading, float newTrailing, float newGap, float max, boolean animated) {
            float total = leading + trailing + gap;
            boolean bounded = total > max && total > 0f;
            if (bounded) {
                float scale = max / total;
                leading *= scale;
                trailing *= scale;
                gap *= scale;
            }
            if (!bounded && initialized && toLeading == newLeading && toTrailing == newTrailing && toGap == newGap && animated) return false;
            fromLeading = leading;
            fromTrailing = trailing;
            fromGap = gap;
            toLeading = newLeading;
            toTrailing = newTrailing;
            toGap = newGap;
            animating = animated && initialized && (leading != toLeading || trailing != toTrailing || gap != toGap);
            initialized = true;
            if (!animating) {
                leading = toLeading;
                trailing = toTrailing;
                gap = toGap;
            }
            return true;
        }

        float left(float viewportWidth, boolean isTrailing) {
            return (viewportWidth - leading - trailing - gap) / 2f + (isTrailing ? leading + gap : 0f);
        }
    }
    private final Paint arrowPaint = new Paint();
    private final Path arrowPath = new Path();
    private final CompactChevronRenderer arrowRenderer = new CompactChevronRenderer();
    private final Drawable icon;

    private final RectF rect = new RectF();
    public void getButtonBoundsOnScreen(RectF bounds) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        bounds.set(rect);
        bounds.offset(location[0], location[1]);
    }
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();

    private final ButtonBounce bounce = new ButtonBounce(this);
    public ProfileMusicView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        icon = context.getResources().getDrawable(R.drawable.files_music).mutate();
        bannerBackgroundPaint.setColor(0x40000000);

        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
        arrowPath.moveTo(0, -dpf2(3.33f));
        arrowPath.lineTo(dpf2(3.16f), 0);
        arrowPath.lineTo(0, dpf2(3.33f));

        setColor(null);
        updateVisualColors();
        setText("Author", " - Title");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.EXACTLY
            ),
            MeasureSpec.makeMeasureSpec(
                dp(37),
                MeasureSpec.EXACTLY
            )
        );
    }

    protected int textColor = Color.WHITE;
    protected boolean hasProfileColor;
    private float parentExpanded;
    private int normalTextColor = Color.WHITE;
    private int appliedIconColor;
    private float bannerProgress;

    private int buttonColor;
    private boolean hasColorById;
    private RadialGradient radialGradient;
    private float radialGradientWidth;
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    public void setColor(MessagesController.PeerColor peerColor) {

        if (!app.nimarkogram.messenger.AppConfig.profileBackgroundColor) peerColor = null;
        hasProfileColor = peerColor != null;
        if (trailingView != null) trailingView.setColor(peerColor);
        invalidate();
    }

    public void setButtonColor(int color, boolean hasColorById) {
        if (trailingView != null) trailingView.setButtonColor(color, hasColorById);
        if (buttonColor == color && this.hasColorById == hasColorById) return;
        buttonColor = color;
        this.hasColorById = hasColorById;
        radialGradient = null;
        backgroundPaint.setColor(color);
        checkTextColor();
    }

    private boolean isButtonColorLight() {
        return AndroidUtilities.computePerceivedBrightness(buttonColor) > 0.72f;
    }

    private void checkTextColor() {
        final boolean useBlackText = parentExpanded < 0.8f
                && isButtonColorLight();
        normalTextColor = useBlackText ? Color.BLACK : Color.WHITE;
        updateVisualColors();
    }

    private void updateVisualColors() {
        textColor = ColorUtils.blendARGB(normalTextColor, Color.WHITE, bannerProgress);
        if (appliedIconColor != textColor) {
            appliedIconColor = textColor;
            icon.setColorFilter(textColor, PorterDuff.Mode.SRC_IN);
        }
        arrowPaint.setColor(Theme.multAlpha(textColor, 0.85f));
        invalidate();
    }

    public void setBannerProgress(float progress) {
        progress = Utilities.clamp01(progress);
        if (trailingView != null) trailingView.setBannerProgress(progress);
        if (Math.abs(bannerProgress - progress) > 0.0001f) {
            bannerProgress = progress;
            updateVisualColors();
        }
    }

    public void setParentExpanded(float expanded) {
        if (trailingView != null) trailingView.setParentExpanded(expanded);
        if (parentExpanded != expanded) {
            parentExpanded = expanded;
            checkTextColor();
            invalidate();
        }
    }

    public void setMusicDocument(TLRPC.Document document) {
        CharSequence author = getAuthor(document);
        CharSequence title = getTitle(document);
        if (TextUtils.isEmpty(author)) {
            if (TextUtils.isEmpty(title)) {
                author = getString(R.string.AudioUnknownArtist);
                title = " - " + getString(R.string.AudioUnknownTitle);
            } else {
                author = "";
            }
        } else if (!TextUtils.isEmpty(title)) {
            title = " - " + title;
        } else {
            title = "";
        }
        setText(author, title);
    }

    public static CharSequence getTitle(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                String title = attribute.title;
                if (title == null || title.length() == 0) {
                    title = FileLoader.getDocumentFileName(document);
                }
                return title;
            }
        }
        String fileName = FileLoader.getDocumentFileName(document);
        if (!TextUtils.isEmpty(fileName)) {
            return fileName;
        }

        return null;
    }

    public static CharSequence getAuthor(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        for (int a = 0; a < document.attributes.size(); a++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(a);
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                if (!attribute.voice) {
                    return attribute.performer;
                }
            }
        }
        return null;
    }

    public void setText(CharSequence author, CharSequence title) {
        author = author == null ? "" : author;
        title = title == null ? "" : title;
        if (TextUtils.equals(authorText, author) && TextUtils.equals(titleText, title)) return;
        boolean animated = canAnimateProfileContent();
        long now = UiAnimationClock.now();
        float oldAlpha = outgoingTextAlpha * (1f - textProgress);
        if (animated && outgoingAuthor != null && (!contentPresented || oldAlpha > textProgress)) {
            outgoingTextAlpha = oldAlpha;
        } else {
            outgoingAuthor = animated ? this.author : null;
            outgoingTitle = animated ? this.title : null;
            outgoingTextAlpha = animated ? textProgress : 0f;
        }
        textProgress = animated ? 0f : 1f;
        textStartedAt = now;
        authorText = author.toString();
        titleText = title.toString();
        this.author = Text.ui(author, 11, AndroidUtilities.bold());
        this.title = Text.ui(title, 11);

        clearProfileContentPresentation();
        setContentDescription(getString(R.string.AccDescrProfileMusic) + " " + author + " — " + title);
        invalidateContentWidth();
    }

    @Nullable
    private BlurredBackgroundDrawable glass;
    private float glassRadius;

    public void setGlassFactory(@Nullable BlurredBackgroundDrawableViewFactory factory) {
        if (trailingView != null) trailingView.setGlassFactory(factory);
        glass = factory != null ? factory.create(this, ProfileButtonsGlass.NO_COLORS) : null;
        glassRadius = 0;
        invalidate();
    }

    private float currentHeight;

    private float targetPositionY;
    private float targetHeight;
    private long lastPositionUpdate;
    private boolean animateGeometry;
    private final AnimatedFloat animatedPositionY = new AnimatedFloat(this, 140, CubicBezierInterpolator.EASE_OUT_QUINT);
    private final AnimatedFloat animatedHeight = new AnimatedFloat(this, 140, CubicBezierInterpolator.EASE_OUT_QUINT);
    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (trailingView != null) trailingView.setAlpha(alpha);
        if (alpha <= 0f) resetProfileAnimations();
    }

    protected boolean isOnProfilePhoto() {
        return parentExpanded >= 0.8f;
    }
    public void updatePosition(float y, float newHeight) {
        if (trailingView != null) trailingView.updatePosition(y, newHeight);
        long now = android.os.SystemClock.uptimeMillis();
        boolean discontinuity = lastPositionUpdate != 0L
                && now - lastPositionUpdate > 90L
                && (Math.abs(targetPositionY - y) > dp(3) || Math.abs(targetHeight - newHeight) > dp(3));
        animateGeometry = discontinuity || animateGeometry && targetPositionY == y && targetHeight == newHeight;
        targetPositionY = y;
        targetHeight = newHeight;
        lastPositionUpdate = now;
        if (!animateGeometry) {
            animatedPositionY.force(y);
            animatedHeight.force(newHeight);
            currentHeight = newHeight;
            setTranslationY(y - dp(12));
        }
        invalidateContentWidth();
    }

    private void snapVerticalPosition() {
        animateGeometry = false;
        animatedPositionY.force(targetPositionY);
        animatedHeight.force(targetHeight);
        currentHeight = targetHeight;
        setTranslationY(targetPositionY - dp(12));
    }

    private void updateVerticalPosition() {
        final float positionY = animateGeometry ? animatedPositionY.set(targetPositionY) : targetPositionY;
        currentHeight = animateGeometry ? animatedHeight.set(targetHeight) : targetHeight;
        setTranslationY(positionY - dp(12));
        if (animateGeometry && Math.abs(currentHeight - targetHeight) < 0.1f
                && Math.abs(positionY - targetPositionY) < 0.1f) animateGeometry = false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ProfileRowAnimation row = getProfileRowAnimation();
        if (row != null) {
            row.dirty = true;
            row.observe();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ProfileRowAnimation row = getProfileRowAnimation();
        if (row != null) {
            row.reset();
            if (leadingView == null) row.stopObserving();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (getProfileRowAnimation() == null) return;
        if (visibility != VISIBLE) finishProfileContentAnimation();
        invalidateContentWidth();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) resetProfileAnimations();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidateContentWidth();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final float alpha = Utilities.clamp01((currentHeight) / dp(21));
        if (alpha <= 0) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            bounce.setPressed(rect.contains(event.getX(), event.getY()));
        } else if (event.getAction() == MotionEvent.ACTION_MOVE && bounce.isPressed()) {
            if (!rect.contains(event.getX(), event.getY())) {
                bounce.setPressed(false);
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            bounce.setPressed(false);
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (bounce.isPressed()) {
                performClick();
            }
            bounce.setPressed(false);
        }
        return bounce.isPressed();
    }

    protected float getContentWidth(float maxWidth) {
        return fitMusicText(author, title, maxWidth);
    }

    private float fitMusicText(Text author, Text title, float maxWidth) {
        final float textWidth = Math.max(0, maxWidth - dp(16) - dp(16.6f) - dp(8));
        author.ellipsize(textWidth / 2f);
        title.ellipsize(Math.max(0, textWidth - author.getWidth()));
        return dp(16.6f) + author.getWidth() + title.getWidth() + dp(8);
    }

    protected void drawContent(Canvas canvas, float alpha) {
        final float cy = getHeight() / 2f;

        final int iconSz = dp(13);
        icon.setBounds(0, (int) cy - iconSz / 2, iconSz, (int) cy + iconSz / 2);
        icon.draw(canvas);

        canvas.translate(dp(16.6f), 0);
        if (outgoingAuthor != null) {
            fitMusicText(outgoingAuthor, outgoingTitle, contentDrawWidth + dp(16));
            canvas.save();
            outgoingAuthor.draw(canvas, 0, cy, textColor, alpha * outgoingTextAlpha * (1f - textProgress));
            canvas.translate(outgoingAuthor.getWidth(), 0);
            outgoingTitle.draw(canvas, 0, cy, textColor, 0.85f * alpha * outgoingTextAlpha * (1f - textProgress));
            canvas.restore();
        }
        canvas.save();
        this.author.draw(canvas, 0, cy, textColor, alpha * textProgress);
        canvas.translate(this.author.getWidth(), 0);
        this.title.draw(canvas, 0, cy, textColor, 0.85f * alpha * textProgress);
        canvas.restore();
        canvas.translate(isProfileAnimationEnabled() ? Math.max(0f, contentDrawWidth - dp(16.6f) - dp(8))
                : this.author.getWidth() + this.title.getWidth(), 0);

        arrowPaint.setStrokeWidth(dpf2(1.16f));
        canvas.translate(dpf2(4.8f), cy);
        if (!arrowRenderer.draw(canvas, arrowPath, arrowPaint, CompactChevronRenderer.RIGHT)) {
        canvas.drawPath(arrowPath, arrowPaint);

        }
    }

    private boolean hasVisibleCompanion() {
        ProfileMusicView companion = trailingView != null ? trailingView : leadingView;
        return companion != null && companion.getVisibility() == VISIBLE;
    }

    protected void invalidateContentWidth() {
        ProfileRowAnimation row = getProfileRowAnimation();
        if (row != null) row.dirty = true;
        invalidate();
        if (leadingView != null) leadingView.invalidate();
        if (trailingView != null) trailingView.invalidate();
    }

    private float getPillWidth(float maxWidth) {
        return dp(16) + getContentWidth(maxWidth);
    }

    private float getBoundedPillWidth(float maxWidth) {
        return Math.max(0f, Math.min(maxWidth, dp(16) + getTargetContentWidth(maxWidth)));
    }

    protected float getTargetContentWidth(float maxWidth) {
        return getContentWidth(maxWidth);
    }
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (this.author == null || this.title == null) return;

        final boolean capturing = isProfileContentCapture() || !canvas.isHardwareAccelerated();
        final float oldLeft = rect.left, oldTop = rect.top, oldRight = rect.right, oldBottom = rect.bottom;
        final float oldContentDrawWidth = contentDrawWidth;
        try {
        ProfileRowAnimation row = getProfileRowAnimation();
        if (row == null && !capturing) updateVerticalPosition();
        final float alpha = Utilities.clamp01(currentHeight / dp(21));
        final float bannerT = Utilities.clamp01(bannerProgress);
        final float profileT = 1f - bannerT;
        final float scale = bounce.getScale(0.02f);
        if (alpha <= 0) {
            if (glass != null) {
                glass.setAlpha(0);
            }
            return;
        }

        final int padding = dp(12);
        final int maxWidth = getWidth() - padding * 2;

        float containerWidth;
        float left;
        if (row != null && row.widths.initialized) {
            containerWidth = leadingView != null ? row.widths.trailing : row.widths.leading;
            left = row.widths.left(row.viewportWidth, leadingView != null);
            getContentWidth(containerWidth);
        } else if (hasVisibleCompanion()) {
            ProfileMusicView leading = leadingView != null ? leadingView : this;
            ProfileMusicView trailing = trailingView != null ? trailingView : this;
            float trailingWidth = trailing.getPillWidth(maxWidth / 2f);
            float leadingWidth = leading.getPillWidth(Math.max(0, maxWidth - trailingWidth - dp(6)));
            left = (getWidth() - leadingWidth - dp(6) - trailingWidth) / 2f;
            containerWidth = leadingView != null ? trailingWidth : leadingWidth;
            if (leadingView != null) left += leadingWidth + dp(6);
        } else {
            containerWidth = getPillWidth(maxWidth);
            left = (getWidth() - containerWidth) / 2f;
        }

        contentDrawWidth = Math.max(0f, containerWidth - dp(16));
        rect.set(left, dp(10), left + containerWidth, dp(10) + dp(17) * alpha);
        canvas.save();
        canvas.scale(scale, scale, rect.centerX(), getHeight() / 2f);

        if (glass != null) {
            final float radius = rect.height() / 2f;
            if (glassRadius != radius) {
                glassRadius = radius;
                glass.setRadius(radius);
            }
            glass.setBounds((int) rect.left, (int) rect.top, (int) Math.ceil(rect.right), (int) Math.ceil(rect.bottom));
            glass.setAlpha((int) (0xFF * alpha));
            glass.draw(canvas);
        } else {
        final boolean withShadows = SharedConfig.shadowsInSections && isButtonColorLight() && parentExpanded < 0.5f;
        final float bgAlpha = hasColorById ? 0.1f : 1f;
        if (withShadows && SharedConfig.shadowsInSections) {
            backgroundPaint.setShadowLayer(dpf2(2), 0, dpf2(0.33f), multAlpha(0x0a000000, alpha * profileT * bgAlpha));
            strokePaint.setShadowLayer(dpf2(0.33f), 0, 0, multAlpha(0x0c000000, alpha * profileT * bgAlpha));
            strokePaint.setColor(0);
        } else {
            backgroundPaint.setShadowLayer(0, 0, 0, 0);
        }
        int wasAlpha = backgroundPaint.getAlpha();
        backgroundPaint.setAlpha((int) (wasAlpha * alpha * profileT * bgAlpha));
        if (withShadows && SharedConfig.shadowsInSections && profileT > 0f) {
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, strokePaint);
        }
        if (backgroundPaint.getAlpha() > 0) {
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, backgroundPaint);
        }
        backgroundPaint.setAlpha(wasAlpha);
        if (profileT > 0f && hasColorById && buttonColor != 0 && rect.width() > 0) {
            if (radialGradient == null || radialGradientWidth != rect.width()) {
                radialGradientWidth = rect.width();
                radialGradient = new RadialGradient(
                    rect.width() / 2f, dp(17) / 2f, rect.width() * 0.65f,
                    Theme.multAlpha(buttonColor, 0.8f), buttonColor,
                    Shader.TileMode.CLAMP
                );
                shaderPaint.setShader(radialGradient);
            }
            matrix.setTranslate(rect.left, rect.top);
            radialGradient.setLocalMatrix(matrix);
            shaderPaint.setAlpha((int) (0xFF * alpha * profileT));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, shaderPaint);
        }
        }
        if (glass == null && bannerT > 0f) {
            bannerBackgroundPaint.setAlpha((int) (40f * alpha * bannerT));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, bannerBackgroundPaint);
        }

        clipPath.rewind();
        clipPath.addRoundRect(rect, rect.height() / 2f, rect.height() / 2f, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(clipPath);

        canvas.translate(rect.left + dp(8), 0);
        drawContent(canvas, alpha);
        canvas.restore();

        canvas.restore();
        if (!capturing) markProfileContentPresented(canvas);
        } finally {
            if (capturing) {
                rect.set(oldLeft, oldTop, oldRight, oldBottom);
                contentDrawWidth = oldContentDrawWidth;
            }
        }
    }
}
