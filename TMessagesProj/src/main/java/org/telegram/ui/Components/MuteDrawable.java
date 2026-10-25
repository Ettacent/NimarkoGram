/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Xfermode;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.R;

import app.nimarkogram.messenger.icons.IconResources;
public class MuteDrawable extends Drawable {

    private Drawable baseDrawable;
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint packPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final MediaActionDrawable.PackIconRenderer packIconRenderer = new MediaActionDrawable.PackIconRenderer();
    private int alpha = 255;
    public MuteDrawable(Context context) {
        baseDrawable = IconResources.getStockDrawable(context, R.drawable.filled_sound_on).mutate();
        packPaint.setColor(0xFFFFFFFF);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dpf2(1.566f));
        strokePaint.setColor(0xFFFFFFFF);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        clipPaint.setStyle(Paint.Style.STROKE);
        clipPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        clipPaint.setStrokeWidth(dpf2(4.5f));
        clipPaint.setColor(0xFFFF0000);
        clipPaint.setStrokeCap(Paint.Cap.ROUND);
        clipPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    private final AnimatedFloat animatedMuted = new AnimatedFloat(this::invalidateSelf, 0, 200, CubicBezierInterpolator.EASE_OUT);
    private boolean muted;

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect bounds = getBounds();
        final float muted = animatedMuted.set(this.muted);
        final IconResources.NavigationIcons icons = IconResources.activeNavigationIcons();
        final IconResources.PreparedIcon soundOn = MediaActionDrawable.PackIconRenderer.get(icons, R.drawable.media_mute);
        final IconResources.PreparedIcon soundOff = MediaActionDrawable.PackIconRenderer.get(icons, R.drawable.media_unmute);
        if (soundOn != null && soundOff != null) {
            final float size = Math.min(dp(24), Math.min(bounds.width(), bounds.height()));
            packIconRenderer.draw(canvas, soundOn, packPaint, bounds.exactCenterX(), bounds.exactCenterY(),
                    size, alpha / 255f * (1f - muted), 0);
            packIconRenderer.draw(canvas, soundOff, packPaint, bounds.exactCenterX(), bounds.exactCenterY(),
                    size, alpha / 255f * muted, 0);
            return;
        }
        canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, alpha, Canvas.ALL_SAVE_FLAG);

        baseDrawable.setBounds(bounds);
        baseDrawable.draw(canvas);

        if (muted > 0) {
            final float p = dpf2(.783f);
            float ax = bounds.centerX() - dp(9) + p, ay = bounds.centerY() - dp(9) + p;
            float bx = bounds.centerX() + dp(9) - p, by = bounds.centerY() + dp(9) - p;
            if (this.muted) {
                ax = lerp(bx, ax, muted);
                ay = lerp(by, ay, muted);
            } else {
                bx = lerp(ax, bx, muted);
                by = lerp(ay, by, muted);
            }
            canvas.drawLine(ax, ay, bx, by, clipPaint);
            strokePaint.setAlpha((int) (0xFF * Math.min(1, 10 * muted)));
            canvas.drawLine(ax, ay, bx, by, strokePaint);
        }

        canvas.restore();
    }

    public void setMuted(boolean muted, boolean animated) {
        this.muted = muted;
        if (!animated) {
            animatedMuted.set(muted, true);
        }
        invalidateSelf();
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        baseDrawable.setAlpha(255);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

        packPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicHeight() {
        return dp(24);
    }

    @Override
    public int getIntrinsicWidth() {
        return dp(24);
    }
}
