package org.telegram.ui.Components.blur3;

import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

@RequiresApi(api = 33)
public class LiquidGlassEffect {

    private final RenderNode node;
    private final RuntimeShader shader;
    private RenderEffect effect;

    public LiquidGlassEffect(RenderNode node) {
        this.node = node;
        final String code = AndroidUtilities.readRes(R.raw.liquid_glass_shader);
        shader = new RuntimeShader(code);
    }
    public void setEnabled(boolean enabled) {
        node.setRenderEffect(enabled ? effect : null);
    }

    private float resolutionX, resolutionY;
    private float centerX, centerY;
    private float sizeX, sizeY;
    private float radiusLeftTop;
    private float radiusRightTop;
    private float radiusRightBottom;
    private float radiusLeftBottom;
    private float thickness;
    private float intensity;
    private float index;
    private int foregroundColor;
    private boolean reflectionEnabled;
    private boolean uniformsInitialized;
    public int getForegroundColor() {
        return foregroundColor;
    }

    public void update(
        float left, float top, float right, float bottom,
        float radiusLeftTop, float radiusRightTop, float radiusRightBottom, float radiusLeftBottom,

        float thickness,
        float intensity,
        float index,
        int foregroundColor
    ) {
        final boolean reflectionEnabled = app.nimarkogram.messenger.NimarkoConfig.glareOnElements;
        float resolutionX = node.getWidth();
        float resolutionY = node.getHeight();
        float centerX = (left + right) / 2;
        float centerY = (top + bottom) / 2;
        float width = right - left, height = bottom - top;
        float sizeX = width / 2;
        float sizeY = height / 2;
        float radiusScale = 1f;
        if (radiusLeftTop + radiusRightTop > width) {
            radiusScale = Math.min(radiusScale, width / (radiusLeftTop + radiusRightTop));
        }
        if (radiusLeftBottom + radiusRightBottom > width) {
            radiusScale = Math.min(radiusScale, width / (radiusLeftBottom + radiusRightBottom));
        }

        if (radiusLeftTop + radiusLeftBottom > height) {
            radiusScale = Math.min(radiusScale, height / (radiusLeftTop + radiusLeftBottom));
        }
        if (radiusRightTop + radiusRightBottom > height) {
            radiusScale = Math.min(radiusScale, height / (radiusRightTop + radiusRightBottom));
        }
        if (radiusScale < 1f) {
            radiusLeftTop *= radiusScale;
            radiusRightTop *= radiusScale;
            radiusRightBottom *= radiusScale;
            radiusLeftBottom *= radiusScale;
        }

        if (
            !uniformsInitialized ||
            Math.abs(this.resolutionX - resolutionX) > 0.1f ||
            Math.abs(this.resolutionY - resolutionY) > 0.1f ||
            Math.abs(this.centerX - centerX) > 0.1f ||
            Math.abs(this.centerY - centerY) > 0.1f ||
            Math.abs(this.sizeX - sizeX) > 0.1f ||
            Math.abs(this.sizeY - sizeY) > 0.1f ||
            Math.abs(this.radiusLeftTop - radiusLeftTop) > 0.1f ||
            Math.abs(this.radiusRightTop - radiusRightTop) > 0.1f ||
            Math.abs(this.radiusRightBottom - radiusRightBottom) > 0.1f ||
            Math.abs(this.radiusLeftBottom - radiusLeftBottom) > 0.1f ||
            Math.abs(this.thickness - thickness) > 0.1f ||
            Math.abs(this.intensity - intensity) > 0.001f ||
            Math.abs(this.index - index) > 0.001f ||
            this.foregroundColor != foregroundColor ||
            this.reflectionEnabled != reflectionEnabled
        ) {
            uniformsInitialized = true;
            this.foregroundColor = foregroundColor;
            this.reflectionEnabled = reflectionEnabled;
            shader.setFloatUniform("reflection_enabled", reflectionEnabled ? 1f : 0f);

            final float a = Color.alpha(foregroundColor) / 255f;
            final float r = Color.red(foregroundColor) / 255f * a;
            final float g = Color.green(foregroundColor) / 255f * a;
            final float b = Color.blue(foregroundColor) / 255f * a;

            shader.setFloatUniform("resolution", this.resolutionX = resolutionX, this.resolutionY = resolutionY);
            shader.setFloatUniform("center", this.centerX = centerX, this.centerY = centerY);
            shader.setFloatUniform("size", this.sizeX = sizeX, this.sizeY = sizeY);
            shader.setFloatUniform("radius", this.radiusRightBottom = radiusRightBottom, this.radiusRightTop = radiusRightTop, this.radiusLeftBottom = radiusLeftBottom, this.radiusLeftTop = radiusLeftTop);
            shader.setFloatUniform("thickness", this.thickness = thickness);
            shader.setFloatUniform("refract_intensity", this.intensity = intensity);
            shader.setFloatUniform("refract_index", this.index = index);
            shader.setFloatUniform("foreground_color_premultiplied", r, g, b, a);
            node.setRenderEffect(effect = RenderEffect.createRuntimeShaderEffect(shader, "img"));
        }
    }

}
