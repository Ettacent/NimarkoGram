/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import androidx.annotation.Keep;

import org.telegram.messenger.AndroidUtilities;

import org.telegram.messenger.R;

import app.nimarkogram.messenger.icons.NimarkoIconResources;
public class AnimatedArrowDrawable extends Drawable {

    private Paint paint;
    private final MediaActionDrawable.PackIconRenderer packIconRenderer = new MediaActionDrawable.PackIconRenderer();
    private Path path = new Path();
    private float animProgress;
    private float animateToProgress;
    private long lastUpdateTime;
    private boolean isSmall;
    private float customWidthDp;
    private float customHeightDp;
    private float customStrokeWidthDp;

    public AnimatedArrowDrawable(int color, boolean small) {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        isSmall = small;

        updatePath();
    }

    public AnimatedArrowDrawable(int color, float widthDp, float heightDp, float strokeWidthDp) {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dpf2(strokeWidthDp));
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        isSmall = true;
        customWidthDp = widthDp;
        customHeightDp = heightDp;
        customStrokeWidthDp = strokeWidthDp;

        updatePath();
    }

    @Override
    public void draw(Canvas c) {
        NimarkoIconResources.NavigationIcons icons = NimarkoIconResources.activeNavigationIcons();
        NimarkoIconResources.PreparedIcon arrow = MediaActionDrawable.PackIconRenderer.get(icons, R.drawable.arrow_more);
        if (arrow == null) {
        c.drawPath(path, paint);
        } else {
            float centerX;
            float centerY;
            float size;
            c.save();
            if (customWidthDp > 0 && customHeightDp > 0) {
                centerX = dpf2(customWidthDp) / 2f;
                centerY = dpf2(customHeightDp) / 2f;
                size = Math.min(Math.min(dpf2(customWidthDp), dpf2(customHeightDp)), dp(24));
                c.clipRect(0, 0, dpf2(customWidthDp), dpf2(customHeightDp));
            } else if (isSmall) {
                centerX = dp(8);
                centerY = dp(6);
                size = dp(14);
            } else {
                centerX = dp(13);
                centerY = dp(12) + dp(2) * animProgress;
                size = dp(24);
            }
            float direction = animProgress * 2f - 1f;
            float minimumFold = Math.min(1f, Math.max(1f, paint.getStrokeWidth()) / size);
            float fold = Math.max(minimumFold, Math.abs(direction));
            float down = Math.max(0f, Math.min(1f, 0.5f + direction / (2f * minimumFold)));
            c.translate(centerX, centerY);
            int layer = paint.getAlpha() == 255 ? c.save() : c.saveLayerAlpha(-size, -size, size, size, paint.getAlpha());
            c.save();
            c.scale(1f, -fold);
            packIconRenderer.draw(c, arrow, paint, 0, 0, size, 1f - down, 0);
            c.restore();
            c.scale(1f, fold);
            packIconRenderer.draw(c, arrow, paint, 0, 0, size, down, 0);
            c.restoreToCount(layer);
            c.restore();
        }
        checkAnimation();
    }

    private void updatePath() {
        path.reset();
        float p = animProgress * 2 - 1;
        if (customWidthDp > 0 && customHeightDp > 0) {
            final float halfStroke = dpf2(customStrokeWidthDp) / 2f;
            final float left = halfStroke;
            final float right = dpf2(customWidthDp) - halfStroke;
            final float center = (left + right) / 2f;
            final float top = halfStroke;
            final float bottom = dpf2(customHeightDp) - halfStroke;
            final float range = bottom - top;
            path.moveTo(left, bottom - range * animProgress);
            path.lineTo(center, top + range * animProgress);
            path.lineTo(right, bottom - range * animProgress);
        } else if (isSmall) {
            path.moveTo(dp(3), dp(6) - dp(2) * p);
            path.lineTo(dp(8), dp(6) + dp(2) * p);
            path.lineTo(dp(13), dp(6) - dp(2) * p);
        } else {
            path.moveTo(dp(4.5f), dp(12) - dp(4) * p + dp(2) * animProgress);
            path.lineTo(dp(13), dp(12) + dp(4) * p + dp(2) * animProgress);
            path.lineTo(dp(21.5f), dp(12) - dp(4) * p + dp(2) * animProgress);
        }
    }

    @Keep
    public void setAnimationProgress(float progress) {
        animProgress = progress;
        animateToProgress = progress;
        updatePath();
        invalidateSelf();
    }

    public void setAnimationProgressAnimated(float progress) {
        if (animateToProgress == progress) {
            return;
        }
        animateToProgress = progress;
        lastUpdateTime = SystemClock.elapsedRealtime();
        invalidateSelf();
    }

    private void checkAnimation() {
        if (animateToProgress != animProgress) {
            long newTime = SystemClock.elapsedRealtime();
            long dt = newTime - lastUpdateTime;
            lastUpdateTime = newTime;
            if (animProgress < animateToProgress) {
                animProgress += dt / 180.0f;
                if (animProgress > animateToProgress) {
                    animProgress = animateToProgress;
                }
            } else {
                animProgress -= dt / 180.0f;
                if (animProgress < animateToProgress) {
                    animProgress = animateToProgress;
                }
            }
            updatePath();
            invalidateSelf();
        }
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    @Keep
    public float getAnimationProgress() {
        return animProgress;
    }

    @Override
    public void setAlpha(int alpha) {

    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return customWidthDp > 0 ? dp(customWidthDp) : dp(26);
    }

    @Override
    public int getIntrinsicHeight() {
        return customHeightDp > 0 ? dp(customHeightDp) : dp(26);
    }
}
