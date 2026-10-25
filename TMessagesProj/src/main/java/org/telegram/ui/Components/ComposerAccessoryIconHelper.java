/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.ui.Components;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import app.nimarkogram.messenger.icons.IconResources;
import org.telegram.messenger.R;

public final class ComposerAccessoryIconHelper {
    private ComposerAccessoryIconHelper() { }

    public static Drawable deleteDraft(Context context) {
        return new AccessoryDrawable(context, false);
    }

    public static Drawable lockBadge(Context context) {
        return new AccessoryDrawable(context, true);
    }

    private static final class AccessoryDrawable extends Drawable implements Drawable.Callback {
        private final Context context;
        private final boolean badge;
        private final int stockId;
        private final MediaActionDrawable.PackIconRenderer renderer;
        private final Paint foreground = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint material = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF nativeInk = new RectF(), packInk = new RectF(), fitted = new RectF();
        private Resources resources;
        private Configuration configuration;
        private long generation;
        private int densityDpi, width, height, alpha = 255;
        private float density;
        private ColorFilter filter;
        private Drawable icon;
        private IconResources.PreparedIcon prepared;
        private boolean preparedFits;

        AccessoryDrawable(Context context, boolean badge) {
            this.context = context;
            this.badge = badge;
            stockId = badge ? R.drawable.mini_switch_lock : R.drawable.menu_delete_old;
            renderer = badge ? new MediaActionDrawable.PackIconRenderer(true) : null;
            foreground.setColor(Color.WHITE);
            refresh();
        }

        private void refresh() {
            Resources next = context.getResources();
            long nextGeneration = next instanceof IconResources
                    ? ((IconResources) next).getReplacementGeneration() : -1L;
            if (icon != null && resources == next && generation == nextGeneration
                    && densityDpi == next.getDisplayMetrics().densityDpi
                    && density == next.getDisplayMetrics().density
                    && next.getConfiguration().equals(configuration)) {
                return;
            }
            Drawable stock = IconResources.getStockDrawable(context, stockId).mutate();
            width = stock.getIntrinsicWidth();
            height = stock.getIntrinsicHeight();
            Drawable replacement = stock;
            fitted.set(0, 0, width, height);
            nativeInk.setEmpty();
            try {
                if (badge) {
                    measure(stock, nativeInk);
                } else if (next instanceof IconResources) {
                    IconResources pack = (IconResources) next;
                    int role = pack.hasReplacement(stockId) ? stockId : R.drawable.msg_delete;
                    if (pack.hasReplacement(role)) {
                        Drawable candidate = next.getDrawable(role, context.getTheme()).mutate();
                        if (measure(stock, nativeInk) && measure(candidate, packInk)
                                && fit(nativeInk, packInk, width, height,
                                candidate.getIntrinsicWidth(), candidate.getIntrinsicHeight(), fitted)) {
                            replacement = candidate;
                        }
                    }
                }
            } catch (RuntimeException | OutOfMemoryError unavailable) {
                nativeInk.setEmpty();
                fitted.set(0, 0, width, height);
            }
            if (icon != null) icon.setCallback(null);
            icon = replacement;
            icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
            icon.setAlpha(alpha);
            icon.setColorFilter(filter);
            icon.setState(getState());
            icon.setLevel(getLevel());
            icon.setVisible(isVisible(), false);
            icon.setCallback(this);
            resources = next;
            generation = nextGeneration;
            densityDpi = next.getDisplayMetrics().densityDpi;
            density = next.getDisplayMetrics().density;
            configuration = new Configuration(next.getConfiguration());
            prepared = null;
            preparedFits = false;
        }

        private IconResources.PreparedIcon currentPrepared() {
            IconResources.NavigationIcons icons = IconResources.activeNavigationIcons();
            if (!(resources instanceof IconResources) || icons == null
                    || icons.generation != generation || icons.densityDpi != densityDpi
                    || icons.selection != ((IconResources) resources).getAppliedSelection()) {
                return null;
            }
            return MediaActionDrawable.PackIconRenderer.get(icons, R.drawable.msg_secret);
        }

        @Override
        public void draw(Canvas canvas) {
            refresh();
            Rect bounds = getBounds();
            if (width <= 0 || height <= 0 || bounds.isEmpty()) return;
            int save = canvas.save();
            try {
                canvas.translate(bounds.left, bounds.top);
                canvas.scale((float) bounds.width() / width, (float) bounds.height() / height);
                if (badge) {
                    IconResources.PreparedIcon next = currentPrepared();
                    if (next != prepared) {
                        prepared = next;
                        preparedFits = next != null && inkBounds(next.bitmap, packInk)
                                && fit(nativeInk, packInk, width, height,
                                next.bitmap.getWidth(), next.bitmap.getHeight(), fitted);
                    }
                    if (preparedFits) {
                        if (prepared.noTint) {
                            canvas.drawBitmap(prepared.bitmap, null, fitted, material);
                            return;
                        }
                        renderer.draw(canvas, prepared, foreground, fitted.centerX(), fitted.centerY(),
                                Math.max(fitted.width(), fitted.height()), foreground.getAlpha() / 255f, 0);
                        return;
                    }
                    icon.draw(canvas);
                } else {
                    canvas.translate(fitted.left, fitted.top);
                    canvas.scale(fitted.width() / icon.getIntrinsicWidth(),
                            fitted.height() / icon.getIntrinsicHeight());
                    icon.draw(canvas);
                }
            } finally {
                canvas.restoreToCount(save);
            }
        }

        @Override public void setAlpha(int value) {
            alpha = value;
            icon.setAlpha(value);
            foreground.setAlpha(value);
            material.setAlpha(value);
            invalidateSelf();
        }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(ColorFilter value) {
            filter = value;
            icon.setColorFilter(value);
            foreground.setColorFilter(value);
            invalidateSelf();
        }
        @Override public ColorFilter getColorFilter() { return filter; }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { refresh(); return width; }
        @Override public int getIntrinsicHeight() { refresh(); return height; }
        @Override public int getMinimumWidth() { return getIntrinsicWidth(); }
        @Override public int getMinimumHeight() { return getIntrinsicHeight(); }
        @Override public boolean isStateful() { return icon.isStateful(); }
        @Override protected boolean onStateChange(int[] state) { return icon.setState(state); }
        @Override protected boolean onLevelChange(int level) { return icon.setLevel(level); }
        @Override public boolean setVisible(boolean visible, boolean restart) {
            boolean changed = super.setVisible(visible, restart);
            return icon.setVisible(visible, restart) || changed;
        }
        @Override public void jumpToCurrentState() { icon.jumpToCurrentState(); }
        @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
    }

    private static boolean fit(RectF nativeInk, RectF packInk, int width, int height,
                               int iconWidth, int iconHeight, RectF result) {
        if (width <= 0 || height <= 0 || iconWidth <= 0 || iconHeight <= 0
                || nativeInk.isEmpty() || packInk.isEmpty()) return false;
        float scale = Math.min(Math.min((float) width / iconWidth, (float) height / iconHeight),
                Math.min(nativeInk.width() / packInk.width(), nativeInk.height() / packInk.height()));
        float left = nativeInk.centerX() - packInk.centerX() * scale;
        float top = nativeInk.centerY() - packInk.centerY() * scale;
        result.set(left, top, left + iconWidth * scale, top + iconHeight * scale);
        return true;
    }

    private static boolean measure(Drawable drawable, RectF result) {
        int width = drawable.getIntrinsicWidth(), height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) return false;
        float scale = Math.min(1f, 128f / Math.max(width, height));
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        Rect previous = new Rect(drawable.getBounds());
        Bitmap sample = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            drawable.setBounds(0, 0, w, h);
            drawable.draw(new Canvas(sample));
            if (!inkBounds(sample, result)) return false;
            result.set(result.left * width / w, result.top * height / h,
                    result.right * width / w, result.bottom * height / h);
            return true;
        } finally {
            drawable.setBounds(previous);
            sample.recycle();
        }
    }

    private static boolean inkBounds(Bitmap bitmap, RectF result) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int left = width, top = height, right = -1, bottom = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        result.set(left, top, right + 1, bottom + 1);
        return !result.isEmpty();
    }
}
