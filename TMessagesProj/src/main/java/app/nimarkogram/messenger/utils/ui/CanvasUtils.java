package app.nimarkogram.messenger.utils.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CombinedDrawable;

public final class CanvasUtils {
    public static final CanvasUtils INSTANCE = new CanvasUtils();
    
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

    private CanvasUtils() {
    }

    public int getAdaptedSurfaceColor() {
        return getAdaptedSurfaceColor(null);
    }

    public static CombinedDrawable createCircleDrawableWithIcon(Context context, int iconRes, int size) {
        Drawable drawable = null;
        if (iconRes != 0) {
            Drawable d = ContextCompat.getDrawable(context, iconRes);
            if (d != null) {
                drawable = d.mutate();
            }
        }
        ShapeDrawable shapeDrawable = new ShapeDrawable(new OvalShape());
        shapeDrawable.getPaint().setColor(-1);
        shapeDrawable.setIntrinsicWidth(size);
        shapeDrawable.setIntrinsicHeight(size);
        CombinedDrawable combinedDrawable = new CombinedDrawable(shapeDrawable, drawable);
        combinedDrawable.setCustomSize(size, size);
        return combinedDrawable;
    }

    public static Bitmap drawableToBitmap(Drawable drawable, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    public int adjustHsl(int color, float luminanceFactor) {
        return adjustHsl(color, luminanceFactor, -1.0f);
    }

    public int adjustHsl(int color, float luminanceFactor, float saturationFactor) {
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(color, hsl);
        if (saturationFactor > 0.0f) {
            hsl[1] = Math.min(hsl[1] * saturationFactor, 1.0f);
        }
        hsl[2] = Math.min(hsl[2] * luminanceFactor, 1.0f);
        return ColorUtils.HSLToColor(hsl);
    }

    public void drawNowPlayingPattern(Canvas canvas, Drawable pattern, float offsetX, float centerY, float alpha) {
        if (alpha <= 0.0f) {
            return;
        }
        int i = 0;
        while (i < nowPlayingPattern.length) {
            float x = nowPlayingPattern[i];
            float y = nowPlayingPattern[i + 1];
            float size = nowPlayingPattern[i + 2];
            float itemAlpha = nowPlayingPattern[i + 3];
            
            float halfCenterY = centerY / 2.0f;
            
            float sizePx = AndroidUtilities.dpf2(size);
            float sizePxHalf = sizePx / 2.0f;
            
            int left = (int) ((AndroidUtilities.dpf2(x) + offsetX) - sizePxHalf);
            int top = (int) ((AndroidUtilities.dpf2(y) + halfCenterY) - sizePxHalf);
            int right = (int) (AndroidUtilities.dpf2(x) + offsetX + sizePxHalf);
            int bottom = (int) (halfCenterY + AndroidUtilities.dpf2(y) + sizePxHalf);

            pattern.setBounds(left, top, right, bottom);
            pattern.setAlpha((int) (255 * alpha * itemAlpha));
            pattern.draw(canvas);
            i += 4;
        }
    }

    public int getAdaptedSurfaceColor(Theme.ResourcesProvider resourcesProvider) {
        int color = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        int grayColor = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
        
        if (color != grayColor) {
            return color;
        }
        
        return adaptColor(color, hsv -> {
            float value = hsv[2];
            if (value > 0.5f) {
                hsv[2] = Math.max(value - 0.03f, 0.0f);
            } else {
                hsv[2] = Math.min(value + 0.03f, 1.0f);
            }
        });
    }

    public int adaptColor(int color, ColorModifier transformer) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        transformer.apply(hsv);
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    private interface ColorModifier {
        void apply(float[] hsv);
    }
}