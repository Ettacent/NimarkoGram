/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;

import app.nimarkogram.messenger.icons.IconResources;

public final class IconPackCanvasRenderer {
    private final MediaActionDrawable.PackIconRenderer renderer =
            new MediaActionDrawable.PackIconRenderer(true);

    public boolean draw(Canvas canvas, int resourceId, Paint foreground,
                        float cx, float cy, float size) {
        IconResources.PreparedIcon icon = MediaActionDrawable.PackIconRenderer.get(
                IconResources.activeNavigationIcons(), resourceId);
        if (icon == null) {
            return false;
        }
        renderer.draw(canvas, icon, foreground, cx, cy, size, foreground.getAlpha() / 255f, 0);
        return true;
    }
}
