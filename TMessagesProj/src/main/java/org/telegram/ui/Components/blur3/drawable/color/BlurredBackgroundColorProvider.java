/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.blur3.drawable.color;

import androidx.annotation.ColorInt;

import androidx.annotation.Px;
public interface BlurredBackgroundColorProvider {
    @ColorInt int getShadowColor();
    @ColorInt int getBackgroundColor();
    @ColorInt int getStrokeColorTop();
    @ColorInt int getStrokeColorBottom();

    @ColorInt default int getStrokeColorFull() { return 0; }
    default @ColorInt int getOutlineShadowColor() {
        return 0;
}
    default @ColorInt int getOutlineColor() {
        return 0;
    }

    default @Px float getOutlineWidth() {
        return 0;
    }

    default @Px float getShadowRadiusOverride() {
        return -1;
    }

    default @Px float getShadowDyOverride() {
        return -1;
    }
}
