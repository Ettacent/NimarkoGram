package org.telegram.ui.Components.blur3.drawable.color;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

public class BlurredBackgroundColorProviderThemed implements BlurredBackgroundColorProvider {

    private final Theme.ResourcesProvider resourcesProvider;
    private final int backgroundColorId;
    private float alpha;
    private boolean useDefaultAlpha;

    public BlurredBackgroundColorProviderThemed(Theme.ResourcesProvider resourcesProvider, int backgroundColorId) {
        this(resourcesProvider, backgroundColorId, 0, true);
    }

    public BlurredBackgroundColorProviderThemed(Theme.ResourcesProvider resourcesProvider, int backgroundColorId, float alpha) {
        this(resourcesProvider, backgroundColorId, alpha, false);
    }
    private BlurredBackgroundColorProviderThemed(Theme.ResourcesProvider resourcesProvider, int backgroundColorId, float alpha, boolean useDefaultAlpha) {
        this.resourcesProvider = resourcesProvider;
        this.backgroundColorId = backgroundColorId;
        this.alpha = alpha;
        this.useDefaultAlpha = useDefaultAlpha;

        updateColors();
    }

    public void setAlpha(float alpha) {
        this.alpha = alpha;
        useDefaultAlpha = false;
        updateColors();
    }

    private int backgroundColor, shadowColor, strokeColorTop, strokeColorBottom, strokeColorFull;

    public boolean isDark() {
        final int color = Theme.getColor(backgroundColorId, resourcesProvider);
        return AndroidUtilities.computePerceivedBrightness(color) < .721f;
    }

    public void updateColors() {
        if (useDefaultAlpha) {
            alpha = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f;
        }
        final int color = Theme.getColor(backgroundColorId, resourcesProvider);
        backgroundColor = Theme.multAlpha(color, alpha);

        if (isDark()) {
            strokeColorTop = 0x28FFFFFF;
            strokeColorBottom = 0x14FFFFFF;
            shadowColor = 0;
        } else {
            strokeColorTop = 0xFFFFFFFF;
            strokeColorBottom = 0xFFFFFFFF;
            shadowColor = 0x20000000; 
        }
        
        strokeColorFull = Theme.getColor(Theme.key_divider, resourcesProvider);
    }

    @Override
    public int getShadowColor() {
        return shadowColor;
    }

    @Override
    public int getBackgroundColor() {
        return backgroundColor;
    }

    @Override
    public int getStrokeColorTop() {
        return strokeColorTop;
    }

    @Override
    public int getStrokeColorBottom() {
        return strokeColorBottom;
    }

    @Override
    public int getStrokeColorFull() {
        return strokeColorFull;
    }
}

