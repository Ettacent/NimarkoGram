/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.blur3;

import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

import java.util.Arrays;
@RequiresApi(api = 33)
public class LiquidGlassEffect {

    private final RenderNode node;
    private RuntimeShader plainShader;
    private RuntimeShader enhancedShader;
    private RuntimeShader standardShader;
    private boolean enhancedHasResolution, standardHasResolution, plainHasResolution;
    private boolean enhanced;
    private boolean reflectionEnabled;
    private boolean uniformsInitialized;
    private boolean enabled = true;
    private float resolutionX, resolutionY;
    private RuntimeShader arrowShader;
    private RuntimeShader shader;
    private RenderEffect effect;

    private RenderEffect[] effectPool;
    private float[] effectPoolCenterX;
    private float[] effectPoolCenterY;
    public LiquidGlassEffect(RenderNode node) {
        this.node = node;
        updateShader();
    }
    private void updateShader() {
        final boolean requested = app.nimarkogram.messenger.AppConfig.enhancedGlassBlur;
        if (plainShader != null && enhanced == requested) return;
        enhanced = requested;
        if (enhanced) {
            if (enhancedShader == null) {
                final String code = AndroidUtilities.readRes(R.raw.liquid_glass_shader);
                enhancedHasResolution = code.contains("uniform float2 resolution");
                enhancedShader = new RuntimeShader(code);
            }
            plainShader = enhancedShader;
            plainHasResolution = enhancedHasResolution;
        } else {
            if (standardShader == null) {
                final String code = AndroidUtilities.readRes(R.raw.liquid_glass_shader_standard);
                standardHasResolution = code.contains("uniform float2 resolution");
                standardShader = new RuntimeShader(code);
            }
            plainShader = standardShader;
            plainHasResolution = standardHasResolution;
        }
        uniformsInitialized = false;
    }
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        node.setRenderEffect(enabled ? effect : null);
    }

    public int getForegroundColor() {
        return foregroundColor;
    }

    private int downsample;
    private float width, height;
    private float radiusLeftTop;
    private float radiusRightTop;
    private float radiusRightBottom;
    private float radiusLeftBottom;
    private float thickness;
    private float intensity;
    private float index;
    private int foregroundColor;
    private float arrowHeight, arrowTipRadius, arrowFilletRadius, arrowStripe;
    private float arrowAxisX, arrowAxisY, arrowDirX, arrowDirY;
    private boolean arrowChanged;

    public void setArrow(float axisX, float axisY, float dirX, float dirY, float height, float tipRadius, float filletRadius, float stripe) {
        if (arrowAxisX != axisX || arrowAxisY != axisY || arrowDirX != dirX || arrowDirY != dirY
                || arrowHeight != height || arrowTipRadius != tipRadius || arrowFilletRadius != filletRadius || arrowStripe != stripe) {
            arrowAxisX = axisX;
            arrowAxisY = axisY;
            arrowDirX = dirX;
            arrowDirY = dirY;
            arrowHeight = height;
            arrowTipRadius = tipRadius;
            arrowFilletRadius = filletRadius;
            arrowStripe = stripe;
            arrowChanged = true;
        }
    }

    public void clearArrow() {
        setArrow(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public void update(
        float width, float height,
        float bodyX, float bodyY,
        float radiusLeftTop, float radiusRightTop, float radiusRightBottom, float radiusLeftBottom,

        float thickness,
        float intensity,
        float index,
        int foregroundColor,

        float nodeOffsetX, float nodeOffsetY,
        int downsample
    ) {
        updateShader();
        downsample = Math.max(1, downsample);
        final boolean reflectionEnabled = app.nimarkogram.messenger.AppConfig.glareOnElements;
        final float resolutionX = node.getWidth(), resolutionY = node.getHeight();
        float radiusScale = 1f;
        if (radiusLeftTop + radiusRightTop > width) radiusScale = Math.min(radiusScale, width / (radiusLeftTop + radiusRightTop));
        if (radiusLeftBottom + radiusRightBottom > width) radiusScale = Math.min(radiusScale, width / (radiusLeftBottom + radiusRightBottom));
        if (radiusLeftTop + radiusLeftBottom > height) radiusScale = Math.min(radiusScale, height / (radiusLeftTop + radiusLeftBottom));
        if (radiusRightTop + radiusRightBottom > height) radiusScale = Math.min(radiusScale, height / (radiusRightTop + radiusRightBottom));
        if (radiusScale < 1f) {
            radiusLeftTop *= radiusScale;
            radiusRightTop *= radiusScale;
            radiusRightBottom *= radiusScale;
            radiusLeftBottom *= radiusScale;
        }

        if (
            !uniformsInitialized ||
            this.resolutionX != resolutionX || this.resolutionY != resolutionY ||
            this.reflectionEnabled != reflectionEnabled ||
            this.downsample != downsample ||
            Math.abs(this.width - width) > 0.1f ||
            Math.abs(this.height - height) > 0.1f ||
            Math.abs(this.radiusLeftTop - radiusLeftTop) > 0.1f ||
            Math.abs(this.radiusRightTop - radiusRightTop) > 0.1f ||
            Math.abs(this.radiusRightBottom - radiusRightBottom) > 0.1f ||
            Math.abs(this.radiusLeftBottom - radiusLeftBottom) > 0.1f ||
            Math.abs(this.thickness - thickness) > 0.1f ||
            Math.abs(this.intensity - intensity) > 0.001f ||
            Math.abs(this.index - index) > 0.001f ||
            this.foregroundColor != foregroundColor ||
            arrowChanged
        ) {
            uniformsInitialized = true;
            this.resolutionX = resolutionX;
            this.resolutionY = resolutionY;
            this.reflectionEnabled = reflectionEnabled;
            arrowChanged = false;
            if (arrowHeight > 0) {
                if (arrowShader == null) {
                    arrowShader = new RuntimeShader(AndroidUtilities.readRes(R.raw.liquid_glass_arrow_shader));
                }
                shader = arrowShader;
            } else {
                shader = plainShader;
            }
            this.foregroundColor = foregroundColor;

            final float a = Color.alpha(foregroundColor) / 255f;
            final float r = Color.red(foregroundColor) / 255f * a;
            final float g = Color.green(foregroundColor) / 255f * a;
            final float b = Color.blue(foregroundColor) / 255f * a;

            final float scale = 1f / downsample;

            this.downsample = downsample;
            this.width = width;
            this.height = height;
            this.radiusRightBottom = radiusRightBottom;
            this.radiusRightTop = radiusRightTop;
            this.radiusLeftBottom = radiusLeftBottom;
            this.radiusLeftTop = radiusLeftTop;
            this.thickness = thickness;

            if (shader != arrowShader) {
                if (plainHasResolution) shader.setFloatUniform("resolution", resolutionX, resolutionY);
                if (enhanced) shader.setFloatUniform("reflection_enabled", reflectionEnabled ? 1f : 0f);
            }
            shader.setFloatUniform("size", width / 2 * scale, height / 2 * scale);
            shader.setFloatUniform("radius", radiusRightBottom * scale, radiusRightTop * scale, radiusLeftBottom * scale, radiusLeftTop * scale);
            shader.setFloatUniform("thickness", thickness * scale);
            this.intensity = intensity;
            shader.setFloatUniform("refract_intensity", enhanced ? intensity : intensity * (0.75f / 0.9f));
            shader.setFloatUniform("refract_index", this.index = index);
            shader.setFloatUniform("foreground_color_premultiplied", r, g, b, a);
            if (shader == arrowShader) {
                shader.setFloatUniform("arrow", arrowHeight * scale, arrowTipRadius * scale, arrowFilletRadius * scale, arrowStripe * scale);
                shader.setFloatUniform("arrow_axis", arrowAxisX * scale, arrowAxisY * scale, arrowDirX, arrowDirY);
            }

            final int poolSize = downsample * downsample + 1;
            if (effectPool == null || effectPool.length != poolSize) {
                effectPool = new RenderEffect[poolSize];
                effectPoolCenterX = new float[poolSize];
                effectPoolCenterY = new float[poolSize];
            } else {
                Arrays.fill(effectPool, null);
            }
        }

        final float centerX = (bodyX + width / 2 - nodeOffsetX) / downsample;
        final float centerY = (bodyY + height / 2 - nodeOffsetY) / downsample;
        final boolean onGrid = nodeOffsetX == (int) nodeOffsetX && nodeOffsetY == (int) nodeOffsetY;
        final int effectIndex = onGrid
            ? Math.floorMod((int) -nodeOffsetY, downsample) * downsample + Math.floorMod((int) -nodeOffsetX, downsample)
            : effectPool.length - 1;
        RenderEffect phaseEffect = effectPool[effectIndex];
        if (phaseEffect == null || effectPoolCenterX[effectIndex] != centerX || effectPoolCenterY[effectIndex] != centerY) {
            shader.setFloatUniform("center", centerX, centerY);
            phaseEffect = RenderEffect.createRuntimeShaderEffect(shader, "img");
            effectPool[effectIndex] = phaseEffect;
            effectPoolCenterX[effectIndex] = centerX;
            effectPoolCenterY[effectIndex] = centerY;
        }
        if (effect != phaseEffect) {
            effect = phaseEffect;
            if (enabled) node.setRenderEffect(effect);
        }
    }

}
