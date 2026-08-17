/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

public class BackDrawable extends Drawable {

    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint prevPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint packPaint;              
    private android.graphics.PorterDuffColorFilter packColorFilter;
    private int packColorFilterColor;
    private final Rect packDst = new Rect();
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
        lastFrameTime = 0;
        if (currentRotation == 1) {
            reverseAngle = true;
        } else if (currentRotation == 0) {
            reverseAngle = false;
        }
        lastFrameTime = 0;
        if (animated) {
            if (currentRotation < rotation) {
                currentAnimationTime = (int) (currentRotation * animationTime);
            } else {
                currentAnimationTime = (int) ((1.0f - currentRotation) * animationTime);
            }
            lastFrameTime = System.currentTimeMillis();
            finalRotation = rotation;
        } else {
            finalRotation = currentRotation = rotation;
        }
        invalidateSelf();
    }

    public void setAnimationTime(float value) {
        animationTime = value;
    }

    public void setRotated(boolean value) {
        rotated = value;
    }

    private float translationX;

    public BackDrawable setTranslationX(float translationX) {
        this.translationX = translationX;
        return this;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (currentRotation != finalRotation) {
            if (lastFrameTime != 0) {
                long dt = System.currentTimeMillis() - lastFrameTime;

                currentAnimationTime += (int) dt;
                if (currentAnimationTime >= animationTime) {
                    currentRotation = finalRotation;
                } else {
                    if (currentRotation < finalRotation) {
                        currentRotation = interpolator.getInterpolation(currentAnimationTime / animationTime) * finalRotation;
                    } else {
                        currentRotation = 1.0f - interpolator.getInterpolation(currentAnimationTime / animationTime);
                    }
                }
            }
            lastFrameTime = System.currentTimeMillis();
            invalidateSelf();
        }

        float packAlpha = 0f;
        android.graphics.Bitmap packBmp = null;
        if (!alwaysClose && arrowRotation == 0) {
            packAlpha = 1f - Math.min(1f, currentRotation / 0.35f);
            if (packAlpha > 0.001f) {
                packBmp = app.nimarkogram.messenger.icons.NimarkoIconResources.activeBackArrowBitmap();
                if (packBmp == null || packBmp.isRecycled()) {
                    packBmp = null;
                    packAlpha = 0f;
                }
            }
        }

        paint.setColor(ColorUtils.blendARGB(color, rotatedColor, currentRotation));
        final int fullAlpha = paint.getAlpha();
        if (packAlpha < 0.999f) {   
            paint.setAlpha((int) (fullAlpha * (1f - packAlpha)));
            canvas.save();
            canvas.translate(getIntrinsicWidth() / 2f + translationX, getIntrinsicHeight() / 2f);
            if (arrowRotation != 0) {
                canvas.rotate(arrowRotation);
            }
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
            float endXDiff = 0;
            canvas.drawLine(startXDiff, -startYDiff, endXDiff, -endYDiff, paint);
            canvas.drawLine(startXDiff, startYDiff, endXDiff, endYDiff, paint);
            canvas.restore();
            paint.setAlpha(fullAlpha);
        }

        if (packBmp != null && packAlpha > 0.001f) {
            if (packPaint == null) {
                packPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            }
            if (packColorFilter == null || packColorFilterColor != color) {
                packColorFilterColor = color;
                packColorFilter = new android.graphics.PorterDuffColorFilter(
                        color, android.graphics.PorterDuff.Mode.SRC_IN);
                packPaint.setColorFilter(packColorFilter);
            }
            packPaint.setAlpha((int) (fullAlpha * packAlpha));
            packDst.set(0, 0, getIntrinsicWidth(), getIntrinsicHeight());
            canvas.drawBitmap(packBmp, null, packDst, packPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
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
