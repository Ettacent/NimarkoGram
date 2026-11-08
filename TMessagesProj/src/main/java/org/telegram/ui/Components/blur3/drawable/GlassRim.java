/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.blur3.drawable;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import androidx.annotation.ColorInt;
import androidx.annotation.Px;
import androidx.core.graphics.ColorUtils;

public final class GlassRim {
    public static final float LIGHT_TOP = 0;
    public static final float LIGHT_TOP_LEFT = 45;

    public final float lightAngle;

    public final @Px float topWidth;
    public final @ColorInt int topColor;
    public final @Px float bottomWidth;
    public final @ColorInt int bottomColor;

    public final @Px float ringWidth;
    public final @ColorInt int ringColor;
    public final float ringTop, ringBottom, ringSides;
    public final float ringExp;

    public final @Px float strokeWidth;
    public final @ColorInt int strokeColor;

    public GlassRim(float lightAngle,
                    float topWidth, int topColor, float bottomWidth, int bottomColor,
                    float ringWidth, int ringColor, float ringTop, float ringBottom, float ringSides, float ringExp) {
        this(lightAngle, topWidth, topColor, bottomWidth, bottomColor, ringWidth, ringColor, ringTop, ringBottom, ringSides, ringExp, 0, 0);
    }

    public GlassRim(float lightAngle,
                    float topWidth, int topColor, float bottomWidth, int bottomColor,
                    float ringWidth, int ringColor, float ringTop, float ringBottom, float ringSides, float ringExp,
                    float strokeWidth, int strokeColor) {
        this.lightAngle = lightAngle;
        this.topWidth = topWidth;
        this.topColor = topColor;
        this.bottomWidth = bottomWidth;
        this.bottomColor = bottomColor;
        this.ringWidth = ringWidth;
        this.ringColor = ringColor;
        this.ringTop = ringTop;
        this.ringBottom = ringBottom;
        this.ringSides = ringSides;
        this.ringExp = ringExp;
        this.strokeWidth = strokeWidth;
        this.strokeColor = strokeColor;
    }

    public static GlassRim of(float lightAngle, float topWidth, int topColor, float bottomWidth, int bottomColor, float outlineWidth, int outlineColor) {
        return new GlassRim(lightAngle, topWidth, topColor, bottomWidth, bottomColor, outlineWidth, outlineColor, 1, 1, 1, 0);
    }

    public static GlassRim evenOf(float topWidth, int topColor, float bottomWidth, int bottomColor, float outlineWidth, int outlineColor) {
        return new GlassRim(LIGHT_TOP, 0, 0, 0, 0, outlineWidth, outlineColor, 1, 1, 1, 0,
            (topWidth + bottomWidth) / 2f, ColorUtils.blendARGB(topColor, bottomColor, .5f));
    }

    public GlassRim withLightAngle(float lightAngle) {
        if (this.lightAngle == lightAngle) {
            return this;
        }
        return new GlassRim(lightAngle, topWidth, topColor, bottomWidth, bottomColor, ringWidth, ringColor, ringTop, ringBottom, ringSides, ringExp, strokeWidth, strokeColor);
    }

    boolean hasStroke() {
        return strokeWidth > 0.05f && (strokeColor >>> 24) != 0;
    }

    boolean hasTop() {
        return topWidth > 0.05f && (topColor >>> 24) != 0;
    }

    boolean hasBottom() {
        return bottomWidth > 0.05f && (bottomColor >>> 24) != 0;
    }

    boolean hasRing() {
        return ringWidth > 0.05f && (ringColor >>> 24) != 0 && (ringTop > 0 || ringBottom > 0 || ringSides > 0);
    }

    boolean isEvenRing() {
        return ringTop == ringSides && ringBottom == ringSides;
    }

    public static GlassRim qrBig() {
        return qr(1.1f, 27, 1.05f, 23, 1.15f, 33, 2, 3);
    }

    public static GlassRim qrSmall() {
        return qr(0.83f, 27, 0.79f, 23, 0.86f, 33, 2, 3);
    }

    public static GlassRim qrSheet() {
        return qr(1.1f, 27, 1.05f, 23, 1.15f, 18, 2, 3);
    }

    public static GlassRim qrSwatch() {
        return qr(0.83f, 13, 0.79f, 11, 0.86f, 16, 1, 1.5f);
    }

    private static GlassRim qr(float topDp, int topWhite, float bottomDp, int bottomWhite, float ringDp, int ringSides, float ringTop, float ringBottom) {
        return new GlassRim(LIGHT_TOP,
            dpf2(topDp), white(topWhite), dpf2(bottomDp), white(bottomWhite),
            dpf2(ringDp), 0xFF000000, ringTop / 100f, ringBottom / 100f, ringSides / 100f, 3.8f);
    }

    private static int white(int percent) {
        return (Math.round(255 * percent / 100f) << 24) | 0xFFFFFF;
    }
}
