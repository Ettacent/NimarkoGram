package org.telegram.ui.Components.blur3.drawable;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.LiquidGlassEffect;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import app.nimarkogram.messenger.NimarkoConfig;

@RequiresApi(api = Build.VERSION_CODES.Q)
public class BlurredBackgroundDrawableRenderNode extends BlurredBackgroundDrawable {
    private final BlurredBackgroundSource source;
    private final Outline outline = new Outline();
    private final Rect outlineRect = new Rect();

    private final RenderNode renderNode;
    private final RenderNode renderNodeFill;

    private final Paint paintShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStrokeTop = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStrokeBottom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStrokeFull = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean renderNodeInvalidated;

    public BlurredBackgroundDrawableRenderNode(BlurredBackgroundSource source) {
        this.renderNode = new RenderNode("BlurredNode");
        this.renderNodeFill = new RenderNode("BlurredFill");
        this.renderNode.setClipToOutline(true);
        this.renderNode.setClipToBounds(true);

        this.source = source;

        this.paintShadow.setColor(0);
        this.paintStrokeTop.setStyle(Paint.Style.STROKE);
        this.paintStrokeBottom.setStyle(Paint.Style.STROKE);
    }

    @Override
    public BlurredBackgroundDrawable setClipToOutline(boolean clipToOutline) {
        renderNode.setClipToOutline(clipToOutline);
        return super.setClipToOutline(clipToOutline);
    }

    private LiquidGlassEffect liquidGlassEffect;
    private boolean liquidGlassEffectAllowed;
    private boolean liquidGlassEffectEnabled;
    private boolean lastLiquidGlassEnabled = BlurredBackgroundDrawableViewFactory.isLiquidGlassEnabled();
    private boolean lastGlareOnElements = NimarkoConfig.glareOnElements;

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public void setLiquidGlassEffectAllowed() {
        liquidGlassEffectAllowed = true;
        renderNodeInvalidated = true;
    }
    private void updateGlassSettings(boolean hardwareAccelerated) {
        final boolean enabled = BlurredBackgroundDrawableViewFactory.isLiquidGlassEnabled();
        if (lastLiquidGlassEnabled != enabled || lastGlareOnElements != NimarkoConfig.glareOnElements) {
            lastLiquidGlassEnabled = enabled;
            lastGlareOnElements = NimarkoConfig.glareOnElements;
            updateColors();
        }
        if (!hardwareAccelerated) {
            return;
        }
        final boolean useLiquid = liquidGlassEffectAllowed && enabled
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        if (useLiquid && liquidGlassEffect == null) {
            liquidGlassEffect = new LiquidGlassEffect(renderNodeFill);
        }
        if (liquidGlassEffectEnabled != useLiquid) {
            liquidGlassEffectEnabled = useLiquid;
            liquidGlassEffect.setEnabled(useLiquid);
            renderNodeInvalidated = true;
        }
    }

    @Override
    public BlurredBackgroundSource getSource() {
        return source;
    }

    @Override
    protected void onBoundPropsChanged() {
        super.onBoundPropsChanged();

        paintStrokeTop.setStrokeWidth(boundProps.strokeWidthTop);
        paintStrokeBottom.setStrokeWidth(boundProps.strokeWidthBottom);

        outlineRect.set(0, 0,
            boundProps.boundsWithPadding.width(),
            boundProps.boundsWithPadding.height()
        );
        getOutline(outline, outlineRect, boundProps.radii);
        outline.setAlpha(1);

        if (!boundProps.boundsWithPadding.isEmpty()) {
            renderNodeFill.setPosition(0, 0, boundProps.boundsWithPadding.width(), boundProps.boundsWithPadding.height());
            renderNode.setPosition(0, 0, boundProps.boundsWithPadding.width(), boundProps.boundsWithPadding.height());
            renderNode.setOutline(outline);

            renderNodeInvalidated = true;
        }
    }

    @Override
    protected void onSourceOffsetChange(float sourceOffsetX, float sourceOffsetY) {
        super.onSourceOffsetChange(sourceOffsetX, sourceOffsetY);
        renderNodeInvalidated = true;
    }

    public boolean hasDisplayList() {
        return renderNode.hasDisplayList();
    }

    private void updateDisplayList() {
        final float offsetX = sourceOffsetX;
        final float offsetY = sourceOffsetY;

        Canvas c;

        final float sL = boundProps.boundsWithPadding.left + offsetX;
        final float sT = boundProps.boundsWithPadding.top + offsetY;
        final float sR = boundProps.boundsWithPadding.right + offsetX;
        final float sB = boundProps.boundsWithPadding.bottom + offsetY;

        c = renderNodeFill.beginRecording();
        c.save();
        c.translate(-sL, -sT);
        if (liquidGlassEffectEnabled && Build.VERSION.SDK_INT >= 33) {
            final int thickness = Math.max(Math.min(
                boundProps.liquidThickness <= 0 ? dp(14) : boundProps.liquidThickness,
                Math.min(boundProps.boundsWithPadding.width(), boundProps.boundsWithPadding.height()) / 5), 1);

            liquidGlassEffect.update(
                0, 0, boundProps.boundsWithPadding.width(), boundProps.boundsWithPadding.height(),
                boundProps.shaderRadii[0], boundProps.shaderRadii[2], boundProps.shaderRadii[4], boundProps.shaderRadii[6],
                thickness,
                boundProps.liquidIntensity,
                boundProps.liquidIndex,
                backgroundColor
            );
        }
        source.draw(c, sL, sT, sR, sB);
        c.restore();
        renderNodeFill.endRecording();

        c = renderNode.beginRecording();
        final int effectiveBackgroundColor = liquidGlassEffectEnabled && Build.VERSION.SDK_INT >= 33
                ? liquidGlassEffect.getForegroundColor() : backgroundColor;
        if (Color.alpha(effectiveBackgroundColor) == 255) {
            c.drawColor(effectiveBackgroundColor);
        } else {
            c.drawRenderNode(renderNodeFill);
            if (!liquidGlassEffectEnabled && Color.alpha(backgroundColor) != 0) {
                c.drawColor(backgroundColor);
            }
        }
        if (strokeColorTop != 0) {
            drawStroke(c, 0, 0, boundProps.boundsWithPadding.width(),
                    boundProps.boundsWithPadding.height(), boundProps.radii,
                    boundProps.strokeWidthTop, true, paintStrokeTop);
        }
        if (strokeColorBottom != 0) {
            drawStroke(c, 0, 0, boundProps.boundsWithPadding.width(),
                    boundProps.boundsWithPadding.height(), boundProps.radii,
                    boundProps.strokeWidthBottom, false, paintStrokeBottom);
        }
        
        if (strokeColorFull != 0) {
            c.save();
            c.translate(-boundProps.boundsWithPadding.left, -boundProps.boundsWithPadding.top);
            c.drawPath(boundProps.strokePathFull, paintStrokeFull);
            c.restore();
        }
        renderNode.endRecording();
    }

    @Override
    public void updateColors() {
        super.updateColors();

        paintShadow.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy, shadowColor);
        paintStrokeTop.setColor(strokeColorTop);
        paintStrokeBottom.setColor(strokeColorBottom);
        paintStrokeFull.setColor(strokeColorFull);

        renderNodeInvalidated = true;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (boundProps.boundsWithPadding.isEmpty()) {
            return;
        }
        updateGlassSettings(canvas.isHardwareAccelerated());

        if (!canvas.isHardwareAccelerated()) {
            drawSource(canvas, source);
            return;
        }

        if (!renderNode.hasDisplayList() || renderNodeInvalidated) {
            updateDisplayList();
        }
        renderNodeInvalidated = false;

        int color = Theme.multAlpha(shadowColor, renderNode.getAlpha() * shadowAlpha);
        if (Color.alpha(color) != 0) {
            paintShadow.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy, color);
            boundProps.drawShadows(canvas, paintShadow, inAppKeyboardOptimization);
        }

        canvas.save();
        canvas.translate(boundProps.boundsWithPadding.left, boundProps.boundsWithPadding.top);
        canvas.drawRenderNode(renderNode);
        canvas.restore();
    }

    public void invalidateDisplayList() {
        renderNodeInvalidated = true;
    }

    @Override
    public void setAlpha(int alpha) {
        final int oldAlpha = getAlpha();
        if (alpha == oldAlpha) {
            
            return;
        }

        super.setAlpha(alpha);
        renderNode.setAlpha(alpha / 255f);
        if ((oldAlpha == 0) != (alpha == 0)) {
            source.dispatchOnDrawablesRelativePositionChange();
        }
    }

    @Override
    protected void onSourceRelativePositionChanged(RectF position) {
        super.onSourceRelativePositionChanged(position);
        source.dispatchOnDrawablesRelativePositionChange();
    }
}
