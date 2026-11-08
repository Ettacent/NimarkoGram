/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.glass;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.SimpleActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SectionsScrollView;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceCrossfade;
import org.telegram.utils.glass.source.GlassSourceRenderNode;
import org.telegram.ui.Components.blur3.utils.Blur3Utils;
import org.telegram.ui.ComposeDrawable;
import org.telegram.utils.glass.fade.GlassFadeBlurredDrawable;
import org.telegram.utils.glass.fade.GlassFadeDirection;
import org.telegram.utils.glass.fade.GlassFadeRangeParams;
import org.telegram.utils.glass.positions.GlassPositionsArray;
import org.telegram.utils.glass.positions.GlassPositionsMerger;
import org.telegram.utils.glass.source.GlassSource;
import org.telegram.utils.glass.source.GlassSourceEmpty;

import me.vkryl.core.BitwiseUtils;

public class BaseFragmentSimpleGlass {
    private final GlassEngine glassEngine;

    private final @NonNull GlassSourceColor glassBackgroundSourceColor;

    public final @NonNull BlurredBackgroundDrawableViewFactory factory;
    public final @NonNull BlurredBackgroundDrawableViewFactory factoryColor;
    public final @NonNull BlurredBackgroundDrawableViewFactory menuFactory;
    public final @Nullable GlassSourceCrossfade menuGlassSource;
    public final @NonNull BlurredBackgroundDrawableViewFactory frostedFactory;

    private final @Nullable GlassCaptureSourceCanvas glassCaptureSourceCanvas;

    private final @Nullable GlassSourceRenderNode glassSource;
    private final @Nullable GlassSourceRenderNode frostedGlassSource;
    private @Nullable Runnable onCaptured;

    private final @NonNull GlassSource glassSourceForFadeDrawable;

    public BaseFragmentSimpleGlass(GlassEngine engine, GlassEngine.ThemedColorDelegate themedColor) {
        glassEngine = engine;
        glassEngine.setGlassInvalidationListener(this::invalidate);
        glassEngine.setPositionsMerger(new GlassPositionsMerger(Float.POSITIVE_INFINITY, dp(48)));

        glassBackgroundSourceColor = new GlassSourceColor();
        glassBackgroundSourceColor.setColor(themedColor.getThemedColor());
        glassEngine.addColorSource(glassBackgroundSourceColor, themedColor);

        factoryColor = new BlurredBackgroundDrawableViewFactory(glassBackgroundSourceColor);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            glassCaptureSourceCanvas = new GlassCaptureSourceCanvas();

            final GlassSourceRenderNode glassBackgroundSourceRenderNode =
                    new GlassSourceRenderNode(glassBackgroundSourceColor);
            glassBackgroundSourceRenderNode.setUnderSource(glassBackgroundSourceColor);

            glassBackgroundSourceRenderNode.setGlassCaptureSourceCanvas(glassCaptureSourceCanvas, GlassCaptureSourceCanvas.SourceIndex.GLASS);
            glassSource = glassBackgroundSourceRenderNode;

            final GlassSourceRenderNode frostedSourceRenderNode = new GlassSourceRenderNode(glassBackgroundSourceColor);
            frostedGlassSource = frostedSourceRenderNode;
            frostedSourceRenderNode.setUnderSource(glassBackgroundSourceColor);
            frostedSourceRenderNode.setGlassCaptureSourceCanvas(glassCaptureSourceCanvas, GlassCaptureSourceCanvas.SourceIndex.FROSTED_GLASS);

            factory = new BlurredBackgroundDrawableViewFactory(glassBackgroundSourceRenderNode, frostedSourceRenderNode, glassBackgroundSourceColor);
            factory.setGlassEngine(glassEngine);
            factory.setLiquidGlassEffectAllowed(true);

            menuGlassSource = new GlassSourceCrossfade(glassBackgroundSourceRenderNode, frostedSourceRenderNode);
            frostedFactory = new BlurredBackgroundDrawableViewFactory(frostedSourceRenderNode, frostedSourceRenderNode, glassBackgroundSourceColor);
            frostedFactory.setGlassEngine(glassEngine);
            menuFactory = new BlurredBackgroundDrawableViewFactory(menuGlassSource, frostedSourceRenderNode, glassBackgroundSourceColor);
            menuFactory.setGlassEngine(glassEngine);
            menuFactory.setLiquidGlassEffectAllowed(true);

            glassSourceForFadeDrawable = glassCaptureSourceCanvas.getSource(GlassCaptureSourceCanvas.SourceIndex.BLURRED);
        } else {
            glassSourceForFadeDrawable = GlassSourceEmpty.INSTANCE;
            glassCaptureSourceCanvas = null;
            glassSource = null;
            frostedGlassSource = null;
            factory = new BlurredBackgroundDrawableViewFactory(glassBackgroundSourceColor);
            factory.setGlassEngine(glassEngine);
            menuGlassSource = null;
            menuFactory = factory;
            frostedFactory = factory;
        }
    }

    @Nullable
    public GlassCaptureSourceCanvas getCaptureSourceCanvas() {
        return glassCaptureSourceCanvas;
    }

    private boolean captureIgnoresBlurSetting;

    public void setCaptureIgnoresBlurSetting(boolean ignores) {
        captureIgnoresBlurSetting = ignores;
    }

    @Nullable
    public GlassSourceRenderNode getGlassSource() {
        return GlassEngine.getCurrentGlassSettings().isBlurEnabled ? glassSource : null;
    }

    @Nullable
    public GlassSourceRenderNode getFrostedGlassSource() {
        return GlassEngine.getCurrentGlassSettings().isBlurEnabled ? frostedGlassSource : null;
    }

    public void setOnCaptured(@Nullable Runnable onCaptured) {
        this.onCaptured = onCaptured;
    }

    private View contentView;
    private View clipView;

    private IBlur3Capture capture;

    @NonNull
    public Drawable createActionBarFadeDrawable(View view) {
        final GlassFadeBlurredDrawable glassBlurredFadeDrawable = new GlassFadeBlurredDrawable(
            glassSourceForFadeDrawable);

        final GlassFadeRangeParams gradientRangeMask = new GlassFadeRangeParams(
                dp(32), GlassFadeRangeParams.Gravity.START, 0, 0);
        final GlassFadeRangeParams gradientRangeFade = new GlassFadeRangeParams(
                dp(56), GlassFadeRangeParams.Gravity.START, 0, 0);
        final GlassFadeRangeParams gradientRangeFallback = new GlassFadeRangeParams(
                dp(48), GlassFadeRangeParams.Gravity.START, 0, 0);

        glassBlurredFadeDrawable.setDirection(GlassFadeDirection.TOP);
        glassBlurredFadeDrawable.setOverlayColorSource(glassBackgroundSourceColor);
        glassBlurredFadeDrawable.setOverlayAlpha(160);
        glassBlurredFadeDrawable.setMaskRange(gradientRangeMask);
        glassBlurredFadeDrawable.setOverlayRange(gradientRangeFade);

        glassBlurredFadeDrawable.setOverlayOnlyAlpha(210);
        glassBlurredFadeDrawable.setOverlayOnlyRange(gradientRangeFallback);

        glassEngine.registerDrawable(view, glassBlurredFadeDrawable);
        return glassBlurredFadeDrawable;
    }

    @NonNull
    public Drawable createNavigationBarFadeDrawable(View view) {
        final GlassFadeBlurredDrawable glassBlurredFadeDrawable = new GlassFadeBlurredDrawable(
            glassSourceForFadeDrawable);

        final GlassFadeRangeParams gradientRangeMask = new GlassFadeRangeParams(
                dp(28), GlassFadeRangeParams.Gravity.START, 0, 0);
        final GlassFadeRangeParams gradientRangeFade = new GlassFadeRangeParams(
                dp(40), GlassFadeRangeParams.Gravity.START, 0, 0);
        final GlassFadeRangeParams gradientRangeFallback = new GlassFadeRangeParams(
                dp(30), GlassFadeRangeParams.Gravity.START, 0, 0);

        glassBlurredFadeDrawable.setDirection(GlassFadeDirection.BOTTOM);
        glassBlurredFadeDrawable.setOverlayColorSource(glassBackgroundSourceColor);
        glassBlurredFadeDrawable.setOverlayAlpha(160);
        glassBlurredFadeDrawable.setMaskRange(gradientRangeMask);
        glassBlurredFadeDrawable.setOverlayRange(gradientRangeFade);

        glassBlurredFadeDrawable.setOverlayOnlyAlpha(210);
        glassBlurredFadeDrawable.setOverlayOnlyRange(gradientRangeFallback);

        glassEngine.registerDrawable(view, glassBlurredFadeDrawable);
        return glassBlurredFadeDrawable;
    }

    public void setViews(FrameLayout contentView, RecyclerListView listView, @Nullable View actionBar, Theme.ResourcesProvider resourceProvider) {
        this.contentView = contentView;
        this.clipView = listView;
        listView.setCaptureSectionsDecoratorAllowed(true);
        capture = (canvas, position) -> Blur3Utils.captureRelativeParent(listView, canvas, position, listView, contentView);
        glassEngine.addScrolledView(listView);

        listView.setClipToPadding(false);

        setupHeader(contentView, actionBar, resourceProvider);

        if (BuildConfig.DEBUG_PRIVATE_VERSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
             if (glassCaptureSourceCanvas != null) {
             }
        }
    }

    public void setViews(FrameLayout contentView, SectionsScrollView listView, @Nullable View actionBar, Theme.ResourcesProvider resourceProvider) {
        this.contentView = contentView;
        this.clipView = listView;
        capture = (canvas, position) -> Blur3Utils.captureRelativeParent(listView, canvas, position, listView, contentView);
        glassEngine.addScrolledView(listView);

        listView.setClipToPadding(false);

        setupHeader(contentView, actionBar, resourceProvider);
    }

    public void setViews(FrameLayout contentView, ViewPagerFixed viewPager, @Nullable View actionBar, Theme.ResourcesProvider resourceProvider) {
        this.contentView = contentView;
        this.clipView = viewPager;
        glassEngine.addScrolledView(viewPager);

        setupHeader(contentView, actionBar, resourceProvider);
    }

    public void setViews(FrameLayout contentView, @Nullable View actionBar, Theme.ResourcesProvider resourceProvider) {
        this.contentView = contentView;
        this.clipView = null;

        setupHeader(contentView, actionBar, resourceProvider);
    }

    private void setupHeader(FrameLayout contentView, @Nullable View header, Theme.ResourcesProvider resourceProvider) {
        if (header == null) {
            return;
        }
        AndroidUtilities.removeFromParent(header);
        contentView.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        if (header instanceof SimpleActionBar) {
            final SimpleActionBar simpleActionBar = (SimpleActionBar) header;
            simpleActionBar.setExtraHeight(dp(6));
            simpleActionBar.setupGlass(factory, BlurredBackgroundProviderImpl.simpleActionBar(resourceProvider));
            simpleActionBar.setMenuGlass(menuFactory, menuGlassSource);
        } else if (header instanceof ActionBar) {
            final ActionBar actionBar = (ActionBar) header;
            actionBar.setAddToContainer(false);
            actionBar.setExtraHeight(dp(6));
            actionBar.doNotDrawCenterGlass = true;
            actionBar.setupGlass(factory, BlurredBackgroundProviderImpl.simpleActionBar(resourceProvider));
            actionBar.setMenuGlass(menuFactory, menuGlassSource);
        }
    }

    public void setClipView(View clipView) {
        this.clipView = clipView;
    }

    public void setCapture(IBlur3Capture capture) {
        this.capture = capture;
    }

    private void invalidate(int flags) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || glassCaptureSourceCanvas == null || !captureIgnoresBlurSetting && !GlassEngine.getCurrentGlassSettings().isBlurEnabled) {
            return;
        }

        if (BitwiseUtils.hasFlag(flags, GlassEngine.FLAG_INVALIDATED_POSITIONS) || BitwiseUtils.hasFlag(flags, GlassEngine.FLAG_INVALIDATED_SCROLL_EDGES)) {
            GlassPositionsArray glassPositionsArray = glassEngine.getAllVisibleDrawablesWithDisplayListPositionsMerged();
            if (clipView != null) {
                glassPositionsArray.clip(0, clipView.getY(), contentView.getWidth(), clipView.getY() + clipView.getHeight());
            }

            if (glassCaptureSourceCanvas != null) {
                glassCaptureSourceCanvas.setPositions(glassPositionsArray);
            }
        }

        if (glassCaptureSourceCanvas != null) {
            glassCaptureSourceCanvas.setCapture(capture);
            glassCaptureSourceCanvas.setRootView(contentView);
            glassCaptureSourceCanvas.setAllowDownsample(!glassEngine.hasScrollEdges());
            glassCaptureSourceCanvas.setOffset(glassEngine.getScrollOffsetX(), glassEngine.getScrollOffsetY());
            glassCaptureSourceCanvas.invalidate();
        }

        if (onCaptured != null) {
            onCaptured.run();
        }
    }

    public static final int PILLS_GAP_DP = 7;

    public static int getPillGlassTop(int insetTop) {
        final int rowHeight = ActionBar.getCurrentActionBarHeight();
        return insetTop + rowHeight - (rowHeight - dp(46)) / 2 + dp(PILLS_GAP_DP);
    }

    public static int getOverscan(int inset) {
        return Math.max(dp(8), dp(48) - inset);
    }

    public static void setListViewParams(ViewGroup listView, int insetTop, int insetBottom, int paddingTop, int paddingBottom) {
        final int mT = -getOverscan(insetTop);
        final int pT = insetTop + paddingTop - mT;
        final int mB = -getOverscan(insetBottom);
        final int pB = insetBottom + paddingBottom - mB;

        AndroidUtilities.setViewLayoutMargins(listView, 0, mT, 0, mB);
        listView.setPadding(0, pT, 0, pB);
    }
}
