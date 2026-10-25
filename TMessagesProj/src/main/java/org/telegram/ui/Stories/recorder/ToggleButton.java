/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Stories.recorder;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Region;
import android.graphics.Xfermode;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import app.nimarkogram.messenger.icons.IconResources;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

public class ToggleButton extends View implements FlashViews.Invertable {

    private Drawable drawable;

    private final int resId;
    private final int activeResId;
    private Resources iconResources;
    private long iconGeneration;
    private int iconDensity;
    private boolean iconDark;
    private float invert;
    private Bitmap activeBitmap;
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public ToggleButton(Context context, int resId) {
        this(context, resId, resId);
    }

    public ToggleButton(Context context, int resId, int activeResId) {
        super(context);

        this.resId = resId;

        this.activeResId = activeResId;
        activePaint.setColor(0xFFFFFFFF);
        activeBitmapPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        refreshResourceDrawables();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshResourceDrawables();
    }

    private void refreshResourceDrawables() {
        final Resources resources = getResources();
        final long generation = resources instanceof IconResources
                ? ((IconResources) resources).getReplacementGeneration() : -1L;
        final int density = resources.getDisplayMetrics().densityDpi;
        final boolean dark = Theme.isCurrentThemeDark();
        if (iconResources != resources || iconGeneration != generation || iconDensity != density || iconDark != dark) {
            drawable = resources.getDrawable(resId).mutate();
            activeBitmap = null;
            iconResources = resources;
            iconGeneration = generation;
            iconDensity = density;
            iconDark = dark;
            setInvert(invert);
        }
        if (activeBitmap == null) {
            Drawable activeDrawable = resources.getDrawable(activeResId).mutate();
            final int width = Math.max(1, activeDrawable.getIntrinsicWidth());
            final int height = Math.max(1, activeDrawable.getIntrinsicHeight());
            activeBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            activeDrawable.setBounds(0, 0, width, height);
            activeDrawable.draw(new Canvas(activeBitmap));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (activeBitmap != null) {
            activeBitmap.recycle();
            activeBitmap = null;
        }
    }

    public void setValue(boolean value) {
        this.value = value ? 1 : 0;
        invalidate();
    }

    private float value;
    private final AnimatedFloat valueAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    private final Path clipPath = new Path();

    public void setInvert(float invert) {
        this.invert = invert;
        drawable.setColorFilter(new PorterDuffColorFilter(ColorUtils.blendARGB(0xFFFFFFFF, 0xFF000000, invert), PorterDuff.Mode.MULTIPLY));
        activePaint.setColor(ColorUtils.blendARGB(0xFFFFFFFF, 0xFF000000, invert));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        refreshResourceDrawables();
        float t = valueAnimated.set(value);

        final int w = drawable.getIntrinsicWidth(), h = drawable.getIntrinsicHeight();

        AndroidUtilities.rectTmp2.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        if (t <= 0) {
            drawable.setBounds(AndroidUtilities.rectTmp2);
            drawable.draw(canvas);
        } else if (t < 1) {
            canvas.save();
            clipPath.rewind();
            clipPath.addCircle(getWidth() / 2f, getHeight() / 2f, dp(16) * t, Path.Direction.CW);
            canvas.clipPath(clipPath, Region.Op.DIFFERENCE);
            drawable.setBounds(AndroidUtilities.rectTmp2);
            drawable.draw(canvas);
            canvas.restore();
        }

        if (t > 0) {
            canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), 0xFF, Canvas.ALL_SAVE_FLAG);
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, dp(16) * t, activePaint);
            canvas.save();
            if (activeBitmap != null) {
                canvas.drawBitmap(activeBitmap, null, AndroidUtilities.rectTmp2, activeBitmapPaint);
            }
            canvas.restore();
            canvas.restore();
        }
    }
}
