/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.glass;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import java.util.Objects;

public abstract class GlassDrawable extends Drawable {

    public abstract void updateColors();

    public abstract void buildDisplayList();

    public abstract boolean hasDisplayList();

    public abstract boolean shouldHaveDisplayList();

    public void getBoundsWithPaddingInset(@NonNull Rect rect) {
        rect.set(getBounds());
    }

    public boolean isInvalidated() {
        return false;
    }

    private final Rect reservedBounds = new Rect();

    public final void setReservedBounds(int left, int top, int right, int bottom) {
        reservedBounds.set(left, top, right, bottom);
    }

    public final void clearReservedBounds() {
        reservedBounds.setEmpty();
    }

    public final boolean getReservedBounds(@NonNull Rect rect) {
        rect.set(reservedBounds);
        return !reservedBounds.isEmpty();
    }

    public int getOutsetX() {
        return 0;
    }

    public int getOutsetY() {
        return 0;
    }

    private GlassSettings glassSettings = GlassEngine.getCurrentGlassSettings();

    public final void setGlassSettings(GlassSettings glassSettings) {
        if (!Objects.equals(this.glassSettings, glassSettings)) {
            this.glassSettings = glassSettings;
            onGlassSettingsChanged(glassSettings);
        }
    }

    public final GlassSettings getGlassSettings() {
        return glassSettings;
    }

    protected void onGlassSettingsChanged(GlassSettings glassSettings) {

    }

    private int alpha = 255;

    @Override
    public final void setAlpha(int alpha) {
        if (this.alpha != alpha) {
            final int oldAlpha = this.alpha;
            this.alpha = alpha;
            onAlphaChanged(oldAlpha, alpha);
        }
    }

    @Override
    public final int getAlpha() {
        return alpha;
    }

    protected void onAlphaChanged(int oldAlpha, int newAlpha) {

    }

    private float sourceOffsetX, sourceOffsetY;

    public final void setSourceOffset(float sourceOffsetX, float sourceOffsetY) {
        if (this.sourceOffsetX != sourceOffsetX || this.sourceOffsetY != sourceOffsetY) {
            this.sourceOffsetX = sourceOffsetX;
            this.sourceOffsetY = sourceOffsetY;
            onSourceOffsetChange(sourceOffsetX, sourceOffsetY);
        }
    }

    public final float getSourceOffsetX() {
        return sourceOffsetX;
    }

    public final float getSourceOffsetY() {
        return sourceOffsetY;
    }

    protected void onSourceOffsetChange(float sourceOffsetX, float sourceOffsetY) {

    }
}
