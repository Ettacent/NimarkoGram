/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 * Modifications Copyright (C) 2026 Ettacent.
 */

/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import app.nimarkogram.messenger.icons.NimarkoIconResources;
public class BackDrawable extends Drawable {

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint prevPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint packPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint packTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF packDst = new RectF();
    private int alpha = 255;
    private float animationStartRotation;
    private float currentAnimationDuration;
    private boolean reverseAngle;
    private long lastFrameTime;
    private boolean animationInProgress;
    private float finalRotation;
    private float currentRotation;
    private int currentAnimationTime;
    private boolean alwaysClose;
    private DecelerateInterpolator interpolator = new DecelerateInterpolator();
    private int color = 0xffffffff;
    private int rotatedColor = 0xff757575;
    private float animationTime = 300.0f;
    private boolean rotated = true;
    private int arrowRotation;

    public float getRotation() {
        return finalRotation;
    }

    public BackDrawable(boolean close) {
        super();
        paint.setStrokeWidth(AndroidUtilities.dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        prevPaint.setStrokeWidth(AndroidUtilities.dp(2));
        prevPaint.setColor(Color.RED);
        packTintPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        alwaysClose = close;
    }

    public void setColor(int value) {
        color = value;
        invalidateSelf();
    }

    public void setRotatedColor(int value) {
        rotatedColor = value;
        invalidateSelf();
    }

    public void setArrowRotation(int angle) {
        arrowRotation = angle;
        invalidateSelf();
    }

    public void setRotation(float rotation, boolean animated) {
        long now = SystemClock.uptimeMillis();
        updateRotation(now);
        if (Float.isNaN(rotation) || Float.isInfinite(rotation)) return;
        rotation = Math.max(0f, Math.min(1f, rotation));
        animated = animated && SharedConfig.animationsEnabled();
        if (animated && rotation == finalRotation && currentRotation != finalRotation) return;
        if (currentRotation == 1) {
            reverseAngle = true;
        } else if (currentRotation == 0) {
            reverseAngle = false;
        }
        finalRotation = rotation;
        animationStartRotation = currentRotation;
        currentAnimationTime = 0;
        currentAnimationDuration = animationTime * Math.abs(finalRotation - animationStartRotation);
        if (animated && currentAnimationDuration > 0) {
            lastFrameTime = now;
        } else {
            currentRotation = rotation;
        lastFrameTime = 0;
        }
        invalidateSelf();
    }

    public void setAnimationTime(float value) {
        animationTime = Float.isNaN(value) || Float.isInfinite(value) ? 0f : Math.max(0f, value);
    }

    public void setRotated(boolean value) {
        rotated = value;
    }

    private float translationX;

    public BackDrawable setTranslationX(float translationX) {
        this.translationX = translationX;
        invalidateSelf();
        return this;
    }

    private void updateRotation(long now) {
        if (currentRotation == finalRotation) return;
        if (!SharedConfig.animationsEnabled() || currentAnimationDuration <= 0) {
            currentRotation = finalRotation;
        } else {
            currentAnimationTime += (int) Math.max(0L, Math.min((long) Integer.MAX_VALUE - currentAnimationTime, now - lastFrameTime));
            float progress = Math.min(1f, currentAnimationTime / currentAnimationDuration);
            currentRotation = progress >= 1f ? finalRotation : animationStartRotation
                    + (finalRotation - animationStartRotation) * interpolator.getInterpolation(progress);
        }
        lastFrameTime = now;
    }
    @Override
    public void draw(@NonNull Canvas canvas) {
        updateRotation(SystemClock.uptimeMillis());
        if (currentRotation != finalRotation) invalidateSelf();
        final int tint = ColorUtils.blendARGB(color, rotatedColor, currentRotation);
        final NimarkoIconResources.NavigationIcons icons = NimarkoIconResources.activeNavigationIcons();
        if (icons != null && icons.close != null && !icons.close.isRecycled()
                && (alwaysClose || icons.back != null && !icons.back.isRecycled())) {
            int packAlpha = (alpha * Color.alpha(tint) + 127) / 255;
            if (packAlpha <= 0) return;
            canvas.save();
            canvas.translate(getIntrinsicWidth() / 2f + translationX, getIntrinsicHeight() / 2f);
                canvas.rotate(arrowRotation);
            float extent = getIntrinsicWidth();
            int layer = packAlpha == 255 ? canvas.save() : canvas.saveLayerAlpha(-extent, -extent, extent, extent, packAlpha);
            int packTint = tint | 0xff000000;
            if (alwaysClose) {
                drawPackIcon(canvas, icons.close, icons.closeNoTint, 1f,
                        currentRotation * (reverseAngle ? -180f : 180f), packTint);
            } else {
                float turn = reverseAngle ? -225f : 135f;
                float glyphProgress = Math.max(0f, Math.min(1f, (currentRotation - .2f) / .6f));
                glyphProgress = glyphProgress * glyphProgress * (3f - 2f * glyphProgress);
                drawPackIcon(canvas, icons.back, icons.backNoTint, 1f - glyphProgress, currentRotation * turn, packTint);
                drawPackIcon(canvas, icons.close, icons.closeNoTint, glyphProgress, (currentRotation - 1f) * turn, packTint);
            }
            canvas.restoreToCount(layer);
            canvas.restore();
            return;
        }
        paint.setColor(tint);
        paint.setAlpha((Color.alpha(tint) * alpha + 127) / 255);
        canvas.save();
        canvas.translate(getIntrinsicWidth() / 2f + translationX, getIntrinsicHeight() / 2f);
        if (arrowRotation != 0) canvas.rotate(arrowRotation);
            float rotation = currentRotation;
            canvas.translate(-AndroidUtilities.dp(0.66f) , 0);
            if (!alwaysClose) {
                canvas.rotate(currentRotation * (reverseAngle ? -225 : 135));
            } else {
                canvas.rotate(135 + currentRotation * (reverseAngle ? -180 : 180));
                rotation = 1.0f;
            }
            canvas.drawLine(AndroidUtilities.dp(AndroidUtilities.lerp(-6.75f, -8f, rotation)), 0, AndroidUtilities.dp(8) - (paint.getStrokeWidth() / 2f) * (1f - rotation), 0, paint);
            float startYDiff = AndroidUtilities.dp(-0.25f);
            float endYDiff = AndroidUtilities.dp(AndroidUtilities.lerp(7f, 8f, rotation)) - (paint.getStrokeWidth() / 4f) * (1f - rotation);
            float startXDiff = AndroidUtilities.dp(AndroidUtilities.lerp(-7f - 0.25f, 0f, rotation));
        canvas.drawLine(startXDiff, -startYDiff, 0, -endYDiff, paint);
        canvas.drawLine(startXDiff, startYDiff, 0, endYDiff, paint);
            canvas.restore();
    }

    private void drawPackIcon(Canvas canvas, Bitmap bitmap, boolean noTint, float progress, float rotation, int tint) {
        int iconAlpha = Math.round(255 * progress);
        if (iconAlpha <= 0) return;
        canvas.save();
        canvas.rotate(rotation);
        packDst.set(-getIntrinsicWidth() / 2f, -getIntrinsicHeight() / 2f,
                getIntrinsicWidth() / 2f, getIntrinsicHeight() / 2f);
        if (noTint) {
            packPaint.setAlpha((iconAlpha * Color.alpha(tint) + 127) / 255);
            canvas.drawBitmap(bitmap, null, packDst, packPaint);
        } else {
            int layer = canvas.saveLayerAlpha(packDst.left, packDst.top, packDst.right, packDst.bottom, iconAlpha);
            packPaint.setAlpha(255);
            canvas.drawBitmap(bitmap, null, packDst, packPaint);
            packTintPaint.setColor(tint);
            canvas.drawRect(packDst, packTintPaint);
            canvas.restoreToCount(layer);
        }
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        packTintPaint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return AndroidUtilities.dp(24);
    }

    @Override
    public int getIntrinsicHeight() {
        return AndroidUtilities.dp(24);
    }
}
