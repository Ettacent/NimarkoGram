/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;
public class MsgClockDrawable extends Drawable {

    private ConstantState constantState;
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;
    private int colorAlpha = 255;
    private long animationTime;
    private long prevTime = -1;
    private int clockEpoch = -1;
    private int color;

    public MsgClockDrawable() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(AndroidUtilities.dp(1f));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int r = Math.min(bounds.width(), bounds.height());
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), (r >> 1) - AndroidUtilities.dp(0.5f), paint);

        long currentTime = NimarkoUiAnimationClock.now();
        int epoch = NimarkoUiAnimationClock.epoch();
        if (prevTime >= 0 && epoch == clockEpoch && !NimarkoUiAnimationClock.isPaused()) {
            animationTime += Math.max(0, currentTime - prevTime);
        }
        prevTime = currentTime;
        clockEpoch = epoch;
        float rotateTime = 1500;
        float rotateHourTime = rotateTime * 3;

        canvas.save();
        canvas.rotate(360 * (animationTime % rotateTime) / rotateTime, bounds.centerX(), bounds.centerY());
        canvas.drawLine(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY() - AndroidUtilities.dp(3), paint);
        canvas.restore();

        canvas.save();
        canvas.rotate(360 * (animationTime % rotateHourTime) / rotateHourTime, bounds.centerX(), bounds.centerY());
        canvas.drawLine(bounds.centerX(), bounds.centerY(), bounds.centerX() + AndroidUtilities.dp(2.3f), bounds.centerY(), paint);
        canvas.restore();
    }

    public void setColor(int color) {
        if (color != this.color) {
            colorAlpha = Color.alpha(color);
            paint.setColor(ColorUtils.setAlphaComponent(color, (int) (alpha * (colorAlpha / 255f))));
        }
        this.color = color;
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(12);
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(12);
    }

    @Override
    public void setAlpha(int i) {
        if (alpha != i) {
            alpha = i;
            paint.setAlpha((int) (alpha * (colorAlpha / 255f)));
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {

    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Nullable
    @Override
    public ConstantState getConstantState() {
        if (constantState == null) {
            constantState = new ConstantState() {
                @NonNull
                @Override
                public Drawable newDrawable() {
                    return new MsgClockDrawable();
                }
                @Override
                public int getChangingConfigurations() {
                    return 0;
                }
            };
        }
        return constantState;
    }
}
