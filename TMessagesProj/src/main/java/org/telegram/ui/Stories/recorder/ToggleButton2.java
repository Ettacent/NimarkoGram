/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Stories.recorder;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Region;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import app.nimarkogram.messenger.icons.NimarkoIconResources;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.concurrent.atomic.AtomicBoolean;

public class ToggleButton2 extends View implements FlashViews.Invertable {

    private final Path clipPath = new Path();
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public ToggleButton2(Context context) {
        super(context);
        activePaint.setColor(0xFFFFFFFF);
        activeBitmapPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    }

    private boolean selected;
    private final AnimatedFloat animatedSelected = new AnimatedFloat(this, 0, 380, CubicBezierInterpolator.EASE_OUT_QUINT);

    private Drawable drawable;
    private Bitmap activeBitmap;

    private int drawableIcon;
    private Resources iconResources;
    private long iconGeneration;
    private int iconDensity;
    private boolean iconDark;
    private float invert;
    private int currentIcon;
    private float scale = 1f;
    private ValueAnimator animator;

    public void setIcon(int iconRes, boolean animated) {
        if (currentIcon == iconRes) {
            refreshResourceDrawable();
            return;
        }
        currentIcon = iconRes;

        if (animator != null) {
            animator.cancel();
            animator = null;
        }

        if (animated) {
            animator = ValueAnimator.ofFloat(0, 1).setDuration(150);
            AtomicBoolean changed = new AtomicBoolean();
            animator.addUpdateListener(animation -> {
                float val = (float) animation.getAnimatedValue();
                this.scale = 0.5f + Math.abs(val - 0.5f);
                if (val >= 0.5f && !changed.get()) {
                    changed.set(true);
                    setDrawable(iconRes);
                }
                invalidate();
            });
            animator.start();
        } else {
            scale = 1f;
            setDrawable(iconRes);
        }
    }

    public void setIcon(Drawable iconDrawable, boolean animated) {
        currentIcon = 0;
        if (drawable == iconDrawable) {
            return;
        }

        if (animator != null) {
            animator.cancel();
            animator = null;
        }

        if (animated) {
            animator = ValueAnimator.ofFloat(0, 1).setDuration(150);
            AtomicBoolean changed = new AtomicBoolean();
            animator.addUpdateListener(animation -> {
                float val = (float) animation.getAnimatedValue();
                this.scale = 0.5f + Math.abs(val - 0.5f);
                if (val >= 0.5f && !changed.get()) {
                    changed.set(true);
                    setDrawable(iconDrawable);
                }
            });
            animator.start();
        } else {
            scale = 1f;
            setDrawable(iconDrawable);
        }
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        invalidate();
    }

    public void setSelected(boolean selected, boolean animated) {
        this.selected = selected;
        if (!animated) {
            animatedSelected.set(selected ? 1.0f : 0.0f, true);
        }
        invalidate();
    }

    @Override
    public void setInvert(float invert) {
        this.invert = invert;
        if (drawable != null) {
            drawable.setColorFilter(new PorterDuffColorFilter(ColorUtils.blendARGB(0xffffffff, 0xff000000, invert), PorterDuff.Mode.MULTIPLY));
        }
        activePaint.setColor(ColorUtils.blendARGB(0xffffffff, 0xff000000, invert));
        invalidate();
    }

    public void setDrawable(int iconRes) {
        setDrawable(iconRes, getResources());
    }

    private void setDrawable(int iconRes, Resources resources) {
        drawableIcon = iconRes;
        iconResources = resources;
        iconGeneration = resources instanceof NimarkoIconResources
                ? ((NimarkoIconResources) resources).getReplacementGeneration() : -1L;
        iconDensity = resources.getDisplayMetrics().densityDpi;
        iconDark = Theme.isCurrentThemeDark();
        drawable = iconRes == 0 ? null : resources.getDrawable(iconRes).mutate();
        rebuildActiveBitmap();
        setInvert(invert);
    }

    public void setDrawable(Drawable drawable) {
        currentIcon = 0;
        drawableIcon = 0;
        this.drawable = drawable;
        rebuildActiveBitmap();
        invalidate();
    }

    private void rebuildActiveBitmap() {
            activeBitmap = null;
        if (activeBitmap == null && drawable != null && drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0) {
            activeBitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            Rect bounds = new Rect(drawable.getBounds());
            try {
            drawable.setBounds(0, 0, activeBitmap.getWidth(), activeBitmap.getHeight());
            drawable.draw(new Canvas(activeBitmap));
            } finally {
                drawable.setBounds(bounds);
            }
        }
    }

    private void refreshResourceDrawable() {
        final Resources resources = getResources();
        final long generation = resources instanceof NimarkoIconResources
                ? ((NimarkoIconResources) resources).getReplacementGeneration() : -1L;
        if (drawableIcon != 0 && (iconResources != resources || iconGeneration != generation
                || iconDensity != resources.getDisplayMetrics().densityDpi
                || iconDark != Theme.isCurrentThemeDark())) {
            setDrawable(drawableIcon, resources);
        } else if (activeBitmap == null && drawable != null) {
            rebuildActiveBitmap();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        refreshResourceDrawable();
        if (drawable == null) {
            return;
        }

        float t = animatedSelected.set(selected);
//        canvas.save();
//        canvas.scale(scale, scale, getWidth() / 2.0f, getHeight() / 2.0f);

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

//        canvas.restore();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshResourceDrawable();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (activeBitmap != null) {
            activeBitmap.recycle();
            activeBitmap = null;
        }
    }

}
