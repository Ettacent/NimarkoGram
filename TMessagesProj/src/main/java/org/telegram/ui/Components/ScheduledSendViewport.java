/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;

public final class ScheduledSendViewport {
    private final Rect area = new Rect(), clip = new Rect();
    private final Point origin = new Point(), rowOrigin = new Point();
    public int top, bottom;

    public boolean read(View list, View composer) {
        if (!list.getGlobalVisibleRect(area, origin)) return false;
        area.top = Math.max(area.top, origin.y + list.getPaddingTop());
        area.bottom = Math.min(area.bottom, origin.y + list.getHeight() - list.getPaddingBottom());
        if (composer != null && composer.isShown() && composer.getAlpha() > 0f
                && composer.getGlobalVisibleRect(clip) && Rect.intersects(area, clip)) {
            area.bottom = Math.min(area.bottom, clip.top);
        }
        top = area.top - origin.y;
        bottom = area.bottom - origin.y;
        return !area.isEmpty();
    }

    public boolean containsPaintedRow(View row) {
        if (row == null || !row.isShown() || !row.getGlobalVisibleRect(clip, rowOrigin) || !clip.intersect(area)) return false;
        float alpha = 1f, scale = 1f;
        for (View v = row; v != null; ) {
            alpha *= v.getAlpha();
            scale *= Math.abs(v.getScaleY());
            ViewParent parent = v.getParent();
            v = parent instanceof View ? (View) parent : null;
        }
        int height = Math.round(row.getHeight() * scale);
        return alpha >= .5f && height > 0 && clip.height() + 1 >= Math.min(height, area.height());
    }
}
