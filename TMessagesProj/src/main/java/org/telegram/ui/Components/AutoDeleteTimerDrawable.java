/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.icons.NimarkoIconResources;
import org.telegram.messenger.R;

final class AutoDeleteTimerDrawable extends Drawable {
    private final int selection;
    private final int size;
    private final Drawable artwork;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF circle = new RectF(1.25f, 1.25f, 19.75f, 19.75f);

    static Drawable create(Context context, int selection, int size) {
        if (selection != NimarkoConfig.ICON_REPLACE_SOLAR
                && selection != NimarkoConfig.ICON_REPLACE_LIQUID_GLASS
                && selection != NimarkoConfig.ICON_REPLACE_PLUMPY) {
            return null;
        }
        Drawable artwork = null;
        if (selection == NimarkoConfig.ICON_REPLACE_SOLAR) {
            try {
                artwork = NimarkoIconResources.getStockDrawable(context,
                        R.drawable.msg_mini_autodelete_empty_solar).mutate();
                if (artwork.getIntrinsicWidth() <= 0 || artwork.getIntrinsicHeight() <= 0) {
                    return null;
                }
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
        return new AutoDeleteTimerDrawable(selection, size, artwork);
    }

    private AutoDeleteTimerDrawable(int selection, int size, Drawable artwork) {
        this.selection = selection;
        this.size = size;
        this.artwork = artwork;
        paint.setColor(0xffffffff);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(selection == NimarkoConfig.ICON_REPLACE_SOLAR ? 1.35f : 1.8f);
        rim.set(paint);
        rim.setStrokeWidth(0.35f);
        if (selection == NimarkoConfig.ICON_REPLACE_PLUMPY) {
            circle.set(0.875f, 0.875f, 20.125f, 20.125f);
            paint.setStrokeWidth(1.75f);
        } else if (selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS) {
            paint.setShader(new LinearGradient(2, 1, 19, 20,
                    new int[] {0x9effffff, 0x70ffffff, 0x42ffffff, 0x6affffff},
                    null, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(2, 1, 19, 20,
                    0xccffffff, 0x57ffffff, Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(Canvas canvas) {
        int save = canvas.save();
        try {
            canvas.translate(getBounds().left, getBounds().top);
            if (artwork != null) {
                float scale = Math.min(getBounds().width() / (float) artwork.getIntrinsicWidth(),
                        getBounds().height() / (float) artwork.getIntrinsicHeight());
                canvas.translate((getBounds().width() - artwork.getIntrinsicWidth() * scale) / 2f,
                        (getBounds().height() - artwork.getIntrinsicHeight() * scale) / 2f);
                canvas.scale(scale, scale);
                artwork.setBounds(0, 0, artwork.getIntrinsicWidth(), artwork.getIntrinsicHeight());
                artwork.draw(canvas);
            } else {
                canvas.scale(getBounds().width() / 21f, getBounds().height() / 21f);
                drawShell(canvas, paint);
                if (selection == NimarkoConfig.ICON_REPLACE_LIQUID_GLASS) {
                    drawShell(canvas, rim);
                }
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawShell(Canvas canvas, Paint material) {
        if (selection == NimarkoConfig.ICON_REPLACE_PLUMPY) {
            for (int i = 0; i < 8; i++) {
                canvas.drawArc(circle, -82 + i * 45, 24, false, material);
            }
        } else {
            canvas.drawArc(circle, 90, 180, false, material);
            for (int i = 0; i < 5; i++) {
                canvas.drawArc(circle, -72 + i * 33, 12, false, material);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        rim.setAlpha(alpha);
        if (artwork != null) {
            artwork.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        rim.setColorFilter(colorFilter);
        if (artwork != null) {
            artwork.setColorFilter(colorFilter);
        }
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }
}
