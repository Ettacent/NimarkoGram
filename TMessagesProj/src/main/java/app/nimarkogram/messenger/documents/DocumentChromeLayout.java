/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceRenderNode;
import java.util.ArrayList;

class DocumentChromeLayout extends FrameLayout {
    final LinearLayout controls;
    final LinearLayout navigation;
    final LinearLayout zoom;
    private final GlassSourceColor fallback = new GlassSourceColor();
    private final GlassSourceRenderNode source;
    private final DocumentGlassFactory factory;
    private final DocumentGlassTransition material = new DocumentGlassTransition();
    private Runnable materialFrame;
    private boolean materialEligible;
    private boolean materialReaderReady;
    private boolean controlsParentReveal;
    private final DownscaleScrollableNoiseSuppressor capture;
    private final ViewPositionWatcher positions;
    private final ChatActivityFadeView edgeFade;
    private final ArrayList<RectF> glassRegions = new ArrayList<>();
    private ActionBar header;
    private final BlurredBackgroundDrawable[] surfaces = new BlurredBackgroundDrawable[2];
    private View content;
    private int background, surface, divider;
    private boolean active;
    private int bottomInset;
    private int counterWidth;
    private boolean searchActive;
    private long contentGeneration;
    private boolean capturingContent;
    private boolean drawingContent;
    private boolean capturedThisDraw;
    private boolean released;
    private final BlurredBackgroundProvider colors;
    private final IBlur3Capture contentCapture = new IBlur3Capture() {
        @Override public void capture(Canvas canvas, RectF position) {
            canvas.drawColor(background);
            if (content == null || content.getVisibility() != VISIBLE || content.getAlpha() <= 0f) return;
            int save = canvas.save();
            try {
                canvas.clipRect(position);
                if (content.getAlpha() < 1f) canvas.saveLayerAlpha(position, Math.round(255 * content.getAlpha()));
                canvas.translate(content.getX() - content.getScrollX(), content.getY() - content.getScrollY());
                if (content instanceof PdfDocumentView) ((PdfDocumentView) content).captureForBlur(canvas);
                else if (content instanceof TextDocumentView) ((TextDocumentView) content).captureForBlur(canvas);
                else if (content instanceof OfficeDocumentView) ((OfficeDocumentView) content).captureForBlur(canvas);
                else content.draw(canvas);
            } finally { canvas.restoreToCount(save); }
        }
        @Override public void captureCalculateHash(IBlur3Hash hash, RectF position) {
            if (content instanceof OfficeDocumentView) ((OfficeDocumentView) content).captureBlurHash(hash);
            else hash.add(contentGeneration);
            hash.add(background);
            if (content != null) {
                hash.add(content.getWidth()); hash.add(content.getHeight());
                hash.add(content.getScrollX()); hash.add(content.getScrollY());
                hash.addF(content.getX()); hash.addF(content.getY()); hash.addF(content.getAlpha());
                hash.add(content.getVisibility());
            }
        }
    };

    DocumentChromeLayout(Context context) {
        this(context, UserConfig.selectedAccount);
    }

    DocumentChromeLayout(Context context, int account) {
        super(context);
        setClipChildren(false);
        colors = BlurredBackgroundProviderImpl.topPanelChatActivity(account,
                key -> key == Theme.key_chat_topPanelBackground || key == Theme.key_actionBarDefault
                        || key == Theme.key_windowBackgroundWhite ? surface : key == Theme.key_divider ? divider : Theme.getColor(key));
        if (Build.VERSION.SDK_INT >= 31) {
            capture = new DownscaleScrollableNoiseSuppressor(true, false,
                    DownscaleScrollableNoiseSuppressor.CaptureFilter.BEFORE_DOWNSCALE);
            source = new GlassSourceRenderNode(fallback);
            source.setUnderSource(fallback);
            source.setScrollableNoiseSuppressor(capture, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
            factory = new DocumentGlassFactory(source, fallback, this::materialProgress);
            factory.setLiquidGlassEffectAllowed(true);
        } else {
            capture = null;
            source = null;
            factory = new DocumentGlassFactory(fallback, fallback, this::materialProgress);
        }
        positions = new ViewPositionWatcher(this);
        factory.setSourceRootView(positions, this);
        edgeFade = new ChatActivityFadeView(context);
        edgeFade.setup(new BlurredBackgroundDrawableViewFactory(fallback));
        edgeFade.setFadeHeightTop(AndroidUtilities.dp(48));
        edgeFade.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(edgeFade, LayoutHelper.createFrame(-1, -1));
        controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        navigation = island(context, 0);
        zoom = island(context, 1);
        controls.addView(navigation, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams zoomParams = new LinearLayout.LayoutParams(AndroidUtilities.dp(104), -1);
        zoomParams.setMarginStart(AndroidUtilities.dp(8));
        controls.addView(zoom, zoomParams);
        addView(controls, LayoutHelper.createFrame(-1, 56, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 12, 0, 12, 12));
    }

    private LinearLayout island(Context context, int index) {
        LinearLayout view = new LinearLayout(context) {
            private final Path clip = new Path();
            @Override protected boolean verifyDrawable(Drawable who) {
                return who == surfaces[index] || super.verifyDrawable(who);
            }
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                surfaces[index].setBounds(0, 0, w, h);
                surfaces[index].setRadius(h / 2f);
                clip.rewind();
                clip.addRoundRect(0, 0, w, h, h / 2f, h / 2f, Path.Direction.CW);
            }
            @Override protected void dispatchDraw(Canvas canvas) {
                BlurredBackgroundDrawable drawable = surfaces[index];
                drawable.setSourceOffset(controls.getX() + getX(), controls.getY() + getY());
                drawable.draw(canvas);
                canvas.save();
                canvas.clipPath(clip);
                super.dispatchDraw(canvas);
                canvas.restore();
            }
        };
        view.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        view.setGravity(Gravity.CENTER_VERTICAL);
        surfaces[index] = factory.createForOverlay(view, colors).setPadding(0);
        surfaces[index].setCallback(view);
        return view;
    }

    void setContent(View view) {
        controlsParentReveal = false;
        resetMaterialPresentation();
        materialReaderReady = false;
        materialEligible = false;
        if (capture != null) capture.resetScrollPhase();
        content = view;
        contentGeneration++;
        addView(view, 0, LayoutHelper.createFrame(-1, -1));
    }

    void invalidateContentForGlass(View owner) {
        if (!isContentOwner(owner)) return;
        contentGeneration++;
        if (active && !capturingContent && source != null) invalidate();
    }

    void onContentScrolledForGlass(View owner, float dx, float dy) {
        if (!isContentOwner(owner) || !Float.isFinite(dx) || !Float.isFinite(dy)) return;
        if (capture != null) capture.onScrolled(dx, dy);
        invalidateContentForGlass(owner);
    }

    void onContentPresentedForGlass(View owner, float dx, float dy) {
        if (!isContentOwner(owner) || !Float.isFinite(dx) || !Float.isFinite(dy)) return;
        if (capture != null && (dx != 0 || dy != 0)) capture.onScrolled(dx, dy);
        contentGeneration++;
        if (active && !drawingContent && !capturingContent && source != null) invalidate();
    }

    private boolean isContentOwner(View owner) {
        if (released || content == null || owner == null) return false;
        ViewParent parent = owner.getParent();
        while (owner != content && parent instanceof View) {
            owner = (View) parent;
            parent = owner.getParent();
        }
        return owner == content;
    }

    BlurredBackgroundDrawableViewFactory getGlassFactory() { return factory; }

    void setHeader(ActionBar actionBar) {
        header = actionBar;
        actionBar.setAddToContainer(false);
        actionBar.setBackground(null);
        addView(actionBar, LayoutHelper.createFrame(-1, -2, Gravity.TOP));
    }

    protected void onContentInsetsChanged(int top, int bottom) {}

    void setBottomInset(int inset) {
        inset = Math.max(0, inset);
        if (bottomInset == inset) return;
        bottomInset = inset;
        updateControlsTranslation();
        requestLayout();
    }

    int getBottomInset() {
        return bottomInset;
    }

    private void updateControlsTranslation() {
        if (isLaidOut() && controls.isLaidOut()) {
            controls.setTranslationY(getHeight() - getPaddingBottom() - bottomInset - AndroidUtilities.dp(12) - controls.getBottom());
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        updateControlsTranslation();
    }

    int getContentBottomInset() {
        return bottomInset + controls.getLayoutParams().height + AndroidUtilities.dp(24);
    }

    void setCounterWidth(int width) {
        counterWidth = Math.max(0, width);
    }

    void setSearchActive(boolean value) {
        if (searchActive == value) return;
        searchActive = value;
        requestLayout();
    }

    void setActive(boolean value) {
        if (released) return;
        if (!value && active) suspendMaterialPresentation();
        if (!value) materialEligible = false;
        if (value && !active) contentGeneration++;
        active = value;
        if (!value && capture != null) capture.setupRenderNodes(glassRegions, 0);
        invalidate();
    }

    void setColors(int background, int surface, int divider) {
        this.background = background | 0xff000000;
        this.surface = surface;
        this.divider = divider;
        contentGeneration++;
        fallback.setColor(this.background);
        setBackgroundColor(this.background);
        if (source != null) factory.updateMaterialColors();
        else for (BlurredBackgroundDrawable drawable : surfaces) drawable.updateColors();
        if (source != null) source.invalidateDisplayListForDrawables();
        navigation.invalidate();
        zoom.invalidate();
        edgeFade.invalidate();
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) controls.getLayoutParams();
        params.width = Math.max(0, Math.min(AndroidUtilities.dp(480), width - getPaddingLeft() - getPaddingRight() - AndroidUtilities.dp(24)));
        params.bottomMargin = bottomInset + AndroidUtilities.dp(12);
        if (header != null) {
            header.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - getPaddingLeft() - getPaddingRight()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            edgeFade.setFadeZoneTop(header.getMeasuredHeight() + AndroidUtilities.dp(8));
        }
        onContentInsetsChanged(header == null ? 0 : header.getMeasuredHeight() + AndroidUtilities.dp(8), getContentBottomInset());
        boolean textZoom = content instanceof TextDocumentView && !searchActive;
        zoom.setVisibility(!textZoom && (params.width < Math.max(AndroidUtilities.dp(300), counterWidth + AndroidUtilities.dp(216))
                || getResources().getConfiguration().fontScale > 1.3f) ? GONE : VISIBLE);
        if (navigation.getChildCount() == 3) {
            navigation.getChildAt(0).setVisibility(textZoom ? GONE : VISIBLE);
            navigation.getChildAt(2).setVisibility(textZoom ? GONE : VISIBLE);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        boolean readerReady = isMaterialReaderReady();
        if (readerReady != materialReaderReady) {
            materialReaderReady = readerReady;
            resetMaterialPresentation();
        }
        boolean eligible = canPresentMaterial();
        if (eligible != materialEligible) {
            materialEligible = eligible;
            if (!eligible) suspendMaterialPresentation();
        }
        if (!materialMotionEnabled() && material.running()) {
            if (material.advance(material.epoch(), SystemClock.uptimeMillis(), false)) factory.invalidateMaterialViews();
            cancelMaterialFrame();
        }
        capturedThisDraw = false;
        if (header != null) header.prepareGlassForCapture();
        for (int i = 0; i < surfaces.length; i++) {
            View island = i == 0 ? navigation : zoom;
            surfaces[i].setAlpha(controls.getVisibility() == VISIBLE && island.getVisibility() == VISIBLE ? 255 : 0);
            float x = controls.getX() + island.getX();
            float y = controls.getY() + island.getY();
            if (surfaces[i].getSourceOffsetX() != x || surfaces[i].getSourceOffsetY() != y) {
                surfaces[i].setSourceOffset(x, y);
                island.invalidate();
            }
        }
        factory.primeMaterials(canvas);
        super.dispatchDraw(canvas);
    }

    @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child == content) {
            boolean dirty = child.isDirty();
            long beforeDraw = contentGeneration;
            boolean drawn;
            drawingContent = true;
            try {
                drawn = super.drawChild(canvas, child, drawingTime);
            } finally {
                drawingContent = false;
            }
            if (dirty && beforeDraw == contentGeneration) contentGeneration++;
            captureContent(canvas);
            return drawn;
        }
        if (!capturedThisDraw) captureContent(canvas);
        return super.drawChild(canvas, child, drawingTime);
    }

    private void captureContent(Canvas canvas) {
        if (capturedThisDraw) return;
        capturedThisDraw = true;
        if (source != null && active && !released && content != null
                && SharedConfig.chatBlurEnabled() && canvas.isHardwareAccelerated()
                && getWidth() > 0 && getHeight() > 0) {
            final boolean hadCapture = source.isDisplayListReady();
            if (header != null) header.prepareGlassForCapture();
            if (content instanceof OfficeDocumentView) capture.resetScrollPhase();
            int count = source.getVisiblePositions(glassRegions, 0, AndroidUtilities.dp(48));
            capture.setupRenderNodes(glassRegions, count);
            capturingContent = true;
            try {
                capture.invalidateResultRenderNodes(contentCapture, getWidth(), getHeight());
                final boolean graphChanged = capture.didDrawableGraphChange();
                source.onCaptureUpdated(graphChanged);
                if (header != null && (graphChanged || !hadCapture && source.isDisplayListReady())) {
                    header.invalidate();
                }
                if (materialEligible && count > 0 && source.isDisplayListReady()) {
                    boolean armed = material.captured(material.epoch(), SystemClock.uptimeMillis(), materialMotionEnabled());
                    if (armed && !material.running()) factory.invalidateMaterialViews();
                    if (armed) scheduleMaterialFrame();
                }
            } finally {
                capturingContent = false;
            }
        }
    }

    void beginControlsParentReveal() {
        if (released || controlsParentReveal) return;
        controlsParentReveal = true;
        navigation.invalidate();
        zoom.invalidate();
    }

    private float materialProgress(View owner) {
        if (released) return 0f;
        if (controlsParentReveal && (owner == navigation || owner == zoom)) return 1f;
        return SharedConfig.chatBlurEnabled() ? material.progress() : 1f;
    }

    private boolean isMaterialReaderReady() {
        return !released && content != null && content.getVisibility() == VISIBLE
                && controls.getVisibility() == VISIBLE;
    }

    private boolean canPresentMaterial() {
        return active && isMaterialReaderReady() && content.getAlpha() > 0f
                && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE;
    }

    private boolean materialMotionEnabled() {
        return Build.VERSION.SDK_INT >= 31 && SharedConfig.chatBlurEnabled()
                && SharedConfig.animationsEnabled()
                && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
    }

    private void cancelMaterialFrame() {
        if (materialFrame != null) removeCallbacks(materialFrame);
        materialFrame = null;
    }

    private void resetMaterialPresentation() {
        cancelMaterialFrame();
        if (material != null) material.reset();
        if (factory != null && !released) factory.invalidateMaterialViews();
    }

    private void suspendMaterialPresentation() {
        cancelMaterialFrame();
        if (material != null) material.suspend();
    }

    void beginContentLoad(View owner) {
        if (!isContentOwner(owner)) return;
        materialReaderReady = false;
        materialEligible = false;
        invalidateGlassCaptureForPrivacy();
    }

    private void scheduleMaterialFrame() {
        if (materialFrame != null || !material.running() || !canPresentMaterial()) return;
        final long owner = material.epoch();
        materialFrame = new Runnable() {
            @Override public void run() {
                if (materialFrame != this) return;
                materialFrame = null;
                if (owner != material.epoch() || !canPresentMaterial()) return;
                if (material.advance(owner, SystemClock.uptimeMillis(), materialMotionEnabled())) {
                    factory.invalidateMaterialViews();
                }
                scheduleMaterialFrame();
            }
        };
        postOnAnimation(materialFrame);
    }

    void invalidateGlassCaptureForPrivacy() {
        controlsParentReveal = false;
        resetMaterialPresentation();
        contentGeneration++;
        if (capture != null) capture.setupRenderNodes(glassRegions, 0);
        if (source != null) source.invalidateDisplayListForDrawables();
        invalidate();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE) { materialEligible = false; suspendMaterialPresentation(); }
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!isShown()) { materialEligible = false; suspendMaterialPresentation(); }
    }

    @Override protected void onDetachedFromWindow() {
        materialEligible = false;
        suspendMaterialPresentation();
        super.onDetachedFromWindow();
    }

    @Override public void onDescendantInvalidated(View child, View target) {
        invalidateContentForGlass(target != null ? target : child);
        super.onDescendantInvalidated(child, target);
    }

    void release() {
        released = true;
        active = false;
        cancelMaterialFrame();
        material.reset();
        if (capture != null) capture.setupRenderNodes(glassRegions, 0);
        factory.release(navigation, surfaces[0]);
        factory.release(zoom, surfaces[1]);
        factory.releaseMaterials();
        positions.shutdown();
        content = null;
        header = null;
    }
}
