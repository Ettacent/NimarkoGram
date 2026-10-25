/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import app.nimarkogram.messenger.icons.IconResources;
public class CrossOutDrawable extends Drawable {

    Drawable iconDrawable;
    RectF rectF = new RectF();
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint xRefPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    int color;
    int colorKey;
    float progress;
    boolean cross;

    private float xOffset;
    private float lenOffsetTop;
    private float lenOffsetBottom;

    private final Theme.ResourcesProvider resourcesProvider;
    private ColorFilter callerColorFilter;
    private boolean colorFilterDirty;
    private boolean lastDark;
    private final boolean preserveBellReversal;
    private boolean slashFromStart;
    public CrossOutDrawable(Context context, int iconRes, int colorKey) {
        this(context, iconRes, colorKey, null);
    }

    public CrossOutDrawable(Context context, int iconRes, int colorKey, Theme.ResourcesProvider resourcesProvider) {
        this.resourcesProvider = resourcesProvider;
        preserveBellReversal = (iconRes == R.drawable.input_notify_on || iconRes == R.drawable.input_notify_off)
                && context.getResources() instanceof IconResources
                && ((IconResources) context.getResources()).hasReplacement(iconRes);
        iconDrawable = ContextCompat.getDrawable(context, iconRes);
        if (resourcesProvider != null && context.getResources() instanceof IconResources
                && ((IconResources) context.getResources()).hasReplacement(iconRes)) {
            Drawable copy = IconResources.copyDrawable(iconDrawable, context.getResources(), resourcesProvider::isDark);
            if (copy != null) {
                iconDrawable = copy;
            }
        }
        colorFilterDirty = resourcesProvider != null;
        this.colorKey = colorKey;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(AndroidUtilities.dpf2(1.7f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        xRefPaint.setColor(0xff000000);
        xRefPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        xRefPaint.setStyle(Paint.Style.STROKE);
        xRefPaint.setStrokeWidth(AndroidUtilities.dpf2(2.5f));
    }

    public void setCrossOut(boolean cross, boolean animated) {
        if (this.cross != cross) {
            this.cross = cross;
            if (!animated || !preserveBellReversal || progress == 0f || progress == 1f) {
                slashFromStart = cross;
            }
            if (!animated) {
                progress = cross ? 1f : 0f;
            } else if (!preserveBellReversal) {
                progress = cross ? 0f : 1f;
            }
            invalidateSelf();
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (cross && progress != 1f) {
            progress += AndroidUtilities.screenRefreshTime / 150f;
            invalidateSelf();
            if (progress > 1f) {
                progress = 1f;
            }
        } else if (!cross && progress != 0f) {
            progress -= AndroidUtilities.screenRefreshTime / 150f;
            invalidateSelf();
            if (progress < 0) {
                progress = 0;
            }
        }
        int newColor = colorKey < 0 ? Color.WHITE : Theme.getColor(colorKey, resourcesProvider);
        boolean dark = resourcesProvider != null && resourcesProvider.isDark();
        if (color != newColor || colorFilterDirty || dark != lastDark) {
            color = newColor;
            colorFilterDirty = false;
            lastDark = dark;
            paint.setColor(callerColorFilter == null ? newColor : Color.WHITE);
            paint.setColorFilter(callerColorFilter);
            iconDrawable.setColorFilter(callerColorFilter == null
                    ? new PorterDuffColorFilter(newColor, PorterDuff.Mode.MULTIPLY) : callerColorFilter);
        }
        if (progress == 0) {
            iconDrawable.draw(canvas);
            return;
        }
        rectF.set(iconDrawable.getBounds());
        canvas.saveLayerAlpha(rectF, 255, Canvas.ALL_SAVE_FLAG);
        iconDrawable.draw(canvas);

        float startX = rectF.left + AndroidUtilities.dpf2(4.5f) + xOffset + lenOffsetTop;
        float startY = rectF.top + AndroidUtilities.dpf2(4.5f) - AndroidUtilities.dp(1) + lenOffsetTop;
        float stopX = rectF.right - AndroidUtilities.dp(3) + xOffset - lenOffsetBottom;
        float stopY = rectF.bottom - AndroidUtilities.dp(1) - AndroidUtilities.dp(3) - lenOffsetBottom;
        if (slashFromStart) {
            stopX = startX + (stopX - startX) * progress;
            stopY = startY + (stopY - startY) * progress;
        } else {
            startX = startX + (stopX - startX) * (1f - progress);
            startY = startY + (stopY - startY) * (1f - progress);
        }
        canvas.drawLine(startX, startY - paint.getStrokeWidth(), stopX, stopY - paint.getStrokeWidth(), xRefPaint);
        float offsetY = (xRefPaint.getStrokeWidth() - paint.getStrokeWidth()) / 2f + 1;
        canvas.drawLine(startX, startY - offsetY, stopX, stopY - offsetY, xRefPaint);
        canvas.drawLine(startX, startY, stopX, stopY, paint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int i) {
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

        if (resourcesProvider != null) {
            callerColorFilter = colorFilter;
            colorFilterDirty = true;
            invalidateSelf();
        }
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        iconDrawable.setBounds(left, top, right, bottom);
    }

    @Override
    public int getIntrinsicHeight() {
        return iconDrawable.getIntrinsicHeight();
    }

    @Override
    public int getIntrinsicWidth() {
        return iconDrawable.getIntrinsicWidth();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    public void setColorKey(int colorKey) {
        this.colorKey = colorKey;
    }

    public void setOffsets(float xOffset, float lenOffsetTop, float lenOffsetBottom) {
        this.xOffset = xOffset;
        this.lenOffsetTop = lenOffsetTop;
        this.lenOffsetBottom = lenOffsetBottom;
        invalidateSelf();
    }

    public void setStrokeWidth(float w) {
        paint.setStrokeWidth(w);
        xRefPaint.setStrokeWidth(w * 1.47f);
    }

    public float getProgress() {
        return progress;
    }
}
