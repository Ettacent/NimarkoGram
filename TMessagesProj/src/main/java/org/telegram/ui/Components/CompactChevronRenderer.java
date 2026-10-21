/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import org.telegram.messenger.R;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.icons.NimarkoIconResources;

public final class CompactChevronRenderer {
    public static final int DOWN = 0;
    public static final int RIGHT = -90;
    public static final int LEFT = 90;

    private static final int MAX_BITMAP_SIDE = 256;
    private final int[] pixelRow = new int[MAX_BITMAP_SIDE];
    private final Rect ink = new Rect();
    private final RectF nativeBounds = new RectF();
    private final RectF bitmapBounds = new RectF();
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint colorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap measuredBitmap;
    private boolean hasInk;

    public CompactChevronRenderer() {
        colorPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    }

    public boolean draw(Canvas canvas, Path nativePath, Paint foreground, int direction) {
        if (nativePath.isEmpty() || foreground.getXfermode() != null) {
            return false;
        }
        final NimarkoIconResources.NavigationIcons icons = NimarkoIconResources.activeNavigationIcons();
        final NimarkoIconResources.PreparedIcon icon = icons == null
                || icons.selection == NimarkoConfig.ICON_REPLACE_NONE
                ? null : icons.getIcon(R.drawable.arrow_more);
        final Bitmap bitmap = icon == null ? null : icon.bitmap;
        if (bitmap != measuredBitmap) {
            measuredBitmap = bitmap;
            hasInk = measureInk(bitmap);
        }
        if (!hasInk || bitmap == null || bitmap.isRecycled()
                || (direction != DOWN && direction != RIGHT && direction != LEFT)) {
            return false;
        }

        nativePath.computeBounds(nativeBounds, true);
        final float halfStroke = foreground.getStrokeWidth() / 2f;
        nativeBounds.inset(-halfStroke, -halfStroke);
        final float width = nativeBounds.width(), height = nativeBounds.height();
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (foreground.getAlpha() == 0) {
            return true;
        }
        final boolean sideways = direction != DOWN;
        final float scale = Math.min(
                (sideways ? height : width) / ink.width(),
                (sideways ? width : height) / ink.height());
        final float halfWidth = ink.width() * scale / 2f;
        final float halfHeight = ink.height() * scale / 2f;
        bitmapBounds.set(-halfWidth, -halfHeight, halfWidth, halfHeight);
        bitmapPaint.setAlpha(foreground.getAlpha());

        final int layer = canvas.saveLayer(nativeBounds, null);
        try {
            canvas.clipRect(nativeBounds);
            final int transform = canvas.save();
            try {
                canvas.translate(nativeBounds.centerX(), nativeBounds.centerY());
                if (direction == LEFT) {
                    canvas.scale(-1f, 1f);
                }
                canvas.rotate(direction == DOWN ? DOWN : RIGHT);
                canvas.drawBitmap(bitmap, ink, bitmapBounds, bitmapPaint);
            } finally {
                canvas.restoreToCount(transform);
            }
            colorPaint.setColor(foreground.getColor() | 0xff000000);
            colorPaint.setColorFilter(foreground.getColorFilter());
            colorPaint.setShader(foreground.getShader());
            canvas.drawRect(nativeBounds, colorPaint);
            return true;
        } catch (RuntimeException failure) {
            hasInk = false;
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            return false;
        } finally {
            colorPaint.setShader(null);
            colorPaint.setColorFilter(null);
            canvas.restoreToCount(layer);
        }
    }

    private boolean measureInk(Bitmap bitmap) {
        ink.setEmpty();
        if (bitmap == null || bitmap.isRecycled()) {
            return false;
        }
        final int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width <= 0 || height <= 0 || width > MAX_BITMAP_SIDE || height > MAX_BITMAP_SIDE) {
            return false;
        }
        int left = width, top = height, right = -1, bottom = -1;
        try {
            for (int y = 0; y < height; y++) {
                bitmap.getPixels(pixelRow, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    if ((pixelRow[x] >>> 24) != 0) {
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
        } catch (RuntimeException failure) {
            return false;
        }
        if (right < left || bottom < top || bitmap.isRecycled()) {
            return false;
        }
        ink.set(left, top, right + 1, bottom + 1);
        return true;
    }
}
