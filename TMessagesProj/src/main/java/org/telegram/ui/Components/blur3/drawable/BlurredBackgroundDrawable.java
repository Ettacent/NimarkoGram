/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.blur3.drawable;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.utils.RadiiUtils;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.Blur3HashImpl;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.LiquidGlassEffect;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.utils.NinePatchBuilder;

import org.telegram.utils.glass.GlassDrawable;
import org.telegram.utils.glass.GlassEngine;
import org.telegram.utils.glass.GlassSettings;
import org.telegram.utils.glass.fade.GlassFadeBlurredDrawable;
import org.telegram.utils.glass.source.GlassSource;
import org.telegram.utils.glass.source.GlassSourceBitmap;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceRenderNode;
import org.telegram.utils.glass.source.GlassSourceWrapped;
import java.util.Arrays;

public class BlurredBackgroundDrawable extends GlassDrawable implements Theme.Colorable {
    public static final float DEFAULT_LIQUID_INTENSITY = 0.9f;

    private final GlassSource glassSource;
    private final GlassSource frostedSource;
    private final GlassSource staticSource;
    private GlassSource source;

    public BlurredBackgroundDrawable(@NonNull GlassSource source) {
        this(source, source, source);
    }

    public BlurredBackgroundDrawable(@NonNull GlassSource glassSource, @NonNull GlassSource frostedSource, @NonNull GlassSource staticSource) {
        this.glassSource = glassSource;
        this.frostedSource = frostedSource;
        this.staticSource = staticSource;
        source = pickSource();
        boundProps.strokeWidthTop = dpf2(1);
        boundProps.strokeWidthBottom = dpf2(2 / 3f);

        shadowLayerRadius = dpf2(1);
        shadowLayerDx = 0;
        shadowLayerDy = dpf2(1 / 3f);
    }

    @Override
    public void getBoundsWithPaddingInset(@NonNull Rect rect) {
        rect.set(boundProps.boundsWithPadding);
    }

    private boolean clipToOutline = true;

    public BlurredBackgroundDrawable setClipToOutline(boolean clipToOutline) {
        this.clipToOutline = clipToOutline;
        if (renderNodeImpl != null) {
            renderNodeImpl.setClipToOutline(clipToOutline);
        }
        return this;
    }

    public BlurredBackgroundDrawable setPadding(int padding) {
        if (boundProps.padding != padding) {
            boundProps.padding = padding;
            boundProps.build();

            onBoundPropsChanged();
        }
        return this;
    }

    public BlurredBackgroundDrawable setHasPadding(boolean hasPadding) {
        boundProps.hasPadding = hasPadding;
        return this;
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        padding.set(boundProps.padding, boundProps.padding, boundProps.padding, boundProps.padding);
        return boundProps.hasPadding;
    }

    public BlurredBackgroundDrawable setRadius(float radius) {
        Arrays.fill(boundProps.radii, radius);
        Arrays.fill(boundProps.shaderRadii, radius);
        boundProps.build();

        onBoundPropsChanged();
        return this;
    }

    public BlurredBackgroundDrawable setRadius(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        boundProps.radii[0] = boundProps.radii[1] = topLeft;
        boundProps.radii[2] = boundProps.radii[3] = topRight;
        boundProps.radii[4] = boundProps.radii[5] = bottomRight;
        boundProps.radii[6] = boundProps.radii[7] = bottomLeft;
        System.arraycopy(boundProps.radii, 0, boundProps.shaderRadii, 0, boundProps.radii.length);
        boundProps.build();

        onBoundPropsChanged();
        return this;
    }

    public void setRadius(float topLeft, float topRight, float bottomRight, float bottomLeft, boolean forceBottomZero) {
        boundProps.radii[0] = boundProps.radii[1] = topLeft;
        boundProps.radii[2] = boundProps.radii[3] = topRight;
        boundProps.radii[4] = boundProps.radii[5] = forceBottomZero ? 0 : bottomRight;
        boundProps.radii[6] = boundProps.radii[7] = forceBottomZero ? 0 : bottomLeft;
        boundProps.shaderRadii[0] = boundProps.shaderRadii[1] = topLeft;
        boundProps.shaderRadii[2] = boundProps.shaderRadii[3] = topRight;
        boundProps.shaderRadii[4] = boundProps.shaderRadii[5] = bottomRight;
        boundProps.shaderRadii[6] = boundProps.shaderRadii[7] = bottomLeft;
        boundProps.build();

        onBoundPropsChanged();
    }

    public BlurredBackgroundDrawable setThickness(int thickness) {
        boundProps.liquidThickness = thickness;
        onBoundPropsChanged();
        return this;
    }

    public void setIntensity(float intensity) {
        intensity = Math.max(0f, intensity);
        if (boundProps.liquidIntensity != intensity) {
            boundProps.liquidIntensity = intensity;
            onBoundPropsChanged();
        }
    }

    public float getIntensity() {
        return boundProps.liquidIntensity;
    }

    public void getPositionRelativeSource(RectF position) {
        position.set(boundProps.boundsWithPadding);
        position.offset(getSourceOffsetX(), getSourceOffsetY());
    }

    private void dispatchSourcePositionChange() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if (glassSource instanceof GlassSourceRenderNode) ((GlassSourceRenderNode) glassSource).dispatchOnDrawablesRelativePositionChange();
        if (frostedSource != glassSource && frostedSource instanceof GlassSourceRenderNode) ((GlassSourceRenderNode) frostedSource).dispatchOnDrawablesRelativePositionChange();
    }

    public static final int ARROW_NONE = -1;
    public static final int ARROW_LEFT = 0;
    public static final int ARROW_TOP = 1;
    public static final int ARROW_RIGHT = 2;
    public static final int ARROW_BOTTOM = 3;

    public BlurredBackgroundDrawable setArrow(int side, float position, float height, float tipRadius, float filletRadius) {
        final Props p = boundProps;
        if (p.arrowSide != side || p.arrowPosition != position || p.arrowHeight != height || p.arrowTipRadius != tipRadius || p.arrowFilletRadius != filletRadius) {
            p.arrowSide = side;
            p.arrowPosition = position;
            p.arrowHeight = height;
            p.arrowTipRadius = tipRadius;
            p.arrowFilletRadius = filletRadius;
            p.build();

            onBoundPropsChanged();
            invalidateSelf();
        }
        return this;
    }

    public BlurredBackgroundDrawable clearArrow() {
        return setArrow(ARROW_NONE, 0, 0, 0, 0);
    }
    public Rect getPaddedBounds() {
        return boundProps.boundsWithPadding;
    }

    public Path getPath() {
        return boundProps.path;
    }

    @Override
    protected final void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        boundProps.bounds.set(bounds);
        boundProps.build();

        onBoundPropsChanged();
    }

    protected void onBoundPropsChanged() {
        dispatchSourcePositionChange();
        if (renderNodeImpl != null) {
            renderNodeImpl.onBoundPropsChanged();
        }
    }

    @Override
    protected void onSourceOffsetChange(float sourceOffsetX, float sourceOffsetY) {
        dispatchSourcePositionChange();
        if (renderNodeImpl != null) {
            renderNodeImpl.invalidate();
        }
    }

    public GlassSource getSource() {

        return source;
    }



    /* Colors */

    public GlassSource getUnwrappedSource() {
        return unwrap(source);
    }

    private static GlassSource unwrap(GlassSource source) {
        while (source instanceof GlassSourceWrapped) {
            source = ((GlassSourceWrapped) source).getSource();
        }
        return source;
    }




    private BlurredBackgroundColorProvider colorProvider;
    private int shadowColor, backgroundColor, strokeColorTop, strokeColorBottom, strokeColorFull, outlineShadowColor, outlineColor;
    private float outlineWidth;
    private float defaultShadowRadius = -1, defaultShadowDx, defaultShadowDy;

    public BlurredBackgroundDrawable setColorProvider(BlurredBackgroundColorProvider colorProvider) {
        this.colorProvider = colorProvider;

        if (defaultShadowRadius < 0) {
            defaultShadowRadius = shadowLayerRadius;
            defaultShadowDx = shadowLayerDx;
            defaultShadowDy = shadowLayerDy;
        }
        if (colorProvider instanceof BlurredBackgroundProvider) {
            BlurredBackgroundProvider provider = (BlurredBackgroundProvider) colorProvider;
            setStrokeWidth(provider.getStrokeWidthTop(), provider.getStrokeWidthBottom());
            setShadowParams(provider.getShadowRadius(), provider.getShadowDx(), provider.getShadowDy());
        }
        updateColors();
        return this;
    }
    private int outsetX, outsetY;
    public BlurredBackgroundDrawable setOutset(int dx, int dy) {
        this.outsetX = dx;
        this.outsetY = dy;
        return this;
    }
    public int getOutsetX() {
        return outsetX;
    }
    public int getOutsetY() {
        return outsetY;
    }

    private int themeGeneration = org.telegram.ui.ActionBar.ThemeBind.generation();

    private void checkThemeColors() {
        final int generation = org.telegram.ui.ActionBar.ThemeBind.generation();
        if (themeGeneration != generation) {
            themeGeneration = generation;
            try {
                updateColors();
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
            }
        }
    }

    @Override
    public void updateColors() {
        themeGeneration = org.telegram.ui.ActionBar.ThemeBind.generation();
        if (colorProvider == null) return;
        if (colorProvider instanceof BlurredBackgroundColorProviderThemed) {
            ((BlurredBackgroundColorProviderThemed) colorProvider).updateColors();
        }

        backgroundColor = colorProvider.getBackgroundColor();
        shadowColor = colorProvider.getShadowColor();
        // NG (extera-port "GlareOnElements"): when ON, draw two halo halves
        // (top brighter, bottom subtler) like Liquid Glass. When OFF, fall
        // back to a single uniform `strokeColorFull` stroke (extera parity —
        // their `useFullStroke` branch). Zero `strokeColorTop/Bottom` so the
        // halo halves are skipped in drawStrokeInternalIfNeeded.
        if (app.nimarkogram.messenger.AppConfig.glareOnElements) {
            strokeColorTop = colorProvider.getStrokeColorTop();
            strokeColorBottom = colorProvider.getStrokeColorBottom();
            strokeColorFull = 0;
        } else {
            strokeColorTop = strokeColorBottom = 0;
            strokeColorFull = colorProvider.getStrokeColorFull();
        }
        outlineShadowColor = colorProvider.getOutlineShadowColor();
        outlineColor = colorProvider.getOutlineColor();
        outlineWidth = colorProvider.getOutlineWidth();
        if (colorProvider instanceof BlurredBackgroundProvider) {
            final BlurredBackgroundProvider provider = (BlurredBackgroundProvider) colorProvider;
            setShadowParams(provider.getShadowRadius(), provider.getShadowDx(), provider.getShadowDy());
        } else if (colorProvider.getShadowRadiusOverride() >= 0) {
            setShadowParams(colorProvider.getShadowRadiusOverride(), 0, colorProvider.getShadowDyOverride());
        } else if (defaultShadowRadius >= 0) {
            setShadowParams(defaultShadowRadius, defaultShadowDx, defaultShadowDy);
        }
        rimFromColors = null;
        if (renderNodeImpl != null) {
            renderNodeImpl.onColorsChanged();
        }
            invalidateSelf();
        }
    private final GlassRimRenderer rimRenderer = new GlassRimRenderer();
    @Nullable private GlassRim rim;
    @Nullable private GlassRim rimFromColors;
    private float lightAngle = GlassRim.LIGHT_TOP;

    public BlurredBackgroundDrawable setRim(@Nullable GlassRim rim) {
        this.rim = rim;
        invalidateSelf();
        return this;
    }

    public BlurredBackgroundDrawable setLightAngle(float lightAngle) {
        if (this.lightAngle != lightAngle) {
            this.lightAngle = lightAngle;
            rimFromColors = null;
            invalidateSelf();
        }
        return this;
    }

    private boolean evenRim;

    public BlurredBackgroundDrawable setEvenRim(boolean evenRim) {
        if (this.evenRim != evenRim) {
            this.evenRim = evenRim;
            rimFromColors = null;
            invalidateSelf();
        }
        return this;
    }

    private GlassRim getRim() {
        if (rim != null && app.nimarkogram.messenger.AppConfig.glareOnElements) {
            return rim;
        }
        if (!app.nimarkogram.messenger.AppConfig.glareOnElements) {
            if (rimFromColors == null) {
                rimFromColors = GlassRim.evenOf(boundProps.strokeWidthTop, strokeColorFull,
                    boundProps.strokeWidthTop, strokeColorFull, outlineWidth, outlineColor);
            }
            return rimFromColors;
        }
        if (rimFromColors == null) {
            rimFromColors = evenRim
                ? GlassRim.evenOf(
                    boundProps.strokeWidthTop, strokeColorTop,
                    boundProps.strokeWidthBottom, strokeColorBottom,
                    outlineWidth, outlineColor)
                : GlassRim.of(lightAngle,
                    boundProps.strokeWidthTop, strokeColorTop,
                    boundProps.strokeWidthBottom, strokeColorBottom,
                    outlineWidth, outlineColor);
        }
        return rimFromColors;
    }

    private void drawRim(Canvas canvas, float alpha) {
        final Rect r = boundProps.boundsWithPadding;
        if (boundProps.hasArrow()) {
            rimRenderer.drawPath(canvas, boundProps.path, boundProps.shapeKey, r.left, r.top, r.right, r.bottom, getRim(), alpha);
        } else {
            rimRenderer.draw(canvas, r.left, r.top, r.right, r.bottom, boundProps.radii, getRim(), alpha);
        }
    }



    /* Bound Props */

    private final Props boundProps = new Props();

    private static class Props {
        final Rect bounds = new Rect();
        final float[] radii = new float[8];
        final float[] shaderRadii = new float[8];
        int padding;
        boolean hasPadding;

        int liquidThickness;
        float liquidIntensity = DEFAULT_LIQUID_INTENSITY;
        float liquidIndex = 1.5f;

        float strokeWidthTop;
        float strokeWidthBottom;

        final Path path = new Path();
        boolean radiiAreSame = true;
        final Rect boundsWithPadding = new Rect();

        int arrowSide = ARROW_NONE;
        float arrowPosition, arrowHeight, arrowTipRadius, arrowFilletRadius;
        final RectF body = new RectF();
        float arrowAxis, arrowHalfBase;
        float arrowFreeStart, arrowFreeEnd;
        long shapeKey;
        private final Matrix arrowMatrix = new Matrix();
        private final RectF arrowOval = new RectF();
        private final float[] arrowRadii = new float[4];

        boolean hasArrow() {
            return arrowSide != ARROW_NONE && arrowHeight > 0;
        }

        void build() {
            radiiAreSame = RadiiUtils.radiiAreSame(radii);

            boundsWithPadding.set(bounds);
            boundsWithPadding.inset(padding, padding);

            body.set(boundsWithPadding);
            path.rewind();
            if (hasArrow()) {
                buildArrowPath();
            } else {
            path.addRoundRect(
                boundsWithPadding.left,
                boundsWithPadding.top,
                boundsWithPadding.right,
                boundsWithPadding.bottom,
                radii, Path.Direction.CW);
            }
            path.close();
        }

        private void buildArrowPath() {
            final boolean vertical = arrowSide == ARROW_LEFT || arrowSide == ARROW_RIGHT;
            switch (arrowSide) {
                case ARROW_LEFT: body.left += arrowHeight; break;
                case ARROW_TOP: body.top += arrowHeight; break;
                case ARROW_RIGHT: body.right -= arrowHeight; break;
                default: body.bottom -= arrowHeight; break;
            }
            final float w = vertical ? body.height() : body.width();
            final float h = vertical ? body.width() : body.height();
            final int first;
            switch (arrowSide) {
                case ARROW_LEFT: first = 3; break;
                case ARROW_TOP: first = 0; break;
                case ARROW_RIGHT: first = 1; break;
                default: first = 2; break;
            }
            final float maxRadius = Math.max(0, Math.min(w, h) / 2f);
            for (int i = 0; i < 4; i++) {
                arrowRadii[i] = Math.min(radii[((first + i) % 4) * 2], maxRadius);
            }
            final float rTL = arrowRadii[0], rTR = arrowRadii[1], rBR = arrowRadii[2], rBL = arrowRadii[3];
            final float ah = arrowHeight;
            final float rt = Math.min(arrowTipRadius, ah);
            final float rf = arrowFilletRadius;
            final float S = (float) Math.sqrt(0.5);
            final float SQ2m1 = (float) Math.sqrt(2) - 1;
            arrowHalfBase = ah + (rt + rf) * SQ2m1;

            float ax;
            switch (arrowSide) {
                case ARROW_LEFT: ax = body.bottom - arrowPosition; break;
                case ARROW_TOP: ax = arrowPosition - body.left; break;
                case ARROW_RIGHT: ax = arrowPosition - body.top; break;
                default: ax = body.right - arrowPosition; break;
            }
            final float minAx = rTL + arrowHalfBase, maxAx = w - rTR - arrowHalfBase;
            ax = minAx <= maxAx ? MathUtils.clamp(ax, minAx, maxAx) : w / 2f;
            arrowAxis = ax;
            arrowFreeStart = ax - rTL;
            arrowFreeEnd = w - rTR - ax;

            path.moveTo(rTL, 0);
            final float cf = arrowHalfBase;
            arrowOval.set(ax - cf - rf, -2 * rf, ax - cf + rf, 0);
            path.arcTo(arrowOval, 90, -45, false);
            arrowOval.set(ax - rt, -ah, ax + rt, -ah + 2 * rt);
            path.arcTo(arrowOval, 225, 90, false);
            arrowOval.set(ax + cf - rf, -2 * rf, ax + cf + rf, 0);
            path.arcTo(arrowOval, 135, -45, false);
            corner(w - rTR * 2, 0, rTR, 270);
            corner(w - rBR * 2, h - rBR * 2, rBR, 0);
            corner(0, h - rBL * 2, rBL, 90);
            corner(0, 0, rTL, 180);

            switch (arrowSide) {
                case ARROW_LEFT: arrowMatrix.setRotate(-90); arrowMatrix.postTranslate(body.left, body.bottom); break;
                case ARROW_TOP: arrowMatrix.setTranslate(body.left, body.top); break;
                case ARROW_RIGHT: arrowMatrix.setRotate(90); arrowMatrix.postTranslate(body.right, body.top); break;
                default: arrowMatrix.setRotate(180); arrowMatrix.postTranslate(body.right, body.bottom); break;
            }
            path.transform(arrowMatrix);

            long k = 0xcbf29ce484222325L;
            k = mix(k, arrowSide);
            k = mix(k, Math.round(w * 4));
            k = mix(k, Math.round(h * 4));
            k = mix(k, Math.round(ax * 4));
            k = mix(k, Math.round(ah * 4));
            k = mix(k, Math.round(rt * 4));
            k = mix(k, Math.round(rf * 4));
            for (float r : arrowRadii) {
                k = mix(k, Math.round(r * 4));
            }
            k = mix(k, Math.round((body.left - boundsWithPadding.left) * 4));
            k = mix(k, Math.round((body.top - boundsWithPadding.top) * 4));
            shapeKey = k;
        }

        private void corner(float left, float top, float r, float startAngle) {
            if (r <= 0) {
                path.lineTo(left, top);
                return;
            }
            arrowOval.set(left, top, left + r * 2, top + r * 2);
            path.arcTo(arrowOval, startAngle, 90, false);
        }

        private static long mix(long k, int v) {
            k ^= v;
            return k * 0x100000001b3L;
        }

        float arrowDirX() {
            return arrowSide == ARROW_LEFT ? -1 : arrowSide == ARROW_RIGHT ? 1 : 0;
        }

        float arrowDirY() {
            return arrowSide == ARROW_TOP ? -1 : arrowSide == ARROW_BOTTOM ? 1 : 0;
        }

        float arrowAxisX() {
            switch (arrowSide) {
                case ARROW_LEFT: return body.left;
                case ARROW_RIGHT: return body.right;
                case ARROW_TOP: return body.left + arrowAxis;
                default: return body.right - arrowAxis;
            }
        }

        float arrowAxisY() {
            switch (arrowSide) {
                case ARROW_TOP: return body.top;
                case ARROW_BOTTOM: return body.bottom;
                case ARROW_RIGHT: return body.top + arrowAxis;
                default: return body.bottom - arrowAxis;
            }
        }

        void drawShadows(Canvas canvas, Paint paint, boolean useInAppKeyboardOptimization) {
            if (useInAppKeyboardOptimization && !hasArrow()) {
                final float bottom = MathUtils.clamp(boundsWithPadding.top + radii[0] * 2,
                        boundsWithPadding.top, boundsWithPadding.bottom);

                canvas.save();
                canvas.clipRect(bounds.left, bounds.top, bounds.right, bottom);
                canvas.drawRoundRect(boundsWithPadding.left, boundsWithPadding.top,
                        boundsWithPadding.right, bottom, radii[0], radii[0], paint);
                canvas.restore();
            } else {
                draw(canvas, paint);
            }
        }

        void draw(Canvas canvas, Paint paint) {
            if (radiiAreSame && !hasArrow()) {
                canvas.drawRoundRect(
                    boundsWithPadding.left,
                    boundsWithPadding.top,
                    boundsWithPadding.right,
                    boundsWithPadding.bottom,
                    radii[0], radii[0], paint);
            } else {
                canvas.drawPath(path, paint);
            }
        }
    }



    /* Outline */

    private ViewOutlineProvider viewOutlineProvider;

    public ViewOutlineProvider getViewOutlineProvider() {
        if (viewOutlineProvider == null) {
            viewOutlineProvider = new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    BlurredBackgroundDrawable.this.getOutline(outline);
                }
            };
        }
        return viewOutlineProvider;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        if (boundProps.hasArrow()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                outline.setPath(boundProps.path);
            } else {
                final RectF body = boundProps.body;
                outline.setRoundRect((int) body.left, (int) body.top, (int) body.right, (int) body.bottom, boundProps.radii[0]);
            }
            return;
        }
                    BlurredBackgroundDrawable.getOutline(outline, boundProps.boundsWithPadding, boundProps.radii);
                }

    private static Path tmpPath = new Path();
    private static void getOutline(Outline outline, Rect rect, float[] radii) {
        final boolean radiiAreSame = RadiiUtils.radiiAreSame(radii);

        if (radiiAreSame) {
            outline.setRoundRect(rect, Math.min(radii[0], Math.min(rect.width(), rect.height()) / 2f));
        } else {
            if (tmpPath == null) {
                tmpPath = new Path();
            } else {
                tmpPath.rewind();
            }
            tmpPath.addRoundRect(
                rect.left, rect.top,
                rect.right, rect.bottom,
                radii, Path.Direction.CW
            );
            outline.setConvexPath(tmpPath);
        }
    }


    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }



    private boolean inAppKeyboardOptimization;
    public void enableInAppKeyboardOptimization() {
        inAppKeyboardOptimization = true;
    }


    private float shadowLayerRadius;
    private float shadowLayerDx;
    private float shadowLayerDy;
    private float shadowAlpha = 1.0f;

    public void setShadowParams(float radius, float dx, float dy) {
        shadowLayerRadius = radius;
        shadowLayerDx = dx;
        shadowLayerDy = dy;
    }

    public void setShadowAlpha(float alpha) {
        if (shadowAlpha != alpha) {
            shadowAlpha = alpha;
            invalidateSelf();
        }
    }

    public void setStrokeWidth(float strokeWidthTop, float strokeWidthBottom) {
        if (boundProps.strokeWidthTop != strokeWidthTop || boundProps.strokeWidthBottom != strokeWidthBottom) {
            boundProps.strokeWidthTop = strokeWidthTop;
            boundProps.strokeWidthBottom = strokeWidthBottom;
            rimFromColors = null;
            invalidateSelf();
        }
    }



    /* Universal */

    private boolean liquidGlassEffectAllowed;
    private int glassSettingsGeneration = GlassEngine.getGlassSettingsGenerationId();

    public void setLiquidGlassEffectAllowed() {
        if (!liquidGlassEffectAllowed) {
            liquidGlassEffectAllowed = true;
            implInvalidated = true;
        }
    }

    private boolean isLiquidGlassEffectEnabled() {
        return liquidGlassEffectAllowed && getGlassSettings().isLiquidGlassEnabled && Build.VERSION.SDK_INT >= 33;
    }

    private GlassSource pickSource() {
        if (!getGlassSettings().isBlurEnabled) {
            return staticSource;
        }
        return isLiquidGlassEffectEnabled() ? glassSource : frostedSource;
    }

    @Override
    protected void onGlassSettingsChanged(GlassSettings glassSettings) {
        implInvalidated = true;
        updateColors();
        invalidateSelf();
    }

    private boolean lastGlare = app.nimarkogram.messenger.AppConfig.glareOnElements;
    private boolean lastEnhanced = app.nimarkogram.messenger.AppConfig.enhancedGlassBlur;

    private void checkGlassSettings() {
        if (lastGlare != app.nimarkogram.messenger.AppConfig.glareOnElements
                || lastEnhanced != app.nimarkogram.messenger.AppConfig.enhancedGlassBlur) {
            lastGlare = app.nimarkogram.messenger.AppConfig.glareOnElements;
            lastEnhanced = app.nimarkogram.messenger.AppConfig.enhancedGlassBlur;
            implInvalidated = true;
            updateColors();
        }
        final int generation = GlassEngine.getGlassSettingsGenerationId();
        if (glassSettingsGeneration != generation) {
            glassSettingsGeneration = generation;
            setGlassSettings(GlassEngine.getCurrentGlassSettings());
        }
    }




    @Override
    public boolean shouldHaveDisplayList() {
        return resolveImpl() == renderNodeImpl && renderNodeImpl != null;
    }

    @Override
    public void buildDisplayList() {
        updateDisplayList();
    }

    @Override
    public boolean hasDisplayList() {
        return resolveImpl() != renderNodeImpl || renderNodeImpl == null || renderNodeImpl.hasDisplayList();
    }

    public void updateDisplayList() {
        if (resolveImpl() == renderNodeImpl && renderNodeImpl != null) {
            renderNodeImpl.record();
        }
    }

    public void invalidateDisplayList() {
        if (renderNodeImpl != null) {
            renderNodeImpl.invalidate();
        }
    }

    @Override
    protected void onAlphaChanged(int oldAlpha, int alpha) {
        if ((oldAlpha == 0) != (alpha == 0)) dispatchSourcePositionChange();
        invalidateSelf();
        if (renderNodeImpl != null) {
            renderNodeImpl.setAlpha(alpha / 255f);
        }
    }




    @Override
    public void draw(@NonNull Canvas canvas) {
        checkThemeColors();
        checkGlassSettings();
        if (getAlpha() == 0 || boundProps.boundsWithPadding.isEmpty()) {
            return;
        }

        Impl impl = resolveImpl();
        GlassSource drawn = source;
        if (impl == renderNodeImpl && !canvas.isHardwareAccelerated()) {
            drawn = softwareSource(getUnwrappedSource());
            impl = directImpl(drawn);
        }
        if (impl == renderNodeImpl) {
            impl.draw(canvas, drawn);
            drawRim(canvas, getAlpha() / 255f);
        } else {
            colorImpl.drawShadow(canvas);
            final Rect b = boundProps.boundsWithPadding;
            final float rimInset = Math.max(outlineWidth, rim == null ? 0 : rim.ringWidth);
            final int layer = getAlpha() == 255 ? -1 : canvas.saveLayerAlpha(
                b.left - rimInset, b.top - rimInset, b.right + rimInset, b.bottom + rimInset, getAlpha());
            impl.draw(canvas, drawn);
            drawRim(canvas, 1f);
            if (layer != -1) canvas.restoreToCount(layer);
        }
    }

    private static GlassSource softwareSource(GlassSource source) {
        source = unwrap(source);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && source instanceof GlassSourceRenderNode) {
            final GlassSource fallback = ((GlassSourceRenderNode) source).getFallbackSource();
            return fallback != null ? unwrap(fallback) : source;
        }
        return source;
    }

    private Impl impl;
    private boolean implInvalidated = true;
    private GlassSource resolvedFor;
    private boolean resolvedOpaque;

    private final ImplColor colorImpl = new ImplColor();
    private ImplBitmap bitmapImpl;
    private ImplCanvas canvasImpl;
    private Impl renderNodeImpl;

    private Impl resolveImpl() {
        if (implInvalidated) {
            final GlassSource picked = pickSource();
            if (source != picked) {
                source = picked;
                invalidateDisplayList();
            }
        }
        final GlassSource unwrapped = getUnwrappedSource();
        final boolean opaque = Color.alpha(backgroundColor) == 255;
        if (impl == null || implInvalidated || resolvedFor != unwrapped || resolvedOpaque != opaque) {
            implInvalidated = false;
            resolvedFor = unwrapped;
            resolvedOpaque = opaque;

            final Impl newImpl;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && source instanceof BlurredBackgroundDrawableViewFactory.DrawableOwner
                    && !(unwrapped instanceof GlassSourceColor) && !(unwrapped instanceof GlassSourceBitmap)) {
                if (renderNodeImpl == null) {
                    renderNodeImpl = createRenderNodeImpl();
                }
                newImpl = renderNodeImpl;
            } else {
                newImpl = directImpl(unwrapped);
            }
            if (renderNodeImpl != null) {
                renderNodeImpl.setLiquidGlassEnabled(newImpl == renderNodeImpl && isLiquidGlassEffectEnabled());
            }
            if (impl != newImpl && impl != null) {
                impl.discard();
            }
            impl = newImpl;
        }
        return impl;
    }

    private Impl directImpl(GlassSource source) {
        source = unwrap(source);
        if (source == null || Color.alpha(backgroundColor) == 255 || source instanceof GlassSourceColor) {
            return colorImpl;
        }
        if (source instanceof GlassSourceBitmap) {
            if (bitmapImpl == null) {
                bitmapImpl = new ImplBitmap();
            }
            return bitmapImpl;
        }
        if (canvasImpl == null) {
            canvasImpl = new ImplCanvas();
        }
        return canvasImpl;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private Impl createRenderNodeImpl() {
        final Impl impl = new ImplRenderNode();
        impl.setClipToOutline(clipToOutline);
        impl.setAlpha(getAlpha() / 255f);
        impl.onBoundPropsChanged();
        return impl;
    }

    private abstract static class Impl {
        abstract void draw(Canvas canvas, GlassSource source);

        void discard() {

        }


        void setClipToOutline(boolean clipToOutline) {

        }

        void setAlpha(float alpha) {

        }

        void invalidate() {

        }

        boolean hasDisplayList() {
            return true;
        }

        void record() {

        }

        void setLiquidGlassEnabled(boolean enabled) {

        }

        void onBoundPropsChanged() {

        }

        void onColorsChanged() {

        }
    }

    private final class ImplColor extends Impl {
        private final Blur3HashImpl hashBuilder = new Blur3HashImpl();
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect ninePatchPadding = new Rect();
        private NinePatchDrawable ninePatch;
        private long ninePatchHash;
        private final Bitmap[] ninePatchRef = new Bitmap[1];

        @Override
        void draw(Canvas canvas, GlassSource source) {
            final GlassSource unwrapped = unwrap(source);
            final int sourceColor = Color.alpha(backgroundColor) != 255 && unwrapped instanceof GlassSourceColor
                ? ((GlassSourceColor) unwrapped).getColor() : 0;
            drawFill(canvas, ColorUtils.compositeColors(backgroundColor, sourceColor));
        }

        void drawFill(Canvas canvas, int fillColor) {
            if (Color.alpha(fillColor) > 0) {
                arrowPaint.clearShadowLayer();
                arrowPaint.setColor(fillColor);
                boundProps.draw(canvas, arrowPaint);
            }
        }

        void drawShadow(Canvas canvas) {
            final int shadowDrawAlpha = MathUtils.clamp(Math.round(getAlpha() * shadowAlpha), 0, 255);
            if (shadowDrawAlpha == 0 || Color.alpha(shadowColor) == 0 || shadowLayerRadius <= 0) return;
            if (boundProps.hasArrow()) {
                arrowPaint.setColor(0);
                arrowPaint.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy,
                    Theme.multAlpha(shadowColor, shadowDrawAlpha / 255f));
                canvas.drawPath(boundProps.path, arrowPaint);
                arrowPaint.clearShadowLayer();
            return;
        }

            final NinePatchDrawable shadow = check(0);
            final Rect b = boundProps.boundsWithPadding;
            shadow.setBounds(b.left - ninePatchPadding.left, b.top - ninePatchPadding.top,
                b.right + ninePatchPadding.right, b.bottom + ninePatchPadding.bottom);
            shadow.setAlpha(shadowDrawAlpha);
            shadow.draw(canvas);
        }

        NinePatchDrawable check(int fillColor) {
            hashBuilder.start();
            hashBuilder.add(fillColor);
            hashBuilder.add(shadowColor);
            hashBuilder.add(boundProps.radii);
            hashBuilder.addF(shadowLayerRadius);
            hashBuilder.addF(shadowLayerDx);
            hashBuilder.addF(shadowLayerDy);
            final long hash = hashBuilder.get();

            if (ninePatch == null || ninePatchHash != hash) {
                ninePatchHash = hash;
                final int colorHint = Color.alpha(fillColor) == 255 ? fillColor : NinePatchBuilder.NO_COLOR;
                ninePatch = NinePatchBuilder.createNinePatch(
                    ninePatchRef,
                    fillColor,
                    boundProps.radii,
                    shadowLayerRadius,
                    shadowColor,
                    shadowLayerDx,
                    shadowLayerDy,
                    colorHint
                );
                ninePatch.getPadding(ninePatchPadding);
            }
            return ninePatch;
        }
    }

    private final class ImplBitmap extends Impl {
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix shaderMatrix = new Matrix();
    /*
     * BitmapShader already holds its Bitmap strongly, so a WeakReference does not
     * reduce the lifetime of the bitmap here. More importantly, the old final
     * WeakReference was created with null and was never updated: every draw looked
     * like a bitmap change and allocated a fresh native BitmapShader. Keep the
     * identity explicitly and rebuild only when the source really changes.
     */
    private @Nullable Bitmap bitmapInShader;
    private @Nullable BitmapShader bitmapShader;

    {
            bitmapPaint.setFilterBitmap(true);
        }

        @Override
        void draw(Canvas canvas, GlassSource s) {
            final GlassSourceBitmap source = (GlassSourceBitmap) unwrap(s);
            final int alpha = 255;
        final Bitmap newBitmap = source.getBitmap();

        if (newBitmap == null || newBitmap.isRecycled()) {
            if (bitmapInShader != null || bitmapShader != null) {
                bitmapInShader = null;
                bitmapShader = null;
                    bitmapPaint.setShader(null);
            }
        } else if (newBitmap != bitmapInShader) {
            bitmapInShader = newBitmap;
            bitmapShader = new BitmapShader(newBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bitmapPaint.setShader(bitmapShader);
            }

        if (bitmapShader != null && newBitmap != null && !newBitmap.isRecycled() && alpha > 0) {
                shaderMatrix.set(source.getMatrix());
                shaderMatrix.postTranslate(-getSourceOffsetX(), -getSourceOffsetY());
                bitmapShader.setLocalMatrix(shaderMatrix);
                bitmapPaint.setAlpha(alpha);
                boundProps.draw(canvas, bitmapPaint);
            }

            final int backgroundColor = BlurredBackgroundDrawable.this.backgroundColor;

        if (Color.alpha(backgroundColor) > 0) {
                fillPaint.setColor(backgroundColor);
                boundProps.draw(canvas, fillPaint);
            }
        }
    }

    private final class ImplCanvas extends Impl {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        {
            shadowPaint.setColor(0);
        }

        @Override
        void draw(Canvas canvas, GlassSource source) {
            final int alpha = 255;
            if (alpha == 0) {
            return;
        }

            final int backgroundColor = BlurredBackgroundDrawable.this.backgroundColor;

            final Rect b = boundProps.boundsWithPadding;
            final float sL = b.left + getSourceOffsetX();
            final float sT = b.top + getSourceOffsetY();
            final float sR = b.right + getSourceOffsetX();
            final float sB = b.bottom + getSourceOffsetY();

            final boolean needSaveLayer = alpha != 255;
            if (needSaveLayer) {
                canvas.saveLayerAlpha(b.left, b.top, b.right, b.bottom, alpha);
            }

            canvas.save();
            canvas.clipPath(boundProps.path);
            canvas.translate(b.left, b.top);
            canvas.translate(-sL, -sT);
            source.draw(canvas, sL, sT, sR, sB);
            canvas.restore();

            if (Color.alpha(backgroundColor) > 0) {
                fillPaint.setColor(backgroundColor);
                boundProps.draw(canvas, fillPaint);
            }

            if (needSaveLayer) {
                canvas.restore();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private final class ImplRenderNode extends Impl {
        private final float OUTLINE_SHADOW_RADIUS = dpf2(0.55f);

        private final Outline outline = new Outline();
        private final Rect outlineRect = new Rect();
        private final RenderNode renderNode = new RenderNode("BlurredNode");
        private final RenderNode renderNodeFill = new RenderNode("BlurredFill");
        private final Paint paintShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private LiquidGlassEffect liquidGlassEffect;
        private boolean liquidGlassEnabled;
        private boolean invalidated = true;
        private boolean clipToOutline = true;
        private final RectF sourceBounds = new RectF();
        private boolean shadowPaintConfigured;
        private int lastShadowColor;
        private float lastShadowRadius, lastShadowDx, lastShadowDy;

        private void updateShadowPaint(int color) {
            if (shadowPaintConfigured && lastShadowColor == color && lastShadowRadius == shadowLayerRadius
                    && lastShadowDx == shadowLayerDx && lastShadowDy == shadowLayerDy) return;
            paintShadow.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy, color);
            shadowPaintConfigured = true;
            lastShadowColor = color;
            lastShadowRadius = shadowLayerRadius;
            lastShadowDx = shadowLayerDx;
            lastShadowDy = shadowLayerDy;
        }

        private int getLensDownsample() {
            return 1;
        }

        ImplRenderNode() {
            renderNode.setClipToOutline(true);
            renderNode.setClipToBounds(true);
            paintShadow.setColor(0);
        }

        @Override
        void setClipToOutline(boolean clipToOutline) {
            this.clipToOutline = clipToOutline;
            renderNode.setClipToOutline(clipToOutline && !boundProps.hasArrow());
        }

        @Override
        void setAlpha(float alpha) {
            renderNode.setAlpha(alpha);
        }

        @Override
        void invalidate() {
            invalidated = true;
        }

        @Override
        boolean hasDisplayList() {
            return renderNode.hasDisplayList() && renderNodeFill.hasDisplayList();
        }

        @Override
        void setLiquidGlassEnabled(boolean enabled) {
            if (liquidGlassEnabled != enabled) {
                liquidGlassEnabled = enabled;
                invalidated = true;
            }
        }

        @Override
        void onBoundPropsChanged() {
            final Rect b = boundProps.boundsWithPadding;
            outlineRect.set(0, 0, b.width(), b.height());
            getOutline(outline, outlineRect, boundProps.radii);
            outline.setAlpha(1);

            if (!b.isEmpty()) {
                renderNodeFill.setPosition(0, 0, b.width(), b.height());
                renderNode.setPosition(0, 0, b.width(), b.height());
                renderNode.setOutline(outline);
                renderNode.setClipToOutline(clipToOutline && !boundProps.hasArrow());
                invalidated = true;
            }
        }

        @Override
        void onColorsChanged() {
            invalidated = true;
        }

        @Override
        void record() {
            final Rect b = boundProps.boundsWithPadding;
            if (b.isEmpty()) return;
            invalidated = false;
            if (Build.VERSION.SDK_INT >= 33) {
                if (liquidGlassEnabled && liquidGlassEffect == null) liquidGlassEffect = new LiquidGlassEffect(renderNodeFill);
                if (liquidGlassEffect != null) liquidGlassEffect.setEnabled(liquidGlassEnabled);
            }
            final float sL = b.left + getSourceOffsetX();
            final float sT = b.top + getSourceOffsetY();
            final float sR = b.right + getSourceOffsetX();
            final float sB = b.bottom + getSourceOffsetY();

            final int downsample = getLensDownsample();
            final float originX, originY;
            if (downsample > 1) {
                final GlassFadeBlurredDrawable.GlassFadeDownsampledSource downsampled = (GlassFadeBlurredDrawable.GlassFadeDownsampledSource) source;
                originX = GlassFadeBlurredDrawable.alignDownsampleOrigin(sL, downsampled.getDownsampleOffsetX(), downsample) - downsample;
                originY = GlassFadeBlurredDrawable.alignDownsampleOrigin(sT, downsampled.getDownsampleOffsetY(), downsample) - downsample;
                renderNodeFill.setPosition(0, 0,
                    (int) Math.ceil((sR - originX) / downsample) + 1,
                    (int) Math.ceil((sB - originY) / downsample) + 1);
            } else {
                originX = sL;
                originY = sT;
                renderNodeFill.setPosition(0, 0, b.width(), b.height());
            }
            renderNodeFill.setPivotX(0f);
            renderNodeFill.setPivotY(0f);
            renderNodeFill.setScaleX(downsample);
            renderNodeFill.setScaleY(downsample);
            renderNodeFill.setTranslationX(originX - sL);
            renderNodeFill.setTranslationY(originY - sT);

            if (liquidGlassEnabled && liquidGlassEffect != null && Build.VERSION.SDK_INT >= 33) {
                final RectF body = boundProps.body;
                final int thickness = Math.max(Math.min(
                    boundProps.liquidThickness <= 0 ? dp(app.nimarkogram.messenger.AppConfig.enhancedGlassBlur ? 14 : 11) : boundProps.liquidThickness,
                    (int) (Math.min(body.width(), body.height()) / 5)), 1);

                if (boundProps.hasArrow()) {
                    final float stripe = Math.max(boundProps.arrowHalfBase, Math.min(boundProps.arrowHalfBase + thickness + 1,
                        Math.min(boundProps.arrowFreeStart, boundProps.arrowFreeEnd)));
                    liquidGlassEffect.setArrow(
                        boundProps.arrowAxisX() - body.centerX(), boundProps.arrowAxisY() - body.centerY(),
                        boundProps.arrowDirX(), boundProps.arrowDirY(),
                        boundProps.arrowHeight, Math.min(boundProps.arrowTipRadius, boundProps.arrowHeight), boundProps.arrowFilletRadius,
                        stripe);
                } else {
                    liquidGlassEffect.clearArrow();
                }
                liquidGlassEffect.update(
                    body.width(), body.height(),
                    body.left - b.left, body.top - b.top,
                    boundProps.shaderRadii[0], boundProps.shaderRadii[2], boundProps.shaderRadii[4], boundProps.shaderRadii[6],
                    thickness,
                    boundProps.liquidIntensity,
                    boundProps.liquidIndex,
                    backgroundColor,
                    originX - sL, originY - sT,
                    downsample
            );

            }

            Canvas c = renderNodeFill.beginRecording();
            if (downsample > 1) {
                sourceBounds.set(sL, sT, sR, sB);
                ((GlassFadeBlurredDrawable.GlassFadeDownsampledSource) source).drawDownsampled(c, sourceBounds, downsample, originX, originY);
            } else {
                c.save();
                c.translate(-sL, -sT);
                source.draw(c, sL, sT, sR, sB);
                c.restore();
            }
            renderNodeFill.endRecording();

            c = renderNode.beginRecording();
            if (Color.alpha(backgroundColor) == 255) {
                c.drawColor(backgroundColor);
            } else {
                c.drawRenderNode(renderNodeFill);
                if (!liquidGlassEnabled && Color.alpha(backgroundColor) != 0) {
                    c.drawColor(backgroundColor);
                }
            }
            renderNode.endRecording();
        }

        @Override
        void draw(Canvas canvas, GlassSource source) {
            if (!renderNode.hasDisplayList() || !renderNodeFill.hasDisplayList() || invalidated) {
                record();
            }

            final int color = Theme.multAlpha(shadowColor, renderNode.getAlpha() * shadowAlpha);
            if (Color.alpha(color) != 0) {
                updateShadowPaint(color);
                boundProps.drawShadows(canvas, paintShadow, inAppKeyboardOptimization);
            }
            final int outlineShadow = Theme.multAlpha(outlineShadowColor, renderNode.getAlpha() * shadowAlpha);
            if (Color.alpha(outlineShadow) != 0) {
                paintShadow.setShadowLayer(OUTLINE_SHADOW_RADIUS, 0, 0, outlineShadow);
                shadowPaintConfigured = false;
                boundProps.drawShadows(canvas, paintShadow, inAppKeyboardOptimization);
            }

            final Rect b = boundProps.boundsWithPadding;
            canvas.save();
            if (boundProps.hasArrow()) {
                canvas.clipPath(boundProps.path);
            }
            canvas.translate(b.left, b.top);
            canvas.drawRenderNode(renderNode);
            canvas.restore();
        }

        @Override
        void discard() {
            renderNode.discardDisplayList();
            renderNodeFill.discardDisplayList();
            invalidated = true;
        }
    }
}
