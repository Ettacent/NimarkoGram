/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import kotlin.math.MathKt;
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

    public ColorRectSpan(int i) {
        this.color = i;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x2, int top, int y, int bottom, Paint paint) {
        TextPaint textPaint = (TextPaint) paint;
        if (text instanceof Spanned) {
            for (CharacterStyle characterStyle : (CharacterStyle[]) ((Spanned) text).getSpans(start, end, CharacterStyle.class)) {
                if (characterStyle != this) {
                    characterStyle.updateDrawState(textPaint);
                }
            }
        }
        canvas.drawText(text, start, end, x2, y, textPaint);
        float textSize = textPaint.getTextSize() * 0.9f;
        float fMeasureText = textPaint.measureText(text, start, end);
        float f = (bottom + top) / 2.0f;
        float f2 = textSize / 2.0f;
        float f3 = x2 + fMeasureText + offset;
        float f4 = f - f2;
        float f5 = f3 + textSize;
        float f6 = f + f2;
        float f7 = textSize * 0.285f;
        Paint paint2 = colorPaint.get();
        paint2.setColor(this.color);
        canvas.drawRoundRect(f3, f4, f5, f6, f7, f7, paint2);
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return MathKt.roundToInt(paint.measureText(text, start, end) + offset + ((int) paint.getTextSize()));
    }
}
