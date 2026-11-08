/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;
import org.telegram.utils.glass.GlassCaptureSourceCanvas;
import org.telegram.utils.glass.GlassEngine;
import org.telegram.utils.glass.GlassSettings;
import org.telegram.utils.glass.fade.GlassFadeBlurredDrawable;
import org.telegram.utils.glass.source.GlassSource;
import org.telegram.utils.glass.source.GlassSourceCrossfade;

import me.vkryl.core.reference.ReferenceList;

public final class ProfileButtonsGlass {
    private static final float PHOTO_SATURATION = .65f;
    private static final float PHOTO_BRIGHTNESS = .5f;

    private static final float[] IDENTITY = new ColorMatrix().getArray();
    private static final float[] PHOTO_MATRIX;

    static {
        final ColorMatrix matrix = new ColorMatrix();
        AndroidUtilities.adjustSaturationColorMatrix(matrix, PHOTO_SATURATION);
        AndroidUtilities.multiplyBrightnessColorMatrix(matrix, PHOTO_BRIGHTNESS);
        PHOTO_MATRIX = matrix.getArray();
    }

    public static final BlurredBackgroundColorProvider NO_COLORS = new BlurredBackgroundColorProvider() {
        @Override
        public int getShadowColor() {
            return 0;
        }

        @Override
        public int getBackgroundColor() {
            return 0;
        }

        @Override
        public int getStrokeColorTop() {
            return 0;
        }

        @Override
        public int getStrokeColorBottom() {
            return 0;
        }
    };

    @Nullable
    private final Object captureCanvas;
    @Nullable
    private final GlassSource photoSource;

    private final Source actionsSource;
    private final Source headerSource;

    public final BlurredBackgroundDrawableViewFactory actionsFactory;
    public final BlurredBackgroundDrawableViewFactory headerFactory;
    public final BlurredBackgroundDrawableViewFactory menuFactory;
    public final GlassSourceCrossfade menuSource;

    private final ReferenceList<View> views = new ReferenceList<>();
    private final ReferenceList<BlurredBackgroundDrawable> drawables = new ReferenceList<>();

    private int buttonColor;
    private boolean hasColorById;
    private float photoProgress;
    private float bannerProgress;
    private float simpleProgress;

    public ProfileButtonsGlass(@NonNull GlassEngine engine, @NonNull BaseFragmentSimpleGlass simpleGlass) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            simpleGlass.setCaptureIgnoresBlurSetting(true);
            captureCanvas = simpleGlass.getCaptureSourceCanvas();
            photoSource = captureCanvas != null ? getFilteredSource(captureCanvas) : null;
        } else {
            captureCanvas = null;
            photoSource = null;
        }

        actionsSource = new Source(null);
        headerSource = new Source(new SettingsSource(simpleGlass.factory));

        actionsFactory = createFactory(engine, actionsSource);
        headerFactory = createFactory(engine, headerSource);
        headerFactory.setLiquidGlassEffectAllowed(true);

        menuSource = new GlassSourceCrossfade(headerSource, new SettingsSource(simpleGlass.frostedFactory));
        menuFactory = createFactory(engine, menuSource);
        menuFactory.setLiquidGlassEffectAllowed(true);
    }

    private BlurredBackgroundDrawableViewFactory createFactory(GlassEngine engine, GlassSource source) {
        final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(source);
        factory.setGlassEngine(engine);
        factory.setLinkedViewsRef(views);
        factory.setLinkedDrawablesRef(drawables);
        return factory;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static GlassSource getFilteredSource(Object captureCanvas) {
        return ((GlassCaptureSourceCanvas) captureCanvas).getSource(GlassCaptureSourceCanvas.SourceIndex.FROSTED_FILTERED);
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static void setFilter(Object captureCanvas, @Nullable ColorMatrixColorFilter filter) {
        ((GlassCaptureSourceCanvas) captureCanvas).setFilteredColorFilter(filter);
    }

    public void setButtonColor(int color, boolean hasColorById) {
        if (buttonColor != color || this.hasColorById != hasColorById) {
            buttonColor = color;
            this.hasColorById = hasColorById;
            invalidate();
        }
    }

    public int getButtonColor() {
        return buttonColor;
    }

    public void setPhotoProgress(float progress) {
        if (photoProgress != progress) {
            photoProgress = progress;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && captureCanvas != null) {
                setFilter(captureCanvas, progress > 0 ? new ColorMatrixColorFilter(lerp(IDENTITY, PHOTO_MATRIX, progress)) : null);
            }
            invalidate();
        }
    }

    public void setSimpleProgress(float progress) {
        if (simpleProgress != progress) {
            simpleProgress = progress;
            invalidate();
        }
    }

    public void setBannerProgress(float progress) {
        progress = Math.max(0f, Math.min(1f, progress));
        if (bannerProgress != progress) {
            bannerProgress = progress;
            invalidate();
        }
    }

    private void invalidate() {
        for (BlurredBackgroundDrawable drawable : drawables) {
            drawable.updateColors();
        }
        for (View view : views) {
            view.invalidate();
        }
    }

    private static float[] lerp(float[] from, float[] to, float t) {
        final float[] out = new float[from.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = AndroidUtilities.lerp(from[i], to[i], t);
        }
        return out;
    }

    private static final class SettingsSource implements GlassSource, GlassFadeBlurredDrawable.GlassFadeDownsampledSource {
        private final BlurredBackgroundDrawableViewFactory factory;

        private SettingsSource(BlurredBackgroundDrawableViewFactory factory) {
            this.factory = factory;
        }

        private GlassSource current() {
            final GlassSettings settings = GlassEngine.getCurrentGlassSettings();
            if (!settings.isBlurEnabled) {
                return factory.getStaticSource();
            }
            return settings.isLiquidGlassEnabled && Build.VERSION.SDK_INT >= 33 ? factory.getSource() : factory.getFrostedSource();
        }

        @Override
        public void draw(@NonNull Canvas canvas, float left, float top, float right, float bottom) {
            current().draw(canvas, left, top, right, bottom);
        }

        @Override
        public boolean isDownsampledSourceSupported() {
            final GlassSource current = current();
            return current instanceof GlassFadeBlurredDrawable.GlassFadeDownsampledSource
                && ((GlassFadeBlurredDrawable.GlassFadeDownsampledSource) current).isDownsampledSourceSupported();
        }

        @Override
        public int getDownsample() {
            return ((GlassFadeBlurredDrawable.GlassFadeDownsampledSource) current()).getDownsample();
        }

        @Override
        public int getDownsampleOffsetX() {
            return ((GlassFadeBlurredDrawable.GlassFadeDownsampledSource) current()).getDownsampleOffsetX();
        }

        @Override
        public int getDownsampleOffsetY() {
            return ((GlassFadeBlurredDrawable.GlassFadeDownsampledSource) current()).getDownsampleOffsetY();
        }

        @Override
        public void drawDownsampled(@NonNull Canvas canvas, @NonNull RectF boundsRelativeRoot, int targetDownsample, float targetOriginX, float targetOriginY) {
            ((GlassFadeBlurredDrawable.GlassFadeDownsampledSource) current()).drawDownsampled(canvas, boundsRelativeRoot, targetDownsample, targetOriginX, targetOriginY);
        }
    }

    private final class Source implements GlassSource, BlurredBackgroundDrawableViewFactory.DrawableOwner, GlassFadeBlurredDrawable.GlassFadeDownsampledSource {
        @Nullable
        private final GlassSource simpleSource;

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dimPaint = new Paint();
        private RadialGradient gradient;
        private int gradientColor;
        private float gradientRadius;

        private Source(@Nullable GlassSource simpleSource) {
            this.simpleSource = simpleSource;
        }

        @Override
        public BlurredBackgroundDrawable createDrawable() {
            return new BlurredBackgroundDrawable(this);
        }

        @Override
        public void draw(@NonNull Canvas canvas, float left, float top, float right, float bottom) {
            final float simple = simpleSource != null ? simpleProgress : 0f;
            if (simple < 1f) {
                final boolean photo = photoSource != null && canvas.isHardwareAccelerated()
                        && (!(photoSource instanceof GlassCaptureSourceCanvas.Source)
                        || ((GlassCaptureSourceCanvas.Source) photoSource).isDisplayListReady());
                if (photo) {
                    photoSource.draw(canvas, left, top, right, bottom);
                } else if (photoProgress > 0) {
                    dimPaint.setColor(Theme.multAlpha(0xFF000000, (1f - PHOTO_BRIGHTNESS) * photoProgress));
                    canvas.drawRect(left, top, right, bottom, dimPaint);
                }
                final float fill = (1f - photoProgress) * (1f - bannerProgress);
                if (fill > 0) {
                    drawFill(canvas, left, top, right, bottom, fill, 0);
                }
                if (bannerProgress > 0f) {
                    dimPaint.setColor(Theme.multAlpha(0x28000000, bannerProgress));
                    canvas.drawRect(left, top, right, bottom, dimPaint);
                }
            }
            if (simple > 0f) {
                if (simple < 1f) {
                    canvas.saveLayerAlpha(left, top, right, bottom, (int) (0xFF * simple));
                }
                simpleSource.draw(canvas, left, top, right, bottom);
                if (simple < 1f) {
                    canvas.restore();
                }
            }
        }

        @Nullable
        private GlassFadeBlurredDrawable.GlassFadeDownsampledSource photoDownsampled() {
            return photoSource instanceof GlassFadeBlurredDrawable.GlassFadeDownsampledSource
                ? (GlassFadeBlurredDrawable.GlassFadeDownsampledSource) photoSource : null;
        }

        @Nullable
        private GlassFadeBlurredDrawable.GlassFadeDownsampledSource simpleDownsampled() {
            return simpleSource instanceof GlassFadeBlurredDrawable.GlassFadeDownsampledSource
                ? (GlassFadeBlurredDrawable.GlassFadeDownsampledSource) simpleSource : null;
        }

        @Override
        public boolean isDownsampledSourceSupported() {
            final GlassFadeBlurredDrawable.GlassFadeDownsampledSource photo = photoDownsampled(), simple = simpleDownsampled();
            return photo != null && photo.isDownsampledSourceSupported() && photo.getDownsample() > 1
                && (!(photoSource instanceof GlassCaptureSourceCanvas.Source)
                || ((GlassCaptureSourceCanvas.Source) photoSource).isDisplayListReady())
                && (simpleSource == null || simple != null && simple.isDownsampledSourceSupported());
        }

        private GlassFadeBlurredDrawable.GlassFadeDownsampledSource finer() {
            final GlassFadeBlurredDrawable.GlassFadeDownsampledSource photo = photoDownsampled(), simple = simpleDownsampled();
            return simple == null || photo.getDownsample() < simple.getDownsample() ? photo : simple;
        }

        @Override
        public int getDownsample() {
            return finer().getDownsample();
        }

        @Override
        public int getDownsampleOffsetX() {
            return finer().getDownsampleOffsetX();
        }

        @Override
        public int getDownsampleOffsetY() {
            return finer().getDownsampleOffsetY();
        }

        @Override
        public void drawDownsampled(@NonNull Canvas canvas, @NonNull RectF boundsRelativeRoot, int targetDownsample, float targetOriginX, float targetOriginY) {
            final float simple = simpleSource != null ? simpleProgress : 0f;
            if (simple < 1f) {
                photoDownsampled().drawDownsampled(canvas, boundsRelativeRoot, targetDownsample, targetOriginX, targetOriginY);
                final float fill = (1f - photoProgress) * (1f - bannerProgress);
                if (fill > 0) {
                    canvas.save();
                    canvas.scale(1f / targetDownsample, 1f / targetDownsample);
                    canvas.translate(-targetOriginX, -targetOriginY);
                    drawFill(canvas, boundsRelativeRoot.left, boundsRelativeRoot.top, boundsRelativeRoot.right, boundsRelativeRoot.bottom, fill, targetDownsample * 2);
                    canvas.restore();
                }
                if (bannerProgress > 0f) {
                    dimPaint.setColor(Theme.multAlpha(0x28000000, bannerProgress));
                    canvas.save();
                    canvas.scale(1f / targetDownsample, 1f / targetDownsample);
                    canvas.translate(-targetOriginX, -targetOriginY);
                    final float outset = targetDownsample * 2f;
                    canvas.drawRect(boundsRelativeRoot.left - outset, boundsRelativeRoot.top - outset,
                            boundsRelativeRoot.right + outset, boundsRelativeRoot.bottom + outset, dimPaint);
                    canvas.restore();
                }
            }
            if (simple > 0f) {
                if (simple < 1f) {
                    canvas.saveLayerAlpha(null, (int) (0xFF * simple));
                }
                simpleDownsampled().drawDownsampled(canvas, boundsRelativeRoot, targetDownsample, targetOriginX, targetOriginY);
                if (simple < 1f) {
                    canvas.restore();
                }
            }
        }

        private void drawFill(Canvas canvas, float left, float top, float right, float bottom, float alpha, float outset) {
            if (hasColorById) {
                final float width = right - left;
                final float radius = Math.max(1f, width * 0.65f);
                if (gradient == null || gradientColor != buttonColor || gradientRadius != radius) {
                    gradientColor = buttonColor;
                    gradientRadius = radius;
                    gradient = new RadialGradient(0, 0, radius, Theme.multAlpha(buttonColor, 0.8f), buttonColor, Shader.TileMode.CLAMP);
                }
                canvas.save();
                canvas.translate((left + right) / 2f, (top + bottom) / 2f);
                fillPaint.setShader(gradient);
                fillPaint.setColor(0xFF000000);
                fillPaint.setAlpha((int) (0xFF * alpha));
                canvas.drawRect((left - right) / 2f - outset, (top - bottom) / 2f - outset, (right - left) / 2f + outset, (bottom - top) / 2f + outset, fillPaint);
                canvas.restore();
            } else {
                fillPaint.setShader(null);
                fillPaint.setColor(Theme.multAlpha(buttonColor, alpha));
                canvas.drawRect(left - outset, top - outset, right + outset, bottom + outset, fillPaint);
            }
        }
    }
}
