/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.telegram.messenger.AndroidUtilities;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.icons.IconResources;

public final class PollTimerIconRenderer {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final MediaActionDrawable.PackIconRenderer renderer =
            new MediaActionDrawable.PackIconRenderer(true);

    public boolean draw(Canvas canvas, Paint foreground, float cx, float cy, float radius,
                        boolean drawFace, float transition) {
        IconResources.NavigationIcons icons = IconResources.activeNavigationIcons();
        int selection = icons == null ? AppConfig.ICON_REPLACE_NONE : icons.selection;
        if (selection != AppConfig.ICON_REPLACE_SOLAR
                && selection != AppConfig.ICON_REPLACE_LIQUID_GLASS
                && selection != AppConfig.ICON_REPLACE_PLUMPY) {
            return false;
        }
        int alpha = foreground.getAlpha();
        if (radius <= 0) {
            return true;
        }
        paint.set(foreground);
        if (selection == AppConfig.ICON_REPLACE_SOLAR) {
            paint.setStrokeWidth(foreground.getStrokeWidth() * (0.85f / 1.1f));
        }
        if (drawFace && alpha > 0) {
            if (selection == AppConfig.ICON_REPLACE_PLUMPY) {
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(Math.round(alpha * (114f / 255f)));
                canvas.drawCircle(cx, cy, radius + foreground.getStrokeWidth() / 2f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setAlpha(alpha);
            } else if (selection == AppConfig.ICON_REPLACE_LIQUID_GLASS) {
                bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
                renderer.drawProgressArc(canvas, bounds, 0, 360, paint, selection);
            } else {
                canvas.drawCircle(cx, cy, radius, paint);
            }
        }
        float progress = Math.max(0f, Math.min(1f, transition));
        if (progress > 0f) {
            int detailAlpha = (int) (alpha * progress);
            paint.setAlpha(selection == AppConfig.ICON_REPLACE_LIQUID_GLASS
                    ? Math.round(detailAlpha * (230f / 255f)) : detailAlpha);
            canvas.drawLine(cx - AndroidUtilities.dp(2.1f) * progress, cy - AndroidUtilities.dp(7.5f),
                    cx + AndroidUtilities.dp(2.1f) * progress, cy - AndroidUtilities.dp(7.5f), paint);
            canvas.drawLine(cx, cy - AndroidUtilities.dp(3) * progress, cx, cy, paint);
        }
        return true;
    }
}
