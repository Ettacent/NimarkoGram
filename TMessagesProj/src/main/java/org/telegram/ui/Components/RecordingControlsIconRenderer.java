/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;

import app.nimarkogram.messenger.icons.IconResources;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

final class RecordingControlsIconRenderer {
    private final MediaActionDrawable.PackIconRenderer renderer =
            new MediaActionDrawable.PackIconRenderer(true);

    private final BoundedPackIconRenderer pauseRenderer = new BoundedPackIconRenderer();

    boolean drawLockPause(Canvas canvas, Paint foreground, float cx, float cy, float progress,
                          float width, float height) {
        IconResources.NavigationIcons icons = IconResources.activeNavigationIcons();
        IconResources.PreparedIcon lock =
                MediaActionDrawable.PackIconRenderer.get(icons, R.drawable.msg_secret);
        boolean pauseReady = pauseRenderer.prepare(icons, R.drawable.ic_action_pause);
        if (lock == null || !pauseReady) {
            return false;
        }
        float t = Math.max(0f, Math.min(1f, progress));
        float alpha = foreground.getAlpha() / 255f;
        renderer.draw(canvas, lock, foreground, cx,
                cy - AndroidUtilities.dpf2(4) * (1f - t),
                AndroidUtilities.dpf2(24), alpha * (1f - t), 0);
        pauseRenderer.draw(canvas, foreground, cx, cy, width, height, t, false);
        return true;
    }
}
