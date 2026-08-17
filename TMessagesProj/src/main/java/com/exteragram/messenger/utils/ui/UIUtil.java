package com.exteragram.messenger.utils.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.telegram.ui.Components.CombinedDrawable;

public final class UIUtil {

    public static final UIUtil INSTANCE = new UIUtil();

    private UIUtil() {
    }

    public static Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        return app.nimarkogram.messenger.utils.ui.UIUtil.drawableToBitmap(drawable, width, height);
    }

    public static CombinedDrawable createCircleDrawableWithIcon(Context context, int iconRes, int size) {
        return app.nimarkogram.messenger.utils.ui.UIUtil.createCircleDrawableWithIcon(context, iconRes, size);
    }

    public static int adjustHsl(int color, float luminance, float saturation) {
        return app.nimarkogram.messenger.utils.ui.UIUtil.adjustHsl(color, luminance, saturation);
    }

    public static int adjustHsl(int color, float luminance) {
        return app.nimarkogram.messenger.utils.ui.UIUtil.adjustHsl(color, luminance);
    }

    public static void drawNowPlayingPattern(Canvas canvas, Drawable pattern, float w, float h, float alpha) {
        app.nimarkogram.messenger.utils.ui.UIUtil.drawNowPlayingPattern(canvas, pattern, w, h, alpha);
    }
}
