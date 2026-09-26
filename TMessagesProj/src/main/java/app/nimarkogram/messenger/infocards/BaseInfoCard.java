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
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LoadingDrawable;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.LaunchActivity;
import app.nimarkogram.messenger.infocards.preferences.InfoCardsPreferencesActivity;

public abstract class BaseInfoCard extends FrameLayout {

    protected final Theme.ResourcesProvider resourcesProvider;

    private static final int CHIP_HEIGHT_DP = 28;
    private static final int CORNER_RADIUS_DP = 14;
    private static final int CONTENT_PADDING_DP = 8;

    private static final long RESIZE_DURATION_MS = 300;
    private static final float RESIZE_TEXT_SCALE_POP = 0.12f; 
    private static final TimeInterpolator RESIZE_INTERPOLATOR = CubicBezierInterpolator.EASE_OUT_QUINT;

    private final LinearLayout content;
    protected final ImageView iconView;
    protected final AnimatedTextView textView;
    private final CardBackground background;
    private BlurredBackgroundDrawableViewFactory glassBackgroundFactory;
    private final Runnable glassSettingsChanged = this::updateGlassBackground;
    private boolean iconVisible = true;

    private int brandTop = 0xff2b2b2b, brandBottom = 0xff202020;
    private int colorMode = InfoCardsConfig.COLOR_MODE_CUSTOM;
    
    private boolean opaqueFlat;
    
    private boolean inlineFolderStyle;
    private boolean renderingInstantly;
    private boolean hasRenderedValue;
    private int lastIconRes;
    private boolean deferredCarouselText;
    private int deferredCarouselIconRes = -1;
    private Boolean deferredCarouselIconVisibility;
    
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
        
        content.setPadding(AndroidUtilities.dp(CONTENT_PADDING_DP), 0, AndroidUtilities.dp(CONTENT_PADDING_DP), 0);
        content.setMinimumWidth(AndroidUtilities.dp(48));
        
        content.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), getChipCornerRadius());
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
        textView.getDrawable().setFadeOverflow(true);
        textView.getDrawable().setStableBaseline(true);
        textView.adaptWidth = true;
        textView.setTextSize(AndroidUtilities.dp(13));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.setTextColor(0xffffffff);
        textView.setGravity(Gravity.CENTER_VERTICAL);
        
        textView.setAnimationProperties(0f, 0, RESIZE_DURATION_MS, RESIZE_INTERPOLATOR);
        textView.setScaleProperty(RESIZE_TEXT_SCALE_POP);
        
        textView.setOnWidthUpdatedListener(this::onAnimatedTextWidthUpdated);
        textView.setText("", false, false);
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
        if (background.glass != null) layerType = View.LAYER_TYPE_NONE;
        if (getLayerType() != layerType) {
            setLayerType(layerType, null);
        }
    }
    public void setGlassBackgroundFactory(BlurredBackgroundDrawableViewFactory factory) {
        if (glassBackgroundFactory == factory) return;
        releaseGlassBackground();
        glassBackgroundFactory = factory;
        updateGlassBackground();
    }
    private void releaseGlassBackground() {
        if (background.glass != null) {
            glassBackgroundFactory.release(content, background.glass);
            background.glass = null;
            content.invalidate();
        }
    }
    private void updateGlassBackground() {
        boolean liquidGlass = glassBackgroundFactory != null && glassBackgroundFactory.supportsLiquidGlass()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && SharedConfig.chatBlurEnabled() && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
        boolean enabled = isAttachedToWindow() && glassBackgroundFactory != null
                && (inlineFolderStyle || liquidGlass);
        if (!enabled) {
            releaseGlassBackground();
            return;
        }
        if (background.glass == null) {
            background.glass = glassBackgroundFactory.create(content);
            background.glass.setCallback(background);
            background.glass.setRadius(getChipCornerRadius());
            background.glass.setAlpha(background.drawableAlpha);
            setLayerType(View.LAYER_TYPE_NONE, null);
        }
        if (inlineFolderStyle || isFlat()) {
            background.glass.setColorProvider(BlurredBackgroundProviderImpl.topPanel(resourcesProvider));
        } else {
            final int tint = Theme.multAlpha(ColorUtils.blendARGB(brandTop, brandBottom, .5f),
                    background.brandedGlassOpacity);
            background.glass.setColorProvider(new BlurredBackgroundProviderBuilder(resourcesProvider)
                    .setBackgroundColor((r, dark) -> tint)
                    .setStrokeColorTop(0x55FFFFFF, 0x35FFFFFF)
                    .setStrokeColorBottom(0x18000000, 0x18FFFFFF)
                    .setShadowColor(0, 0).setShadowLayer(0, 0, 0)
                    .setStrokeWidth(AndroidUtilities.dpf2(.5f), AndroidUtilities.dpf2(.5f)).build());
        }
        content.invalidate();
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
        if (shouldDeferCarouselData()) {
            deferredCarouselIconRes = resId;
            deferredCarouselIconVisibility = true;
            return;
        }
        deferredCarouselIconRes = -1;
        deferredCarouselIconVisibility = null;
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
        background.brandedGlassOpacity = getBrandedGlassOpacity(
                AndroidUtilities.computePerceivedBrightness(Theme.getColor(
                        Theme.key_windowBackgroundWhite, resourcesProvider)) < .721f);
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
        background.setShellColor(Theme.multAlpha(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), .12f));
        updateGlassBackground();
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
    public void setInlineFolderStyle(boolean inline) {
        if (inlineFolderStyle == inline) return;
        inlineFolderStyle = inline;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) content.getLayoutParams();
        params.height = getChipHeight(inline);
        content.setLayoutParams(params);
        int padding = AndroidUtilities.dp(CONTENT_PADDING_DP)
                + (inline ? (int) Math.ceil(getInlineSurfaceInset(params.height)) : 0);
        content.setPadding(padding, 0, padding, 0);
        applyMaxChipWidth();
        background.inlineFolderStyle = inline;
        background.setCornerRadius(getChipCornerRadius());
        content.invalidateOutline();
        if (loadingDrawable != null) loadingDrawable.setRadii(AndroidUtilities.dp(CORNER_RADIUS_DP));
        textView.setTextSize(AndroidUtilities.dp(13));
        float iconScale = inline ? 14f / 16f : 1f;
        iconView.setScaleX(iconScale);
        iconView.setScaleY(iconScale);
        applyColorMode();
        requestLayout();
    }
    private static int getChipHeight(boolean inline) {
        return inline ? AndroidUtilities.dp(50) - 2 * AndroidUtilities.dp(6.666f)
                : AndroidUtilities.dp(CHIP_HEIGHT_DP);
    }
    private float getChipCornerRadius() {
        return inlineFolderStyle ? getChipHeight(true) / 2f : AndroidUtilities.dp(CORNER_RADIUS_DP);
    }
    private static float getInlineSurfaceInset(float height) {
        return Math.max(0, (height - AndroidUtilities.dp(CHIP_HEIGHT_DP)) / 2f);
    }
    private static void setSurfaceBounds(RectF rect, boolean inline, float left, float top, float right, float bottom) {
        rect.set(left, top, right, bottom);
        if (inline) {
            float inset = getInlineSurfaceInset(bottom - top);
            rect.inset(inset, inset);
        }
    }

    protected int contentColorOverride() {
        return 0;
    }

    protected int fillColorOverride() {
        return 0;
    }

    protected void setIconVisible(boolean visible) {
        if (shouldDeferCarouselData()) {
            deferredCarouselIconVisibility = visible;
            return;
        }
        if (deferredCarouselIconRes >= 0) {
            setIcon(deferredCarouselIconRes);
        }
        deferredCarouselIconVisibility = null;
        if (iconVisible == visible) return;
        iconVisible = visible;
        iconView.setVisibility(visible ? View.VISIBLE : View.GONE);
        applyMaxChipWidth();
    }

    protected void setText(CharSequence text, boolean animated) {
        accessibilityValue = text;
        updateAccessibilityDescription();
        if (shouldDeferCarouselData()) {
            deferredCarouselText = !android.text.TextUtils.equals(textView.getText(), text);
            return;
        }
        deferredCarouselText = false;
        if (!android.text.TextUtils.isEmpty(text) && !hasRenderedValue) {
            hasRenderedValue = true;
            boolean laidOutColdLoad = animated && isAttachedToWindow() && isLaidOut()
                    && getVisibility() == VISIBLE
                    && getParent() instanceof InfoCardStripView
                    && ((InfoCardStripView) getParent()).canAnimateCardResize();
            animated = laidOutColdLoad;
        }
        
        animated &= !renderingInstantly;
        boolean changed = !android.text.TextUtils.equals(textView.getText(), text);
        if (!changed) {
            if (renderingInstantly) finishResizeAnimation();
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
    private boolean shouldDeferCarouselData() {
        return !renderingInstantly && getVisibility() == VISIBLE && isShown() && getAlpha() > 0f
                && getParent() instanceof InfoCardStripView
                && ((InfoCardStripView) getParent()).isLayoutSuppressed();
    }
    private void applyDeferredCarouselIcon() {
        int icon = deferredCarouselIconRes;
        Boolean visible = deferredCarouselIconVisibility;
        deferredCarouselIconRes = -1;
        deferredCarouselIconVisibility = null;
        if (icon >= 0) setIcon(icon);
        if (visible != null) setIconVisible(visible);
    }
    void applyDeferredCarouselData() {
        if (shouldDeferCarouselData()) return;
        applyDeferredCarouselIcon();
        if (deferredCarouselText) {
            deferredCarouselText = false;
            setText(accessibilityValue, true);
        }
    }

    private void onAnimatedTextWidthUpdated() {
        if (!isAttachedToWindow() || getWindowVisibility() != View.VISIBLE
                || getVisibility() != VISIBLE || !isShown()) {
            return;
        }
        ((ChipTextView) textView).requestAnimatedWidthLayout();
        invalidate();
    }

    void finishResizeAnimation() {
        boolean previous = renderingInstantly;
        renderingInstantly = true;
        try {
            applyDeferredCarouselIcon();
        } finally {
            renderingInstantly = previous;
        }
        deferredCarouselText = false;
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

    void updateDataInstantly() {
        boolean previous = renderingInstantly;
        renderingInstantly = true;
        try {
            onUpdateData(false);
        } finally {
            renderingInstantly = previous;
        }
    }
    void restoreRenderedValue() {
        if (accessibilityValue == null) {
            return;
        }
        if (!android.text.TextUtils.isEmpty(accessibilityValue)) {
            hasRenderedValue = true;
        }
        textView.cancelAnimation();
        textView.setText(accessibilityValue, false, false);
        updateAccessibilityDescription();
        content.requestLayout();
        content.invalidateOutline();
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

    public boolean onCardLongClicked() {
        final BaseFragment fragment = getVisibleMenuFragment();
        if (fragment == null) return false;
        ItemOptions options = ItemOptions.makeOptions(fragment, this).setDrawScrim(false);
        options.add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings), () -> {
            if (getVisibleMenuFragment() == fragment) {
                fragment.presentFragment(new InfoCardsPreferencesActivity());
            }
        });
        options.setGravity(LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT).show();
        return options.isShown();
    }
    private BaseFragment getVisibleMenuFragment() {
        if (!isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE) return null;
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getContext() == null || fragment.getParentLayout() == null) return null;
        View overlay = fragment.getParentLayout().getOverlayContainerView();
        if (overlay == null || !overlay.isAttachedToWindow() || overlay.getWindowToken() != getWindowToken()) return null;
        boolean ownsCard = false;
        for (View view = this; view != null;
                view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.getAlpha() <= 0f) return null;
            ownsCard |= view == fragment.getFragmentView() || view == fragment.getActionBar();
        }
        return ownsCard ? fragment : null;
    }

    public abstract void updateColors();

    public void onCardSelected() {
        onUpdateData(false);
    }

    public void onCardUnselected() {
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LiteMode.addOnGlassSettingsChangedListener(glassSettingsChanged);
        restoreRenderedValue();
        updateColors();
        applyColorMode();
        
        if (isRefreshDue()) {
            onUpdateData(false);
        }
        scheduleNext();
    }

    @Override
    protected void onDetachedFromWindow() {
        LiteMode.removeOnGlassSettingsChangedListener(glassSettingsChanged);
        finishResizeAnimation();
        releaseGlassBackground();
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
            loadingDrawable.setRadii(AndroidUtilities.dp(CORNER_RADIUS_DP));
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
            int color = currentContentColor();
            loadingDrawable.setColors(
                    Theme.multAlpha(color, 0.1f),
                    Theme.multAlpha(color, 0.3f));
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);
        
        LoadingDrawable ld = loadingDrawable;
        if (ld != null && (loading || ld.isDisappearing()) && !ld.isDisappeared()) {
            setSurfaceBounds(loadingRect, inlineFolderStyle,
                    content.getLeft(), content.getTop(), content.getRight(), content.getBottom());
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
        private int textWidthLimit;
        private int lastAvailableWidth = -1;
        ChipTextView(android.content.Context c, boolean splitByWords, boolean preserveIndex, boolean startFromEnd) {
            super(c, splitByWords, preserveIndex, startFromEnd);
        }

        @Override
        public void setMaxWidth(int width) {
            super.setMaxWidth(width);
            if (textWidthLimit != width) {
                textWidthLimit = width;
                requestLayout();
                invalidate();
            }
        }
        @Override
        public void requestLayout() {
            
            if (getVisibility() == GONE || !isShown()) {
                forceLayout();
                android.view.ViewParent parent = getParent();
                while (parent instanceof View && !(parent instanceof InfoCardStripView)) {
                    ((View) parent).forceLayout();
                    parent = parent.getParent();
                }
                return;
            }
            super.requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            lastAvailableWidth = -1;
            if (adaptWidth && View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.AT_MOST) {
                
                int avail = View.MeasureSpec.getSize(widthMeasureSpec);
                if (textWidthLimit > 0) avail = Math.min(avail, textWidthLimit);
                lastAvailableWidth = avail;
                setMeasuredDimension(animatedWidth(avail), getMeasuredHeight());
            }
        }
        private int animatedWidth(int available) {
            int padding = getPaddingLeft() + getPaddingRight();
            int want = padding + (int) Math.ceil(getDrawable().getCurrentWidth(Math.max(0, available - padding)));
            return Math.min(want, available);
        }
        void requestAnimatedWidthLayout() {
            if (lastAvailableWidth < 0 || animatedWidth(lastAvailableWidth) != getMeasuredWidth()) {
                requestLayout();
            }
        }
    }

    private static float getBrandedGlassOpacity(boolean dark) {
        return dark ? .78f : .90f;
    }
    private static final class CardBackground extends Drawable implements Drawable.Callback {
        private float brandedGlassOpacity = .90f;
        private BlurredBackgroundDrawable glass;
        private float cornerRadius = AndroidUtilities.dp(CORNER_RADIUS_DP);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF surfaceRect = new RectF();
        private boolean inlineFolderStyle;
        private int shellColorAlpha;
        private int drawableAlpha = 255;
        private int topColor, bottomColor;
        
        private boolean themeMode;

        CardBackground(int top, int bottom) {
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(AndroidUtilities.dp(1));
            shellPaint.setStyle(Paint.Style.STROKE);
            shellPaint.setStrokeWidth(AndroidUtilities.dpf2(.5f));
            setColors(top, bottom);
        }

        void setThemeMode(boolean theme) {
            this.themeMode = theme;
            invalidateSelf();
        }
        void setShellColor(int color) {
            shellColorAlpha = color >>> 24;
            shellPaint.setColor(color);
            shellPaint.setAlpha(Math.round(shellColorAlpha * drawableAlpha / 255f));
            invalidateSelf();
        }
        void setCornerRadius(float radius) {
            if (cornerRadius == radius) return;
            cornerRadius = radius;
            if (glass != null) glass.setRadius(radius);
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
            if (inlineFolderStyle) {
                drawInline(canvas, bounds);
                return;
            }
            if (glass != null && canvas.isHardwareAccelerated()) {
                glass.setBounds(bounds);
                glass.draw(canvas);
                if (!themeMode) {
                    int oldAlpha = fillPaint.getAlpha();
                    fillPaint.setAlpha(Math.round(oldAlpha * .18f));
                    canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                            cornerRadius, cornerRadius, fillPaint);
                    fillPaint.setAlpha(oldAlpha);
                }
                return;
            }
            float r = cornerRadius;
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
        private void drawInline(Canvas canvas, Rect bounds) {
            boolean liveGlass = glass != null && canvas.isHardwareAccelerated()
                    && BlurredBackgroundDrawableViewFactory.isLiquidGlassEnabled();
            if (glass != null) {
                glass.setBounds(bounds);
                glass.draw(canvas);
            } else {
                surfaceRect.set(bounds);
                float half = shellPaint.getStrokeWidth() / 2f;
                surfaceRect.inset(half, half);
                canvas.drawRoundRect(surfaceRect, cornerRadius, cornerRadius, shellPaint);
            }
            setSurfaceBounds(surfaceRect, true, bounds.left, bounds.top, bounds.right, bounds.bottom);
            int save = canvas.save();
            canvas.translate(surfaceRect.left, surfaceRect.top);
            int oldAlpha = fillPaint.getAlpha();
            if (liveGlass) fillPaint.setAlpha(Math.round(oldAlpha * (themeMode ? .18f : brandedGlassOpacity)));
            float radius = AndroidUtilities.dp(CORNER_RADIUS_DP);
            canvas.drawRoundRect(0, 0, surfaceRect.width(), surfaceRect.height(), radius, radius, fillPaint);
            fillPaint.setAlpha(oldAlpha);
            canvas.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            drawableAlpha = alpha;
            fillPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
            shellPaint.setAlpha(Math.round(shellColorAlpha * alpha / 255f));
            if (glass != null) glass.setAlpha(alpha);
        }

        @Override
        public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fillPaint.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
            shellPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
