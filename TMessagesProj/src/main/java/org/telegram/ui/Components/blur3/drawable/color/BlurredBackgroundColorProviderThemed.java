/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.blur3.drawable.color;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import android.graphics.Color;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import org.telegram.ui.ActionBar.ThemeBind;
public class BlurredBackgroundColorProviderThemed implements BlurredBackgroundColorProvider {

    private final Theme.ResourcesProvider resourcesProvider;
    private final int backgroundColorId;
    private float alpha;
    private boolean useDefaultAlpha;

    private int readableTextKey = -1, readableSecondaryKey = -1;
    private final app.nimarkogram.messenger.utils.ui.MonetHelper.GlassContrast glassContrast =
            new app.nimarkogram.messenger.utils.ui.MonetHelper.GlassContrast();
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

    public BlurredBackgroundColorProviderThemed setReadableTextColors(int primary, int secondary) {
        readableTextKey = primary;
        readableSecondaryKey = secondary;
        return this;
    }

    protected int readableBackgroundColor(int color) {
        return readableTextKey < 0 ? color : glassContrast.color(color,
                Theme.getColor(readableTextKey, resourcesProvider), Theme.getColor(readableSecondaryKey, resourcesProvider));
    }

    private int backgroundColor, shadowColor, strokeColorTop, strokeColorBottom, strokeColorFull;

    private int compactOutlineKey = -1;

    public BlurredBackgroundColorProviderThemed setCompactOutlineKey(int key) {
        compactOutlineKey = key;
        updateColors();
        return this;
    }

    private boolean isCompactGlass() {
        return compactOutlineKey >= 0 && Theme.isCompactMode(resourcesProvider);
    }

    private boolean hasCompactOutline() {
        return isCompactGlass() && Color.alpha(Theme.getColor(compactOutlineKey, resourcesProvider)) != 0;
    }

    @Override
    public int getOutlineColor() {
        return isCompactGlass() ? Theme.getColor(compactOutlineKey, resourcesProvider) : 0;
    }

    @Override
    public float getOutlineWidth() {
        return AndroidUtilities.dpf2(0.6f);
    }

    @Override
    public int getOutlineShadowColor() {
        return isCompactGlass() ? Theme.getColor(Theme.key_chat_compact_glassHairline, resourcesProvider) : 0;
    }

    @Override
    public float getShadowRadiusOverride() {
        return isCompactGlass() ? AndroidUtilities.dpf2(2.25f) : -1;
    }

    @Override
    public float getShadowDyOverride() {
        return isCompactGlass() ? AndroidUtilities.dpf2(1) : -1;
    }
    public boolean isDark() {
        final int color = Theme.getColor(backgroundColorId, resourcesProvider);
        return AndroidUtilities.computePerceivedBrightness(color) < .721f;
    }

    private int themeGeneration = ThemeBind.generation();

    private void checkThemeGeneration() {
        final int generation = ThemeBind.generation();
        if (themeGeneration != generation) {
            updateColors();
        }
    }
    public void updateColors() {
        if (useDefaultAlpha) {
            alpha = org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory.isLiquidGlassEnabled() ? 0.85f : 0.76f;
        }
        themeGeneration = ThemeBind.generation();
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
        checkThemeGeneration();
        if (isCompactGlass()) {
            return Theme.getColor(Theme.key_chat_compact_glassShadow, resourcesProvider);
        }
        return shadowColor;
    }

    @Override
    public int getBackgroundColor() {
        return readableBackgroundColor(getRawBackgroundColor());
    }

    protected int getRawBackgroundColor() {
        checkThemeGeneration();
        return backgroundColor;
    }

    @Override
    public int getStrokeColorTop() {
        checkThemeGeneration();
        if (hasCompactOutline()) return 0;
        return strokeColorTop;
    }

    @Override
    public int getStrokeColorBottom() {
        checkThemeGeneration();
        if (hasCompactOutline()) return 0;
        return strokeColorBottom;
    }

    @Override
    public int getStrokeColorFull() {
        checkThemeGeneration();
        return strokeColorFull;
    }
}

