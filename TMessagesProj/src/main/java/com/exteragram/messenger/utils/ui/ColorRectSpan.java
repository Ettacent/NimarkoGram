package com.exteragram.messenger.utils.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;

import org.telegram.messenger.AndroidUtilities;

public final class ColorRectSpan extends ReplacementSpan {

    private static final ThreadLocal<Paint> colorPaint = new ThreadLocal<Paint>() {
        @Override
        protected Paint initialValue() {
            return new Paint(Paint.ANTI_ALIAS_FLAG);
        }
    };
    private static final int offset = AndroidUtilities.dp(2.0f);

    private final int color;

    public ColorRectSpan(int color) {
        this.color = color;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, Paint paint) {
        try {
            TextPaint textPaint = (paint instanceof TextPaint) ? (TextPaint) paint : new TextPaint(paint);
            if (text instanceof Spanned) {
                CharacterStyle[] spans = ((Spanned) text).getSpans(start, end, CharacterStyle.class);
                for (CharacterStyle characterStyle : spans) {
                    if (characterStyle != this) {
                        characterStyle.updateDrawState(textPaint);
                    }
                }
            }
            canvas.drawText(text, start, end, x, y, textPaint);
            float textSize = textPaint.getTextSize() * 0.9f;
            float measured = textPaint.measureText(text, start, end);
            float cy = (bottom + top) / 2.0f;
            float half = textSize / 2.0f;
            float left = x + measured + offset;
            float topRect = cy - half;
            float right = left + textSize;
            float bottomRect = cy + half;
            float radius = textSize * 0.285f;
            Paint p = colorPaint.get();
            p.setColor(this.color);
            canvas.drawRoundRect(left, topRect, right, bottomRect, radius, radius, p);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        try {
            return Math.round(paint.measureText(text, start, end) + offset + (int) paint.getTextSize());
        } catch (Throwable t) {
            return 0;
        }
    }
}
