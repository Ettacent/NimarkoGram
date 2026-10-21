/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import org.telegram.ui.Components.CombinedDrawable;

public final class CompactIconHelper {
    private CompactIconHelper() { }

    public static Drawable getDrawable(Context context, int resourceId) {
        Resources resources = context.getResources();
        Drawable icon = resources.getDrawable(resourceId, context.getTheme()).mutate();
        if (!(resources instanceof NimarkoIconResources)
                || !((NimarkoIconResources) resources).hasReplacement(resourceId)) {
            return icon;
        }
        Drawable stock = NimarkoIconResources.getStockDrawable(context, resourceId).mutate();
        return fitActionIcon(icon, stock);
    }

    private static Drawable fitActionIcon(Drawable icon, Drawable stock) {
        float[] stockInk = getActionIconInkSize(stock);
        float[] iconInk = getActionIconInkSize(icon);
        if (stockInk[0] <= 0 || stockInk[1] <= 0 || iconInk[0] <= 0 || iconInk[1] <= 0) {
            return icon;
        }
        float scale = Math.min(1f, Math.min(stockInk[0] / iconInk[0], stockInk[1] / iconInk[1]));
        CombinedDrawable result = new CombinedDrawable(new ColorDrawable(Color.TRANSPARENT), icon);
        result.setCustomSize(stock.getIntrinsicWidth(), stock.getIntrinsicHeight());
        result.setIconSize(Math.max(1, Math.round(icon.getIntrinsicWidth() * scale)),
                Math.max(1, Math.round(icon.getIntrinsicHeight() * scale)));
        return result;
    }

    private static float[] getActionIconInkSize(Drawable drawable) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            return new float[] { 0, 0 };
        }
        float sampleScale = Math.min(1f, 128f / Math.max(width, height));
        int sampleWidth = Math.max(1, Math.round(width * sampleScale));
        int sampleHeight = Math.max(1, Math.round(height * sampleScale));
        Bitmap sample = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
        Rect bounds = new Rect(drawable.getBounds());
        try {
            drawable.setBounds(0, 0, sampleWidth, sampleHeight);
            drawable.draw(new Canvas(sample));
            int[] pixels = new int[sampleWidth * sampleHeight];
            sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight);
            int left = sampleWidth, top = sampleHeight, right = -1, bottom = -1;
            for (int y = 0; y < sampleHeight; y++) {
                for (int x = 0; x < sampleWidth; x++) {
                    if (Color.alpha(pixels[y * sampleWidth + x]) > 25) {
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
            return new float[] {
                    Math.max(0, right - left + 1) * (float) width / sampleWidth,
                    Math.max(0, bottom - top + 1) * (float) height / sampleHeight
            };
        } finally {
            drawable.setBounds(bounds);
            sample.recycle();
        }
    }
}
