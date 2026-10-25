/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.ui.Components;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import app.nimarkogram.messenger.icons.IconResources;
import org.telegram.messenger.R;

public final class MediaControlIconHelper {
    private MediaControlIconHelper() { }

    public static void setImageResource(ImageView view, int stockId) {
        Drawable fitted = getMissingPackDrawable(view.getContext(), stockId);
        if (fitted == null) {
            view.setImageResource(stockId);
        } else {
            view.setImageDrawable(fitted);
        }
    }

    public static Drawable getDrawableBounded(Context context, int stockId) {
        Drawable fitted = getMissingPackDrawable(context, stockId);
        return fitted == null ? context.getResources().getDrawable(stockId, context.getTheme())
                : new BoundedSlotDrawable(fitted);
    }

    static Drawable getMissingPackDrawable(Context context, int stockId) {
        final int role = semanticRole(stockId);
        final Resources resources = context.getResources();
        if (role == 0 || !(resources instanceof IconResources)
                || ((IconResources) resources).hasReplacement(stockId)
                || !((IconResources) resources).hasReplacement(role)) {
            return null;
        }
        try {
            Drawable stock = IconResources.getStockDrawable(context, stockId).mutate();
            Drawable icon = resources.getDrawable(role, context.getTheme()).mutate();
            return fitToStock(icon, stock);
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            return null;
        }
    }

    private static int semanticRole(int stockId) {
        if (stockId == R.drawable.pip_play_large || stockId == R.drawable.ic_play) {
            return R.drawable.ic_action_play;
        }
        if (stockId == R.drawable.pip_pause_large || stockId == R.drawable.ic_pause
                || stockId == R.drawable.msg_round_pause_m) {
            return R.drawable.ic_action_pause;
        }
        if (stockId == R.drawable.pip_replay_large || stockId == R.drawable.ic_again) {
            return R.drawable.msg_reset;
        }
        if (stockId == R.drawable.pip_video_close) {
            return R.drawable.ic_close_white;
        }
        if (stockId == R.drawable.pip_video_expand || stockId == R.drawable.ic_gofullscreen) {
            return R.drawable.voice_expand;
        }
        if (stockId == R.drawable.ic_outfullscreen) {
            return R.drawable.voice_minimize;
        }
        if (stockId == R.drawable.video_mute || stockId == R.drawable.video_muted) {
            return R.drawable.media_unmute;
        }
        return 0;
    }

    private static Drawable fitToStock(Drawable icon, Drawable stock) {
        final int width = stock.getIntrinsicWidth(), height = stock.getIntrinsicHeight();
        final int iconWidth = icon.getIntrinsicWidth(), iconHeight = icon.getIntrinsicHeight();
        if (width <= 0 || height <= 0 || iconWidth <= 0 || iconHeight <= 0) {
            return null;
        }
        final float[] nativeInk = inkBounds(stock), packInk = inkBounds(icon);
        if (nativeInk == null || packInk == null) {
            return null;
        }
        final float fit = Math.min(Math.min((float) width / iconWidth, (float) height / iconHeight),
                Math.min((nativeInk[2] - nativeInk[0]) / (packInk[2] - packInk[0]),
                        (nativeInk[3] - nativeInk[1]) / (packInk[3] - packInk[1])));
        CombinedDrawable result = new CombinedDrawable(new ColorDrawable(Color.TRANSPARENT), icon);
        result.setCustomSize(width, height);
        result.setIconSize(Math.max(1, Math.round(iconWidth * fit)), Math.max(1, Math.round(iconHeight * fit)));
        result.setIconOffset(
                Math.round((nativeInk[0] + nativeInk[2] - width
                        - (packInk[0] + packInk[2] - iconWidth) * fit) / 2f),
                Math.round((nativeInk[1] + nativeInk[3] - height
                        - (packInk[1] + packInk[3] - iconHeight) * fit) / 2f));
        return result;
    }

    private static float[] inkBounds(Drawable drawable) {
        final int width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
        final float scale = Math.min(1f, 128f / Math.max(width, height));
        final int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        final Rect previous = new Rect(drawable.getBounds());
        final Bitmap sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0, 0, w, h);
            drawable.draw(new Canvas(sample));
            final int[] pixels = new int[w * h];
            sample.getPixels(pixels, 0, w, 0, 0, w, h);
            int left = w, top = h, right = -1, bottom = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (Color.alpha(pixels[y * w + x]) > 25) {
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
            if (right < left || bottom < top) return null;
            return new float[]{left * (float) width / w, top * (float) height / h,
                    (right + 1) * (float) width / w, (bottom + 1) * (float) height / h};
        } finally {
            drawable.setBounds(previous);
            sample.recycle();
        }
    }

    private static final class BoundedSlotDrawable extends Drawable implements Drawable.Callback {
        private final Drawable fitted;
        private final int width, height;

        BoundedSlotDrawable(Drawable fitted) {
            this.fitted = fitted;
            width = fitted.getIntrinsicWidth();
            height = fitted.getIntrinsicHeight();
            fitted.setBounds(0, 0, width, height);
            fitted.setCallback(this);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;
            int save = canvas.save();
            try {
                canvas.translate(bounds.left, bounds.top);
                canvas.scale((float) bounds.width() / width, (float) bounds.height() / height);
                fitted.draw(canvas);
            } finally {
                canvas.restoreToCount(save);
            }
        }

        @Override public void setAlpha(int alpha) { fitted.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { fitted.setColorFilter(filter); }
        @Override public int getOpacity() { return fitted.getOpacity(); }
        @Override public int getIntrinsicWidth() { return width; }
        @Override public int getIntrinsicHeight() { return height; }
        @Override public int getMinimumWidth() { return width; }
        @Override public int getMinimumHeight() { return height; }
        @Override public boolean isStateful() { return fitted.isStateful(); }
        @Override protected boolean onStateChange(int[] state) { return fitted.setState(state); }
        @Override public void jumpToCurrentState() { fitted.jumpToCurrentState(); }
        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    }
}
