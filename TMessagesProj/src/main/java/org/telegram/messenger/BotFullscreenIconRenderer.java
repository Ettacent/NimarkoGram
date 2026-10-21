/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.messenger;

import android.graphics.Canvas;
import android.graphics.Paint;

import org.telegram.ui.Components.BoundedPackIconRenderer;

import app.nimarkogram.messenger.icons.NimarkoIconResources;

final class BotFullscreenIconRenderer {
    private final BoundedPackIconRenderer close = new BoundedPackIconRenderer();
    private final BoundedPackIconRenderer back = new BoundedPackIconRenderer();
    private final BoundedPackIconRenderer collapse = new BoundedPackIconRenderer();
    private final BoundedPackIconRenderer overflow = new BoundedPackIconRenderer();
    private final BoundedPackIconRenderer download = new BoundedPackIconRenderer();
    private NimarkoIconResources.NavigationIcons snapshot;

    void update() {
        snapshot = NimarkoIconResources.activeNavigationIcons();
    }

    boolean drawCloseBack(Canvas canvas, Paint source, float progress, float radius, float shaft) {
        boolean closeReady = prepare(close, R.drawable.ic_close_white);
        boolean backReady = prepare(back, R.drawable.ic_ab_back);
        if (!closeReady || !backReady) return false;
        float p = Math.max(0f, Math.min(1f, progress));
        float left = -radius * (1f - p);
        float right = Math.max(radius, shaft * p);
        float width = right - left + source.getStrokeWidth();
        float height = 2f * radius + source.getStrokeWidth();
        float cx = (left + right) / 2f;
        close.draw(canvas, source, cx, 0, width, height, 1f - p, false);
        back.draw(canvas, source, cx, 0, width, height, p, false);
        return true;
    }

    boolean drawCollapse(Canvas canvas, Paint source, float halfWidth, float halfHeight) {
        if (!prepare(collapse, R.drawable.arrow_more)) return false;
        collapse.draw(canvas, source, 0, 0,
                2f * halfWidth + source.getStrokeWidth(),
                2f * halfHeight + source.getStrokeWidth(), 1f, false);
        return true;
    }

    boolean drawOverflow(Canvas canvas, Paint source, float radius, float spacing) {
        if (!prepare(overflow, R.drawable.ic_ab_other)) return false;
        overflow.draw(canvas, source, 0, 0, 2f * radius, 2f * (spacing + radius), 1f, false);
        return true;
    }

    boolean drawDownload(Canvas canvas, Paint source) {
        if (!prepare(download, R.drawable.msg_download)) return false;
        download.draw(canvas, source, 0, 0,
                AndroidUtilities.dpf2(7), AndroidUtilities.dpf2(7), 1f, false);
        return true;
    }

    private boolean prepare(BoundedPackIconRenderer renderer, int id) {
        try {
            return renderer.prepare(snapshot, id);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
