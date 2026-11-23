/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.ui.Components.Premium;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PremiumPreviewFragment;

final class PremiumPreviewPoster {
    private static final float WIDTH = 192f;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final float[] radii = new float[8];
    private final Drawable icon;
    private final boolean tags;
    private final String title;
    private final String tagsTitle;

    PremiumPreviewPoster(Context context, PremiumPreviewFragment.PremiumFeatureData feature, Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
        tags = feature.type == PremiumPreviewFragment.PREMIUM_FEATURE_SAVED_TAGS;
        icon = context.getDrawable(feature.icon != 0 ? feature.icon : R.drawable.settings_premium).mutate();
        textPaint.setTypeface(AndroidUtilities.bold());
        textPaint.setTextSize(14);
        title = TextUtils.ellipsize(feature.title, textPaint, WIDTH - 28, TextUtils.TruncateAt.END).toString();
        tagsTitle = TextUtils.ellipsize(LocaleController.getString(R.string.SavedMessages),
                textPaint, WIDTH - 74, TextUtils.TruncateAt.END).toString();
    }

    void draw(Canvas canvas, RectF bounds, float radius, boolean fromTop) {
        if (bounds.isEmpty()) return;
        final int surface = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider) | 0xff000000;
        final int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final int accent = Theme.getColor(Theme.key_premiumGradient2, resourcesProvider);
        final int panel = ColorUtils.blendARGB(surface, ink, .065f);
        final int quiet = ColorUtils.blendARGB(surface, ink, .16f);
        final int accentPanel = ColorUtils.blendARGB(surface, accent, .22f);

        final float scale = bounds.width() / WIDTH;
        final float height = bounds.height() / scale;
        final int state = canvas.save();
        clip.reset();
        for (int i = 0; i < 8; i++) radii[i] = (i < 4) != fromTop ? Math.max(0f, radius) : 0f;
        clip.addRoundRect(bounds, radii, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.drawColor(surface);
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(scale, scale);
        textPaint.setColor(ink);
        if (tags) {
            final boolean rtl = LocaleController.isRTL;
            box(canvas, 12, 25, WIDTH - 12, 59, 12, panel);
            box(canvas, rtl ? 145 : 19, 31, rtl ? 173 : 47, 53, 8, accentPanel);
            drawIcon(canvas, rtl ? 159 : 33, 42, 15, ink);
            textPaint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
            canvas.drawText(tagsTitle, rtl ? 137 : 55, 47, textPaint);
            for (int i = 0; i < 3; i++) {
                final float x = rtl ? WIDTH - 60 - 51 * i : 16 + 51 * i;
                box(canvas, x, 69, x + 44, 87, 9, i == 0 ? accentPanel : panel);
                drawIcon(canvas, x + 12, 78, 10, i == 0 ? ink : quiet);
                box(canvas, x + 21, 76, x + 35, 80, 2, i == 0 ? accent : quiet);
            }
            for (int i = 0; i < 3; i++) {
                final float y = 101 + 55 * i;
                box(canvas, 16, y, WIDTH - 16, y + 47, 11, panel);
                final float left = rtl ? WIDTH - 93 : 27;
                box(canvas, left, y + 10, left + 66, y + 15, 2.5f, quiet);
                box(canvas, rtl ? 28 : WIDTH - 57, y + 23, rtl ? 57 : WIDTH - 28, y + 39, 8, accentPanel);
                drawIcon(canvas, rtl ? 42 : WIDTH - 42, y + 31, 10, accent);
                box(canvas, rtl ? WIDTH - 77 : 27, y + 22, rtl ? WIDTH - 27 : 77, y + 26, 2, quiet);
            }
        } else {
            final float center = height * .42f;
            box(canvas, 48, center - 48, WIDTH - 48, center + 48, 33, accentPanel);
            box(canvas, 59, center - 37, WIDTH - 59, center + 37, 22,
                    ColorUtils.blendARGB(surface, accent, .35f));
            drawIcon(canvas, WIDTH / 2, center, 38, ink);
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(title, WIDTH / 2, center + 76, textPaint);
        }
        canvas.restoreToCount(state);
    }

    private void box(Canvas canvas, float x1, float y1, float x2, float y2, float radius, int color) {
        paint.setColor(color);
        canvas.drawRoundRect(x1, y1, x2, y2, radius, radius, paint);
    }

    private void drawIcon(Canvas canvas, float cx, float cy, int size, int color) {
        final int left = Math.round(cx - size / 2f), top = Math.round(cy - size / 2f);
        icon.setTint(color);
        icon.setBounds(left, top, left + size, top + size);
        icon.draw(canvas);
    }
}
