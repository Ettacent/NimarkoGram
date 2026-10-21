/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import app.nimarkogram.messenger.icons.NimarkoIconResources;

public final class BoundedPackIconRenderer {
    private NimarkoIconResources.PreparedIcon icon;
    private boolean ready;
    private final Rect ink = new Rect();
    private final RectF target = new RectF();
    private final int[] row = new int[256];
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BoundedPackIconRenderer() {
        tintPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    }

    public boolean prepare(NimarkoIconResources.NavigationIcons batch, int id) {
        ready = false;
        final NimarkoIconResources.PreparedIcon next = batch == null ? null : batch.getIcon(id);
        if (next == null || next.bitmap == null || next.bitmap.isRecycled()) return false;
        if (icon != next) {
            final int width = next.bitmap.getWidth(), height = next.bitmap.getHeight();
            if (width <= 0 || height <= 0 || width > row.length || height > row.length) return false;
            int left = width, top = height, right = 0, bottom = 0;
            try {
                for (int y = 0; y < height; y++) {
                    next.bitmap.getPixels(row, 0, width, 0, y, width, 1);
                    for (int x = 0; x < width; x++) {
                        if ((row[x] >>> 24) != 0) {
                            left = Math.min(left, x);
                            top = Math.min(top, y);
                            right = Math.max(right, x + 1);
                            bottom = Math.max(bottom, y + 1);
                        }
                    }
                }
            } catch (RuntimeException unreadable) {
                return false;
            }
            ink.set(left, top, right, bottom);
            icon = next;
        }
        return ready = !ink.isEmpty();
    }

    public void draw(Canvas canvas, Paint foreground, float cx, float cy, float width, float height,
                     float opacity, boolean turnLeft) {
        if (!ready || icon == null || icon.bitmap == null || icon.bitmap.isRecycled()
                || ink.isEmpty() || foreground.getXfermode() != null) return;
        if (opacity <= 0f || foreground.getAlpha() == 0 || width <= 0f || height <= 0f) return;
        final float scale = Math.min((turnLeft ? height : width) / ink.width(),
                (turnLeft ? width : height) / ink.height());
        final float halfWidth = ink.width() * scale / 2f, halfHeight = ink.height() * scale / 2f;
        final int save = canvas.save();
        canvas.translate(cx, cy);
        if (turnLeft) canvas.rotate(90f);
        target.set(-halfWidth, -halfHeight, halfWidth, halfHeight);
        canvas.saveLayer(target, null);
        bitmapPaint.setAlpha(Math.round(foreground.getAlpha() * opacity));
        canvas.drawBitmap(icon.bitmap, ink, target, bitmapPaint);
        tintPaint.setColor(foreground.getColor() | 0xff000000);
        tintPaint.setColorFilter(foreground.getColorFilter());
        tintPaint.setShader(foreground.getShader());
        canvas.drawRect(target, tintPaint);
        tintPaint.setShader(null);
        canvas.restoreToCount(save);
    }
}
