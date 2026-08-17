package app.nimarkogram.messenger.infocards;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LoadingDrawable;
import org.telegram.ui.Components.ScaleStateListAnimator;

public abstract class BaseInfoCard extends FrameLayout {

    protected final Theme.ResourcesProvider resourcesProvider;

    private static final int CHIP_HEIGHT_DP = 28;
    private static final int CORNER_RADIUS_DP = 14;

    private static final long RESIZE_DURATION_MS = 350;
    private static final float RESIZE_TEXT_SCALE_POP = 0.12f; 
    private static final TimeInterpolator RESIZE_INTERPOLATOR = CubicBezierInterpolator.EASE_BOTH; 

    private final LinearLayout content;
    protected final ImageView iconView;
    protected final AnimatedTextView textView;
    private final CardBackground background;
    private boolean iconVisible = true;

    private int brandTop = 0xff2b2b2b, brandBottom = 0xff202020;
    private int colorMode = InfoCardsConfig.COLOR_MODE_CUSTOM;
    
    private boolean opaqueFlat;
    
    private boolean nextRenderInstant;
    private int lastIconRes;
    
    private int maxChipWidth;
    private int appliedTextMaxWidth;
    private CharSequence accessibilityLabel;
    private CharSequence accessibilityValue;

    private boolean loading;
    private long lastUpdateMs;

    private LoadingDrawable loadingDrawable;
    private final RectF loadingRect = new RectF();

    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            try { onUpdateData(false); } catch (Throwable ignore) {}
            scheduleNext();
        }
    };

    public BaseInfoCard(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        
        setClipChildren(false);
        setClipToPadding(false);

        background = new CardBackground(0xff2b2b2b, 0xff202020);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER); 
        
        content.setClipChildren(true);
        content.setClipToPadding(false);
        content.setBackground(background);
        
        content.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        content.setMinimumWidth(AndroidUtilities.dp(48));
        
        final float radius = AndroidUtilities.dp(CORNER_RADIUS_DP);
        content.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        content.setClipToOutline(true);
        
        content.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) v.invalidateOutline();
        });
        
        int chipGravity = Gravity.CENTER_VERTICAL
                | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        addView(content, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, CHIP_HEIGHT_DP, chipGravity));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        content.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        textView = new ChipTextView(context, true, true, true);
        textView.adaptWidth = true;
        textView.setTextSize(AndroidUtilities.dp(13));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.setTextColor(0xffffffff);
        textView.setGravity(Gravity.CENTER_VERTICAL);
        
        textView.setAnimationProperties(0f, 0, RESIZE_DURATION_MS, RESIZE_INTERPOLATOR);
        textView.setScaleProperty(RESIZE_TEXT_SCALE_POP);
        
        textView.setOnWidthUpdatedListener(this::onAnimatedTextWidthUpdated);
        content.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, CHIP_HEIGHT_DP, Gravity.CENTER_VERTICAL));

        ScaleStateListAnimator.apply(content);

        setOnClickListener(v -> onCardClicked());
        setOnLongClickListener(v -> onCardLongClicked());
    }

    @Override
    public void setPressed(boolean pressed) {
        
        if (loading) pressed = false;
        super.setPressed(pressed);
        
        if (content != null) content.setPressed(pressed);
    }

    public void setCardLayerType(int layerType) {
        if (getLayerType() != layerType) {
            setLayerType(layerType, null);
        }
    }

    public void setCardColors(int top, int bottom) {
        brandTop = top;
        brandBottom = bottom;
        applyColorMode();
    }

    public void setCardAccessibilityLabel(CharSequence label) {
        accessibilityLabel = label;
        updateAccessibilityDescription();
    }

    private void updateAccessibilityDescription() {
        if (android.text.TextUtils.isEmpty(accessibilityLabel)) {
            setContentDescription(accessibilityValue);
        } else if (android.text.TextUtils.isEmpty(accessibilityValue)) {
            setContentDescription(accessibilityLabel);
        } else {
            setContentDescription(accessibilityLabel + ": " + accessibilityValue);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Button");
        info.setClickable(true);
    }

    protected void setIcon(int resId) {
        boolean iconChanged = resId != lastIconRes;
        iconVisible = true;
        lastIconRes = resId;
        iconView.setVisibility(View.VISIBLE);
        applyMaxChipWidth();
        iconView.setImageResource(resId);
        iconView.setColorFilter(currentContentColor());
        
        if (iconChanged && getVisibility() == VISIBLE && getTranslationX() == 0f && getTranslationY() == 0f) {
            content.requestLayout();
        }
    }

    protected boolean isBranded() {
        return false;
    }

    private boolean isFlat() {
        return colorMode == InfoCardsConfig.COLOR_MODE_THEME || !isBranded();
    }

    private int currentContentColor() {
        if (isFlat()) {
            return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), 0.75f);
        }
        return 0xffffffff;
    }

    protected void applyColorMode() {
        colorMode = InfoCardsConfig.getColorMode();
        boolean flat = isFlat();
        if (flat) {
            
            int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            
            int fill = opaqueFlat
                    ? Theme.blendOver(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), Theme.multAlpha(text, 0.09f))
                    : Theme.multAlpha(text, 0.09f);
            int ovFill = fillColorOverride();
            if (ovFill != 0) fill = ovFill;
            background.setThemeMode(true);
            background.setColors(fill, fill);
        } else {
            background.setThemeMode(false);
            background.setColors(brandTop, brandBottom);
        }
        
        content.setElevation(0f);
        int fg = currentContentColor();
        int ov = contentColorOverride();
        if (ov != 0) fg = ov;
        textView.setTextColor(fg);
        if (iconVisible) iconView.setColorFilter(fg);
        content.invalidateOutline();
        content.invalidate();
    }

    public void setOpaqueFlat(boolean v) {
        if (opaqueFlat == v) return;
        opaqueFlat = v;
        applyColorMode();
    }

    protected int contentColorOverride() {
        return 0;
    }

    protected int fillColorOverride() {
        return 0;
    }

    protected void setIconVisible(boolean visible) {
        if (iconVisible == visible) return;
        iconVisible = visible;
        iconView.setVisibility(visible ? View.VISIBLE : View.GONE);
        applyMaxChipWidth();
    }

    protected void setText(CharSequence text, boolean animated) {
        accessibilityValue = text;
        updateAccessibilityDescription();
        
        if (nextRenderInstant) {
            animated = false;
            nextRenderInstant = false;
        }
        boolean changed = !android.text.TextUtils.equals(textView.getText(), text);
        if (!changed) {
            return;
        }
        
        boolean canAnimate = animated
                && isAttachedToWindow()
                && getWindowVisibility() == View.VISIBLE;

        boolean visibleResting = getVisibility() == VISIBLE && isShown()
                && getTranslationX() == 0f && getTranslationY() == 0f
                && getAlpha() > 0.999f
                && Math.abs(getScaleX() - 1f) < 0.001f
                && Math.abs(getScaleY() - 1f) < 0.001f
                && (!(getParent() instanceof InfoCardStripView)
                    || ((InfoCardStripView) getParent()).canAnimateCardResize());

        if (!visibleResting || !canAnimate) {
            textView.setText(text, false, false);
            if (getVisibility() == VISIBLE) {
                content.requestLayout();
                content.invalidateOutline();
            }
            return;
        }

        textView.setText(text, true);
    }

    private void onAnimatedTextWidthUpdated() {
        if (!isAttachedToWindow() || getWindowVisibility() != View.VISIBLE
                || getVisibility() != VISIBLE || !isShown()) {
            return;
        }
        if (getParent() instanceof InfoCardStripView
                && ((InfoCardStripView) getParent()).isLayoutSuppressed()) {
            return;
        }
        textView.requestLayout();
        if (getVisibility() == VISIBLE) {
            content.requestLayout();
            content.invalidateOutline();
        }
        invalidate();
    }

    void finishResizeAnimation() {
        if (textView.isAnimating()) {
            textView.cancelAnimation();
        }
        if (accessibilityValue != null) {
            
            textView.setText(accessibilityValue, false, false);
        }
        content.requestLayout();
        content.invalidateOutline();
    }

    void updateLayoutDirection() {
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) content.getLayoutParams();
        int gravity = Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
        if (lp != null && lp.gravity != gravity) {
            lp.gravity = gravity;
            content.setLayoutParams(lp);
        }
    }

    public void renderNextInstant() {
        nextRenderInstant = true;
    }

    public void setMaxChipWidth(int maxTextWidth) {
        maxChipWidth = Math.max(0, maxTextWidth);
        applyMaxChipWidth();
    }

    private void applyMaxChipWidth() {
        
        int chrome = content.getPaddingLeft() + content.getPaddingRight();
        if (iconVisible) chrome += AndroidUtilities.dp(16 + 4); 
        int textMax = maxChipWidth > 0 ? Math.max(1, maxChipWidth - chrome) : 0;
        if (appliedTextMaxWidth == textMax) return;
        appliedTextMaxWidth = textMax;
        textView.setMaxWidth(textMax);
        textView.setEllipsizeByGradient(textMax > 0);
    }

    protected void setTextColor(int color) {
        
        if (colorMode == InfoCardsConfig.COLOR_MODE_THEME) return;
        textView.setTextColor(color);
    }

    public abstract int getCardId();

    public abstract long getRefreshInterval();

    public abstract void onUpdateData(boolean force);

    public abstract void onCardClicked();

    public abstract boolean onCardLongClicked();

    public abstract void updateColors();

    public void onCardSelected() {
        onUpdateData(false);
    }

    public void onCardUnselected() {
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateColors();
        applyColorMode();
        
        if (isRefreshDue()) {
            onUpdateData(false);
        }
        scheduleNext();
    }

    @Override
    protected void onDetachedFromWindow() {
        finishResizeAnimation();
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(autoRefresh);
    }

    private void scheduleNext() {
        AndroidUtilities.cancelRunOnUIThread(autoRefresh);
        long iv = getRefreshInterval();
        if (iv > 0) {
            AndroidUtilities.runOnUIThread(autoRefresh, iv);
        }
    }

    protected void markDataUpdated() {
        lastUpdateMs = System.currentTimeMillis();
    }

    protected boolean isRefreshDue() {
        long iv = getRefreshInterval();
        return iv <= 0 || System.currentTimeMillis() - lastUpdateMs >= iv;
    }

    public void startLoading() {
        loading = true;
        if (loadingDrawable == null) {
            loadingDrawable = new LoadingDrawable(resourcesProvider);
            loadingDrawable.setCallback(this);
            loadingDrawable.setGradientScale(2.0f);
            loadingDrawable.setRadiiDp(CORNER_RADIUS_DP);
            updateLoadingColors();
        }
        loadingDrawable.reset();
        loadingDrawable.resetDisappear();
        loadingDrawable.setAlpha(255);
        invalidate();
    }

    public void stopLoading() {
        loading = false;
        if (loadingDrawable != null) {
            
            loadingDrawable.disappear();
            invalidate();
        }
    }

    public boolean isLoading() {
        return loading;
    }

    protected void updateLoadingColors() {
        if (loadingDrawable != null) {
            loadingDrawable.setColors(
                    Theme.multAlpha(0xffffffff, 0.1f),
                    Theme.multAlpha(0xffffffff, 0.3f));
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        
        LoadingDrawable ld = loadingDrawable;
        if (ld != null && (loading || ld.isDisappearing()) && !ld.isDisappeared()) {
            loadingRect.set(content.getLeft(), content.getTop(), content.getRight(), content.getBottom());
            ld.setBounds(loadingRect);
            ld.draw(canvas);
            invalidate();
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == loadingDrawable || super.verifyDrawable(who);
    }

    private static final class ChipTextView extends AnimatedTextView {
        ChipTextView(android.content.Context c, boolean splitByWords, boolean preserveIndex, boolean startFromEnd) {
            super(c, splitByWords, preserveIndex, startFromEnd);
        }

        @Override
        public void requestLayout() {
            
            if (getVisibility() == GONE || !isShown()) return;
            super.requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (adaptWidth && View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.AT_MOST) {
                
                int want = isAnimating() ? width() : finalWidth();
                int avail = View.MeasureSpec.getSize(widthMeasureSpec);
                setMeasuredDimension(Math.min(want, avail), getMeasuredHeight());
            }
        }
    }

    private static final class CardBackground extends Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int topColor, bottomColor;
        
        private boolean themeMode;

        CardBackground(int top, int bottom) {
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(AndroidUtilities.dp(1));
            setColors(top, bottom);
        }

        void setThemeMode(boolean theme) {
            this.themeMode = theme;
            invalidateSelf();
        }

        void setColors(int top, int bottom) {
            this.topColor = top;
            this.bottomColor = bottom;
            float h = AndroidUtilities.dp(CHIP_HEIGHT_DP);
            fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{top, bottom}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            
            strokePaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0x4DFFFFFF, 0x00000000, 0x1AFFFFFF}, new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float r = AndroidUtilities.dp(CORNER_RADIUS_DP);
            RectF rf = AndroidUtilities.rectTmp;
            rf.set(bounds);
            canvas.drawRoundRect(rf, r, r, fillPaint);
            
            if (themeMode) return;
            
            Theme.ThemeInfo active = Theme.getActiveTheme();
            if (!Theme.isCurrentThemeDark() || (active != null && active.isMonet())) return;
            float sw = AndroidUtilities.dp(1);
            strokePaint.setStrokeWidth(sw);
            float half = sw / 2f;
            rf.inset(half, half);
            canvas.drawRoundRect(rf, r, r, strokePaint);
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
