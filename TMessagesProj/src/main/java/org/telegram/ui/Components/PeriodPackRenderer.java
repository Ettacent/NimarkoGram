/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.icons.IconResources;

public final class PeriodPackRenderer {
    private final MediaActionDrawable.PackIconRenderer renderer =
            new MediaActionDrawable.PackIconRenderer(true);
    private final Paint contourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF fillBounds = new RectF();

    public int selection() {
        IconResources.NavigationIcons icons = IconResources.activeNavigationIcons();
        if (icons == null) return AppConfig.ICON_REPLACE_NONE;
        int selection = icons.selection;
        return selection == AppConfig.ICON_REPLACE_SOLAR
                || selection == AppConfig.ICON_REPLACE_LIQUID_GLASS
                || selection == AppConfig.ICON_REPLACE_PLUMPY
                ? selection : AppConfig.ICON_REPLACE_NONE;
    }

    public void drawArc(Canvas canvas, RectF bounds, float start, float sweep,
                        Paint foreground, int selection) {
        if (selection == AppConfig.ICON_REPLACE_NONE || foreground.getXfermode() != null) {
            canvas.drawArc(bounds, start, sweep, false, foreground);
            return;
        }
        contourPaint.set(foreground);
        float weight = selection == AppConfig.ICON_REPLACE_SOLAR ? 0.82f : 1.2f;
        if (selection == AppConfig.ICON_REPLACE_PLUMPY) {
            contourPaint.setStrokeWidth(foreground.getStrokeWidth() * 1.35f);
            contourPaint.setAlpha(Math.round(foreground.getAlpha() * (Math.abs(sweep) > 90f ? 0.4f : 1f)));
            canvas.drawArc(bounds, start, sweep, false, contourPaint);
            return;
        }
        contourPaint.setStrokeWidth(foreground.getStrokeWidth() * weight);
        renderer.drawProgressArc(canvas, bounds, start, sweep, contourPaint, selection);
    }

    public void drawCircle(Canvas canvas, float cx, float cy, float radius,
                           Paint foreground, int selection) {
        if (foreground.getXfermode() != null || selection == AppConfig.ICON_REPLACE_NONE) {
            canvas.drawCircle(cx, cy, radius, foreground);
            return;
        }
        if (selection == AppConfig.ICON_REPLACE_SOLAR
                || selection == AppConfig.ICON_REPLACE_PLUMPY) {
            canvas.drawCircle(cx, cy, radius, foreground);
            detailPaint.set(foreground);
            detailPaint.setShader(null);
            detailPaint.setColor(0xffffffff);
            detailPaint.setAlpha(Math.round(foreground.getAlpha() * 0.2f));
            if (selection == AppConfig.ICON_REPLACE_SOLAR) {
                detailPaint.setStyle(Paint.Style.STROKE);
                detailPaint.setStrokeWidth(radius * 0.09f);
                canvas.drawCircle(cx, cy, radius * 0.84f, detailPaint);
            } else {
                detailPaint.setStyle(Paint.Style.FILL);
                fillBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
                canvas.drawArc(fillBounds, 180, 180, true, detailPaint);
            }
            return;
        }
        if (selection != AppConfig.ICON_REPLACE_LIQUID_GLASS) {
            canvas.drawCircle(cx, cy, radius, foreground);
            return;
        }
        fillBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        renderer.drawProgressArc(canvas, fillBounds, 0, 360, foreground, selection);
    }
}
