package app.nimarkogram.messenger.utils.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.CombinedDrawable;

public final class UIUtil {

    public static final UIUtil INSTANCE = new UIUtil();

    private static final float[] nowPlayingPattern = {
            -5.5f, 20.0f, 20.0f, 0.35f,
            -5.5f, -20.0f, 20.0f, 0.35f,
            -36.0f, -42.0f, 22.0f, 0.375f,
            -36.0f, 0.0f, 25.0f, 0.425f,
            -36.0f, 42.0f, 22.0f, 0.375f,
            -70.0f, 22.0f, 23.0f, 0.35f,
            -70.0f, -22.0f, 23.0f, 0.35f,
            -99.0f, 46.0f, 21.0f, 0.275f,
            -99.0f, 0.0f, 22.0f, 0.325f,
            -99.0f, -46.0f, 21.0f, 0.275f,
            -128.0f, -23.0f, 20.0f, 0.225f,
            -128.0f, 23.0f, 20.0f, 0.225f
    };

    private UIUtil() {
    }

    public static Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        try {
            if (drawable == null || width <= 0 || height <= 0) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    public static CombinedDrawable createCircleDrawableWithIcon(Context context, int iconRes, int size) {
        try {
            Drawable icon = null;
            if (iconRes != 0 && context != null) {
                Drawable d = ContextCompat.getDrawable(context, iconRes);
                if (d != null) {
                    icon = d.mutate();
                }
            }
            ShapeDrawable circle = new ShapeDrawable(new OvalShape());
            circle.getPaint().setColor(0xFFFFFFFF);
            circle.setIntrinsicWidth(size);
            circle.setIntrinsicHeight(size);
            CombinedDrawable combined = new CombinedDrawable(circle, icon);
            combined.setCustomSize(size, size);
            return combined;
        } catch (Throwable t) {
            return null;
        }
    }

    public static int adjustHsl(int color, float luminance, float saturation) {
        try {
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(color, hsl);
            if (saturation > 0.0f) {
                hsl[1] = Math.min(hsl[1] * saturation, 1.0f);
            }
            hsl[2] = Math.min(hsl[2] * luminance, 1.0f);
            return ColorUtils.HSLToColor(hsl);
        } catch (Throwable t) {
            return color;
        }
    }

    public static int adjustHsl(int color, float luminance) {
        return adjustHsl(color, luminance, -1.0f);
    }

    public static void drawNowPlayingPattern(Canvas canvas, Drawable pattern, float w, float h, float alpha) {
        if (alpha <= 0.0f || canvas == null || pattern == null) {
            return;
        }
        try {
            final float cy = h / 2.0f;
            for (int i = 0; i + 3 < nowPlayingPattern.length; i += 4) {
                float dx = nowPlayingPattern[i];
                float dy = nowPlayingPattern[i + 1];
                float diameter = nowPlayingPattern[i + 2];
                float alphaFactor = nowPlayingPattern[i + 3];

                float px = AndroidUtilities.dpf2(dx) + w;
                float py = AndroidUtilities.dpf2(dy) + cy;
                float r = AndroidUtilities.dpf2(diameter) / 2.0f;

                pattern.setBounds((int) (px - r), (int) (py - r), (int) (px + r), (int) (py + r));
                pattern.setAlpha((int) (255.0f * alpha * alphaFactor));
                pattern.draw(canvas);
            }
        } catch (Throwable ignored) {
        }
    }
}
