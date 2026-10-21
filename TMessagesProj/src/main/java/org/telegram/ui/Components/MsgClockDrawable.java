/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import android.util.SparseArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.icons.NimarkoIconResources;
import app.nimarkogram.messenger.utils.NimarkoUiAnimationClock;
public class MsgClockDrawable extends Drawable {

    private ConstantState constantState;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SparseArray<LinearGradient[]> liquidMaterials = new SparseArray<>(8);
    private final int[] liquidMaterialKeys = new int[8];
    private int nextMaterialSlot;
    private int alpha = 255;
    private int colorAlpha = 255;
    private long animationTime;
    private long prevTime = -1;
    private int clockEpoch = -1;
    private int color = Color.BLACK;
    private int preparedPack = -1;
    private int preparedColor;

    public MsgClockDrawable() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(AndroidUtilities.dp(1f));
        handsPaint.setStyle(Paint.Style.STROKE);
        handsPaint.setStrokeCap(Paint.Cap.ROUND);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(0.25f);
    }

    @Override
    public void draw(Canvas canvas) {

        long currentTime = NimarkoUiAnimationClock.now();
        int epoch = NimarkoUiAnimationClock.epoch();
        if (prevTime >= 0 && epoch == clockEpoch && !NimarkoUiAnimationClock.isPaused()) {
            animationTime += Math.max(0, currentTime - prevTime);
        }
        prevTime = currentTime;
        clockEpoch = epoch;
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        NimarkoIconResources.NavigationIcons icons = NimarkoIconResources.activeNavigationIcons();
        int selection = icons == null ? NimarkoConfig.ICON_REPLACE_NONE : icons.selection;
        if (selection == NimarkoConfig.ICON_REPLACE_SOLAR
                || selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS
                || selection == NimarkoConfig.ICON_REPLACE_PLUMPY) {
            drawPackClock(canvas, bounds, selection);
            return;
        }
        int r = Math.min(bounds.width(), bounds.height());
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), (r >> 1) - AndroidUtilities.dp(0.5f), paint);
        drawHands(canvas, bounds.centerX(), bounds.centerY(),
                AndroidUtilities.dp(3), AndroidUtilities.dp(2.3f), paint);
    }

    private void drawHands(Canvas canvas, float cx, float cy, float minuteLength, float hourLength, Paint hands) {
        float rotateTime = 1500;
        float rotateHourTime = rotateTime * 3;

        canvas.save();
        canvas.rotate(360 * (animationTime % rotateTime) / rotateTime, cx, cy);
        canvas.drawLine(cx, cy, cx, cy - minuteLength, hands);
        canvas.restore();

        canvas.save();
        canvas.rotate(360 * (animationTime % rotateHourTime) / rotateHourTime, cx, cy);
        canvas.drawLine(cx, cy, cx + hourLength, cy, hands);
        canvas.restore();
    }

    private void drawPackClock(Canvas canvas, Rect bounds, int selection) {
        int opacity = (int) (alpha * (colorAlpha / 255f));
        if (opacity == 0) {
            return;
        }
        preparePackPaints(selection);
        float size = Math.min(bounds.width(), bounds.height());
        int save = canvas.save();
        canvas.translate(bounds.exactCenterX() - size / 2f, bounds.exactCenterY() - size / 2f);
        canvas.scale(size / 12f, size / 12f);
        if (opacity < 255) {
            canvas.saveLayerAlpha(0, 0, 12, 12, opacity);
        }
        canvas.drawCircle(6, 6, 5.5f, facePaint);
        if (selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS) {
            canvas.drawCircle(6, 6, 5.5f, rimPaint);
        }
        drawHands(canvas, 6, 6, 3, 2.3f, handsPaint);
        canvas.restoreToCount(save);
    }

    public boolean drawFrame(Canvas canvas, Paint foreground, float cx, float cy, float radius,
                             float minuteAngle, float hourAngle, float minuteLength, float hourLength) {
        NimarkoIconResources.NavigationIcons icons = NimarkoIconResources.activeNavigationIcons();
        int selection = icons == null ? NimarkoConfig.ICON_REPLACE_NONE : icons.selection;
        if (selection != NimarkoConfig.ICON_REPLACE_SOLAR
                && selection != NimarkoConfig.ICON_REPLACE_LIQUID_GLASS
                && selection != NimarkoConfig.ICON_REPLACE_PLUMPY) {
            return false;
        }
        int sourceAlpha = foreground.getAlpha();
        if (sourceAlpha == 0 || radius <= 0) {
            return true;
        }
        int savedColor = color;
        try {
            color = foreground.getColor();
            preparePackPaints(selection);
        } finally {
            color = savedColor;
        }
        float strokeWidth = foreground.getStrokeWidth();
        float extent = Math.max(radius, Math.max(minuteLength, hourLength)) + strokeWidth / 2f + 1f;
        int save = canvas.save();
        try {
            if (sourceAlpha < 255) {
                canvas.saveLayerAlpha(cx - extent, cy - extent, cx + extent, cy + extent, sourceAlpha);
            }
            int faceSave = canvas.save();
            float scale = radius / 5.5f;
            canvas.translate(cx, cy);
            canvas.scale(scale, scale);
            canvas.translate(-6, -6);
            framePaint.set(facePaint);
            framePaint.setStrokeWidth(strokeWidth / scale);
            canvas.drawCircle(6, 6, selection == NimarkoConfig.ICON_REPLACE_PLUMPY
                    ? 5.5f + strokeWidth / (2f * scale) : 5.5f, framePaint);
            if (selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS) {
                framePaint.set(rimPaint);
                framePaint.setStrokeWidth(strokeWidth / scale);
                canvas.drawCircle(6, 6, 5.5f, framePaint);
            }
            canvas.restoreToCount(faceSave);
            framePaint.set(foreground);
            framePaint.setShader(null);
            framePaint.setStyle(Paint.Style.STROKE);
            framePaint.setColor(handsPaint.getColor());
            double angle = Math.toRadians(minuteAngle - 90);
            canvas.drawLine(cx, cy, cx + (float) Math.cos(angle) * minuteLength,
                    cy + (float) Math.sin(angle) * minuteLength, framePaint);
            angle = Math.toRadians(hourAngle - 90);
            canvas.drawLine(cx, cy, cx + (float) Math.cos(angle) * hourLength,
                    cy + (float) Math.sin(angle) * hourLength, framePaint);
        } finally {
            canvas.restoreToCount(save);
        }
        return true;
    }

    private void preparePackPaints(int selection) {
        int rgb = color | 0xff000000;
        if (preparedPack == selection && preparedColor == rgb) {
            return;
        }
        preparedPack = selection;
        preparedColor = rgb;
        facePaint.setShader(null);
        facePaint.setStyle(Paint.Style.FILL);
        facePaint.setColor(rgb);
        rimPaint.setShader(null);
        handsPaint.setColor(rgb);
        handsPaint.setStrokeWidth(1.05f);
        if (selection == NimarkoConfig.ICON_REPLACE_SOLAR) {
            facePaint.setStyle(Paint.Style.STROKE);
            facePaint.setStrokeWidth(0.85f);
            handsPaint.setStrokeWidth(0.85f);
        } else if (selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS) {
            LinearGradient[] material = liquidMaterials.get(rgb);
            if (material == null) {
                material = new LinearGradient[] {
                        new LinearGradient(2, 1, 10, 11, new int[] {
                                ColorUtils.setAlphaComponent(rgb, 158),
                                ColorUtils.setAlphaComponent(rgb, 112),
                                ColorUtils.setAlphaComponent(rgb, 66),
                                ColorUtils.setAlphaComponent(rgb, 106)
                        }, null, Shader.TileMode.CLAMP),
                        new LinearGradient(2, 1, 10, 11,
                                ColorUtils.setAlphaComponent(rgb, 204),
                                ColorUtils.setAlphaComponent(rgb, 87), Shader.TileMode.CLAMP)
                };
                if (liquidMaterials.size() == 8) {
                    liquidMaterials.remove(liquidMaterialKeys[nextMaterialSlot]);
                }
                liquidMaterials.put(rgb, material);
                liquidMaterialKeys[nextMaterialSlot] = rgb;
                nextMaterialSlot = (nextMaterialSlot + 1) % liquidMaterialKeys.length;
            }
            facePaint.setShader(material[0]);
            rimPaint.setColor(rgb);
            rimPaint.setShader(material[1]);
            handsPaint.setColor(ColorUtils.setAlphaComponent(rgb, 230));
        } else {
            facePaint.setColor(ColorUtils.setAlphaComponent(rgb, 114));
            handsPaint.setStrokeWidth(1.2f);
        }
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
        i = Math.max(0, Math.min(255, i));
        if (alpha != i) {
            alpha = i;
            paint.setAlpha((int) (alpha * (colorAlpha / 255f)));
        }
    }

    @Override
    public int getAlpha() {
        return alpha;
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
