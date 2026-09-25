package org.telegram.ui.Components.blur3.drawable;

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
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.utils.RadiiUtils;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.Blur3HashImpl;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceWrapped;
import org.telegram.ui.Components.blur3.utils.NinePatchBuilder;

import java.util.Arrays;

public abstract class BlurredBackgroundDrawable extends Drawable {
    public static final float DEFAULT_LIQUID_INTENSITY = 0.9f;

    public BlurredBackgroundDrawable() {
        boundProps.strokeWidthTop = dpf2(1);
        boundProps.strokeWidthBottom = dpf2(2 / 3f);

        shadowLayerRadius = dpf2(1);
        shadowLayerDx = 0;
        shadowLayerDy = dpf2(1 / 3f);
    }

    protected float sourceOffsetX;
    protected float sourceOffsetY;

    public void setSourceOffset(float sourceOffsetX, float sourceOffsetY) {
        if (this.sourceOffsetX != sourceOffsetX || this.sourceOffsetY != sourceOffsetY) {
            this.sourceOffsetX = sourceOffsetX;
            this.sourceOffsetY = sourceOffsetY;
            onSourceOffsetChange(sourceOffsetX, sourceOffsetY);
        }
    }

    public float getSourceOffsetX() {
        return sourceOffsetX;
    }

    public float getSourceOffsetY() {
        return sourceOffsetY;
    }

    public BlurredBackgroundDrawable setClipToOutline(boolean clipToOutline) {
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

    @CallSuper
    protected void onBoundPropsChanged() {
        dispatchSourceRelativePositionChange();
    }

    @CallSuper
    protected void onSourceOffsetChange(float sourceOffsetX, float sourceOffsetY) {
        dispatchSourceRelativePositionChange();
    }

    public abstract BlurredBackgroundSource getSource();

    public BlurredBackgroundSource getUnwrappedSource() {
        BlurredBackgroundSource source = getSource();
        while (source instanceof BlurredBackgroundSourceWrapped) {
            source = ((BlurredBackgroundSourceWrapped) source).getSource();
        }

        return source;
    }



    /* Colors */

    protected BlurredBackgroundColorProvider colorProvider;
    protected int shadowColor, backgroundColor, strokeColorTop, strokeColorBottom, strokeColorFull;

    public BlurredBackgroundDrawable setColorProvider(BlurredBackgroundColorProvider colorProvider) {
        this.colorProvider = colorProvider;

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

    @CallSuper
    public void updateColors() {
        if (colorProvider == null) return;
        final int oldBackgroundColor = backgroundColor;
        final int oldShadowColor = shadowColor;
        final int oldStrokeColorTop = strokeColorTop;
        final int oldStrokeColorBottom = strokeColorBottom;
        final int oldStrokeColorFull = strokeColorFull;
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
        if (app.nimarkogram.messenger.NimarkoConfig.glareOnElements) {
            strokeColorTop = colorProvider.getStrokeColorTop();
            strokeColorBottom = colorProvider.getStrokeColorBottom();
            strokeColorFull = 0;
        } else {
            strokeColorTop = 0;
            strokeColorBottom = 0;
            strokeColorFull = colorProvider.getStrokeColorFull();
        }
        if (oldBackgroundColor != backgroundColor || oldShadowColor != shadowColor
                || oldStrokeColorTop != strokeColorTop || oldStrokeColorBottom != strokeColorBottom
                || oldStrokeColorFull != strokeColorFull) {
            invalidateSelf();
        }
    }



    /* Bound Props */
    private static final float[] tmpRadii = new float[8];
    protected final Props boundProps = new Props();

    protected static class Props {
        public final Rect bounds = new Rect();
        public final float[] radii = new float[8];
        public final float[] shaderRadii = new float[8];
        public int padding;
        public boolean hasPadding;
        public int liquidThickness;
        public float liquidIntensity = DEFAULT_LIQUID_INTENSITY;
        public float liquidIndex = 1.5f;

        public float strokeWidthTop;
        public float strokeWidthBottom;

        public final Path path = new Path();
        public boolean radiiAreSame = true;

        public final Rect boundsWithPadding = new Rect();

        public final Path strokePathTop = new Path();
        public final Path strokePathBottom = new Path();
        public final Path strokePathFull = new Path();

        public void build() {
            radiiAreSame = RadiiUtils.radiiAreSame(radii);

            boundsWithPadding.set(bounds);
            boundsWithPadding.inset(padding, padding);

            path.rewind();
            path.addRoundRect(
                boundsWithPadding.left,
                boundsWithPadding.top,
                boundsWithPadding.right,
                boundsWithPadding.bottom,
                radii, Path.Direction.CW);
            path.close();
            strokePathFull.rewind();
            if (strokeWidthTop > 0 && !boundsWithPadding.isEmpty()) {
                strokePathFull.set(path);
                if (boundsWithPadding.width() > strokeWidthTop * 2
                        && boundsWithPadding.height() > strokeWidthTop * 2) {
                    for (int i = 0; i < tmpRadii.length; i++) {
                        tmpRadii[i] = Math.max(0, radii[i] - strokeWidthTop);
                    }
                    strokePathFull.addRoundRect(
                        boundsWithPadding.left + strokeWidthTop,
                        boundsWithPadding.top + strokeWidthTop,
                        boundsWithPadding.right - strokeWidthTop,
                        boundsWithPadding.bottom - strokeWidthTop,
                        tmpRadii, Path.Direction.CCW);
                    strokePathFull.close();
                }
            }

            final float radiusMax = Math.min(boundsWithPadding.width(), boundsWithPadding.height()) / 2f;

            Arrays.fill(tmpRadii, 0);
            tmpRadii[0] = radii[0]; tmpRadii[1] = radii[1]; tmpRadii[2] = radii[2]; tmpRadii[3] = radii[3];
            if (radiiAreSame && radii[0] > radiusMax) {
                tmpRadii[0] = tmpRadii[1] = tmpRadii[2] = tmpRadii[3] = radiusMax;
            }
            strokePathTop.rewind();
            strokePathTop.addRoundRect(
                boundsWithPadding.left, boundsWithPadding.top, boundsWithPadding.right,
                Math.min(boundsWithPadding.top + radii[0], boundsWithPadding.bottom), tmpRadii, Path.Direction.CW);
            strokePathTop.addRoundRect(
                boundsWithPadding.left, boundsWithPadding.top + strokeWidthTop, boundsWithPadding.right,
                Math.min(boundsWithPadding.top + radii[0], boundsWithPadding.bottom), tmpRadii, Path.Direction.CCW);
            strokePathTop.close();

            Arrays.fill(tmpRadii, 0);
            tmpRadii[4] = radii[4]; tmpRadii[5] = radii[5]; tmpRadii[6] = radii[6]; tmpRadii[7] = radii[7];
            if (radiiAreSame && radii[0] > radiusMax) {
                tmpRadii[4] = tmpRadii[5] = tmpRadii[6] = tmpRadii[7] = radiusMax;
            }
            strokePathBottom.rewind();
            strokePathBottom.addRoundRect(
                boundsWithPadding.left, Math.max(boundsWithPadding.bottom - radii[4], boundsWithPadding.top),
                boundsWithPadding.right, boundsWithPadding.bottom, tmpRadii, Path.Direction.CW);
            strokePathBottom.addRoundRect(
                boundsWithPadding.left, Math.max(boundsWithPadding.bottom - radii[4], boundsWithPadding.top),
                boundsWithPadding.right, boundsWithPadding.bottom - strokeWidthBottom, tmpRadii, Path.Direction.CCW);
            strokePathBottom.close();
        }

        public void drawShadows(Canvas canvas, Paint paint, boolean useInAppKeyboardOptimization) {
            if (useInAppKeyboardOptimization) {
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

        public void draw(Canvas canvas, Paint paint) {
            if (radiiAreSame) {
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
                    BlurredBackgroundDrawable.getOutline(outline, boundProps.boundsWithPadding, boundProps.radii);
                }
            };
        }

        return viewOutlineProvider;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        BlurredBackgroundDrawable.getOutline(outline, boundProps.boundsWithPadding, boundProps.radii);
    }
    public boolean hasDisplayList() {
        return false;
    }
    public void updateDisplayList() {
    }

    private static Path tmpPath = new Path();
    protected static void getOutline(Outline outline, Rect rect, float[] radii) {
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

    protected int alpha = 255;

    @Override
    public void setAlpha(int alpha) {
        if (this.alpha != alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }



    public static void drawStroke(
        Canvas canvas,
        float left, float top, float right, float bottom,
        float[] radii, float strokeWidth, boolean isTop,
        Paint paint
    ) {

        final boolean radiiAreSame = isTop ?
            radii[0] == radii[1] && radii[1] == radii[2] && radii[2] == radii[3]:
            radii[4] == radii[5] && radii[5] == radii[6] && radii[6] == radii[7];

        final float strokeHalf = strokeWidth / 2f;

        if (isTop) {
            // float topLeft, float topRight, float bottomRight, float bottomLeft

            if (radiiAreSame) {
                canvas.save();
                if (canvas.clipRect(left, top, right, MathUtils.clamp(top + radii[0] * 2, top, bottom))) {
                    canvas.drawRoundRect(
                            left - strokeHalf,
                            top + strokeHalf,
                            right + strokeHalf,
                            bottom + strokeHalf,
                            radii[0], radii[0],
                            paint
                    );
                }
                canvas.restore();
            } else {
                final float cx = (left + right) / 2f;
                canvas.save();
                if (canvas.clipRect(left, top, cx, MathUtils.clamp(top + radii[0] * 2, top, bottom))) {
                    canvas.drawRoundRect(
                            left - strokeHalf,
                            top + strokeHalf,
                            right + strokeHalf,
                            bottom + strokeHalf,
                            radii[0], radii[1],
                            paint
                    );
                }
                canvas.restore();
                canvas.save();
                if (canvas.clipRect(cx, top, right, MathUtils.clamp(top + radii[0] * 2, top, bottom))) {
                    canvas.drawRoundRect(
                            left - strokeHalf,
                            top + strokeHalf,
                            right + strokeHalf,
                            bottom + strokeHalf,
                            radii[2], radii[3],
                            paint
                    );
                }
                canvas.restore();
            }
        } else {
            if (radiiAreSame) {
                canvas.save();
                if (canvas.clipRect(left, MathUtils.clamp(bottom - radii[4] * 2, top, bottom), right, bottom)) {
                    canvas.drawRoundRect(
                            left - strokeHalf,
                            top - strokeHalf,
                            right + strokeHalf,
                            bottom - strokeHalf,
                            radii[4], radii[4],
                            paint
                    );
                }
                canvas.restore();
            } else {
                final float cx = (left + right) / 2f;
                canvas.save();
                if (canvas.clipRect(left, MathUtils.clamp(bottom - radii[4] * 2, top, bottom), cx, bottom)) {
                    canvas.drawRoundRect(
                            left - strokeHalf,
                            top - strokeHalf,
                            right + strokeHalf,
                            bottom - strokeHalf,
                            radii[6], radii[7],
                            paint
                    );
                }
                canvas.restore();
                canvas.save();
                if (canvas.clipRect(cx, MathUtils.clamp(bottom - radii[4] * 2, top, bottom), right, bottom)) {
                    canvas.drawRoundRect(
                            left - strokeHalf,
                            top - strokeHalf,
                            right + strokeHalf,
                            bottom - strokeHalf,
                            radii[4], radii[5],
                            paint
                    );
                }
                canvas.restore();
            }
        }
    }

    public static void drawStroke(Canvas canvas, RectF rect,
                                     float radii, float strokeWidth, boolean isTop, Paint paint) {
        drawStroke(canvas, rect.left, rect.top, rect.right, rect.bottom, radii, strokeWidth, isTop, paint);
    }

    public static void drawStroke(Canvas canvas, float left, float top, float right, float bottom,
                                     float radii, float strokeWidth, boolean isTop, Paint paint) {
        final float strokeHalf = strokeWidth / 2f;
        canvas.save();
        if (isTop) {
            if (canvas.clipRect(left - strokeHalf, top, right + strokeHalf, MathUtils.clamp(top + radii * 2, top, bottom))) {
                canvas.drawRoundRect(
                    left - strokeHalf,
                    top + strokeHalf,
                    right + strokeHalf,
                    bottom + strokeHalf,
                    radii, radii,
                    paint
                );
            }
        } else {
            if (canvas.clipRect(left - strokeHalf, MathUtils.clamp(bottom - radii * 2, top, bottom), right + strokeHalf, bottom)) {
                canvas.drawRoundRect(
                    left - strokeHalf,
                    top - strokeHalf,
                    right + strokeHalf,
                    bottom - strokeHalf,
                    radii, radii,
                    paint
                );
            }
        }
        canvas.restore();
    }

    protected boolean inAppKeyboardOptimization;
    public void enableInAppKeyboardOptimization() {
        inAppKeyboardOptimization = true;
    }


    protected float shadowLayerRadius;
    protected float shadowLayerDx;
    protected float shadowLayerDy;
    protected float shadowAlpha = 1.0f;

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
            boundProps.build();
            onBoundPropsChanged();
            invalidateSelf();
        }
    }



    /* Universal */

    protected void drawSource(Canvas canvas, BlurredBackgroundSource source) {
        if (alpha == 0 || boundProps.boundsWithPadding.isEmpty()) {
            return;
        }

        if (Color.alpha(backgroundColor) == 255) {
            drawSourceColorImpl(canvas, 0);
            return;
        }

        if (source instanceof BlurredBackgroundSourceColor) {
            drawSourceColor(canvas, (BlurredBackgroundSourceColor) source);
        } else if (source instanceof BlurredBackgroundSourceBitmap) {
            drawSourceBitmap(canvas, (BlurredBackgroundSourceBitmap) source);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && source instanceof BlurredBackgroundSourceRenderNode) {
            drawSourceRenderNode(canvas, (BlurredBackgroundSourceRenderNode) source);
        } else if (source instanceof BlurredBackgroundSourceWrapped) {
            drawSource(canvas, ((BlurredBackgroundSourceWrapped) source).getSource());
        } else if (source != null) {
            drawSourceAny(canvas, source);
        } else {
            drawSourceColorImpl(canvas, 0);
        }
    }

    private final Paint backgroundColorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paintStrokeFill = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint backgroundBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundBitmapFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix bitmapShaderMatrix = new Matrix();
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
        shadowPaint.setColor(0);
        backgroundBitmapPaint.setFilterBitmap(true);
    }


    private void drawShadow(Canvas canvas) {
        final int shadowDrawAlpha = MathUtils.clamp(Math.round(alpha * shadowAlpha), 0, 255);
        if (Color.alpha(shadowColor) == 0 || shadowLayerRadius <= 0 || shadowDrawAlpha == 0) {
            return;
        }

        final NinePatchDrawable shadow = checkShadowDrawable();
        shadow.setBounds(
            boundProps.boundsWithPadding.left - ninePatchDrawablePadding.left,
            boundProps.boundsWithPadding.top - ninePatchDrawablePadding.top,
            boundProps.boundsWithPadding.right + ninePatchDrawablePadding.right,
            boundProps.boundsWithPadding.bottom + ninePatchDrawablePadding.bottom
        );
        shadow.setAlpha(shadowDrawAlpha);
        shadow.draw(canvas);
    }
    private int saveContentLayer(Canvas canvas) {
        return alpha == 255 ? -1 : canvas.saveLayerAlpha(
            boundProps.boundsWithPadding.left, boundProps.boundsWithPadding.top,
            boundProps.boundsWithPadding.right, boundProps.boundsWithPadding.bottom, alpha);
    }
    private void drawSourceAny(Canvas canvas, BlurredBackgroundSource source) {
        final int effectiveShadowColor = Theme.multAlpha(shadowColor, alpha / 255f * shadowAlpha);
        if (Color.alpha(effectiveShadowColor) > 0) {
            shadowPaint.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy, effectiveShadowColor);
            boundProps.drawShadows(canvas, shadowPaint, inAppKeyboardOptimization);
        }

        final float offsetX = sourceOffsetX;
        final float offsetY = sourceOffsetY;
        final float sL = boundProps.boundsWithPadding.left + offsetX;
        final float sT = boundProps.boundsWithPadding.top + offsetY;
        final float sR = boundProps.boundsWithPadding.right + offsetX;
        final float sB = boundProps.boundsWithPadding.bottom + offsetY;

        final int layer = saveContentLayer(canvas);

        canvas.save();
        canvas.clipPath(boundProps.path);
        canvas.translate(
            boundProps.boundsWithPadding.left,
            boundProps.boundsWithPadding.top
        );
        canvas.translate(-sL, -sT);
        source.draw(canvas, sL, sT, sR, sB);
        canvas.restore();

        if (Color.alpha(backgroundColor) > 0) {
            backgroundColorPaint.setColor(backgroundColor);
            boundProps.draw(canvas, backgroundColorPaint);
        }

        drawStrokeInternalIfNeeded(canvas);

        if (layer != -1) {
            canvas.restoreToCount(layer);
        }
    }

    private void drawSourceColor(Canvas canvas, BlurredBackgroundSourceColor source) {
        drawSourceColorImpl(canvas, source.getColor());
    }

    private void drawSourceColorImpl(Canvas canvas, int sourceColor) {
        final int fillColor = ColorUtils.compositeColors(this.backgroundColor, sourceColor);
        drawShadow(canvas);
        final int layer = saveContentLayer(canvas);
        if (Color.alpha(fillColor) > 0) {
            backgroundColorPaint.setColor(fillColor);
            boundProps.draw(canvas, backgroundColorPaint);
        }
        drawStrokeInternalIfNeeded(canvas);
        if (layer != -1) {
            canvas.restoreToCount(layer);
        }

    }

    private void drawSourceBitmap(Canvas canvas, BlurredBackgroundSourceBitmap source) {
        final Bitmap newBitmap = source.getBitmap();

        if (newBitmap == null || newBitmap.isRecycled()) {
            if (bitmapInShader != null || bitmapShader != null) {
                bitmapInShader = null;
                bitmapShader = null;
                backgroundBitmapPaint.setShader(null);
            }
        } else if (newBitmap != bitmapInShader) {
            bitmapInShader = newBitmap;
            bitmapShader = new BitmapShader(newBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            backgroundBitmapPaint.setShader(bitmapShader);
        }

        drawShadow(canvas);
        final int layer = saveContentLayer(canvas);

        if (bitmapShader != null && newBitmap != null && !newBitmap.isRecycled() && alpha > 0) {
            bitmapShaderMatrix.set(source.getMatrix());
            bitmapShaderMatrix.postTranslate(-sourceOffsetX, -sourceOffsetY);
            bitmapShader.setLocalMatrix(bitmapShaderMatrix);
            backgroundBitmapPaint.setAlpha(255);
            boundProps.draw(canvas, backgroundBitmapPaint);
        }

        if (Color.alpha(backgroundColor) > 0) {
            backgroundBitmapFill.setColor(backgroundColor);
            boundProps.draw(canvas, backgroundBitmapFill);
        }

        drawStrokeInternalIfNeeded(canvas);
        if (layer != -1) {
            canvas.restoreToCount(layer);
        }
    }

    private void drawStrokeInternalIfNeeded(Canvas canvas) {
        // NG (extera-port "GlareOnElements" fallback): when glare is OFF the
        // provider supplies a single full-stroke colour to draw a clean
        // uniform outline using the top path (full perimeter of the rounded
        // rect). This matches extera's `useFullStroke` branch.
        if (Color.alpha(strokeColorFull) > 0) {
            paintStrokeFill.setColor(strokeColorFull);
            canvas.drawPath(boundProps.strokePathFull, paintStrokeFill);
            return;
        }

        if (Color.alpha(strokeColorTop) > 0) {
            paintStrokeFill.setColor(strokeColorTop);
            canvas.drawPath(boundProps.strokePathTop, paintStrokeFill);
        }
        if (Color.alpha(strokeColorBottom) > 0) {
            paintStrokeFill.setColor(strokeColorBottom);
            canvas.drawPath(boundProps.strokePathBottom, paintStrokeFill);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void drawSourceRenderNode(Canvas canvas, BlurredBackgroundSourceRenderNode source) {
        if (!canvas.isHardwareAccelerated()) {
            drawSource(canvas, source.getFallbackSource());
            return;
        }
        drawSourceAny(canvas, source);

        // todo: move from drawableRenderNode
    }

    private final RectF cmpRectF1 = new RectF();
    private final RectF cmpRectF2 = new RectF();
    private void dispatchSourceRelativePositionChange() {
        getPositionRelativeSource(cmpRectF1);
        if (!cmpRectF1.equals(cmpRectF2)) {
            cmpRectF2.set(cmpRectF1);
            onSourceRelativePositionChanged(cmpRectF1);
        }
    }

    @CallSuper
    protected void onSourceRelativePositionChanged(RectF position) {

    }

    public void getPositionRelativeSource(RectF position) {
        position.set(boundProps.boundsWithPadding);
        position.offset(sourceOffsetX, sourceOffsetY);
    }




    /* * */

    //private static final Map<Long, NinePatchDrawable> ninePatchDrawablesPool = new MapMaker()
    //    .weakValues()
    //    .makeMap();

    private final Blur3HashImpl ninePatchHashBuilder = new Blur3HashImpl();
    private final Rect ninePatchDrawablePadding = new Rect();
    private NinePatchDrawable ninePatchDrawable;
    private long ninePatchDrawableHash;

    @NonNull
    private NinePatchDrawable checkShadowDrawable() {
        ninePatchHashBuilder.start();
        ninePatchHashBuilder.add(shadowColor);
        ninePatchHashBuilder.add(boundProps.radii);
        ninePatchHashBuilder.addF(shadowLayerRadius);
        ninePatchHashBuilder.addF(shadowLayerDx);
        ninePatchHashBuilder.addF(shadowLayerDy);

        final long hash = ninePatchHashBuilder.get();

        if (ninePatchDrawable == null || ninePatchDrawableHash != hash) {
            ninePatchDrawableHash = hash;


            ninePatchDrawable = NinePatchBuilder.createNinePatch(
                null,
                boundProps.radii, shadowLayerRadius,
                shadowLayerDx, shadowLayerDy,
                NinePatchBuilder.NO_COLOR, (canvas, rect, radii) -> {
                    final Path path = new Path();
                    path.addRoundRect(rect, radii, Path.Direction.CW);
                    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setColor(Color.TRANSPARENT);
                    paint.setShadowLayer(shadowLayerRadius, shadowLayerDx, shadowLayerDy, shadowColor);
                    canvas.drawPath(path, paint);
                }
            );

            ninePatchDrawable.getPadding(ninePatchDrawablePadding);
        }

        return ninePatchDrawable;
    }
}
