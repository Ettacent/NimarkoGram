package app.nimarkogram.messenger.utils.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import kotlin.Metadata;
import kotlin.math.MathKt;
import okhttp3.internal.url._UrlKt;
import org.telegram.messenger.AndroidUtilities;

@Metadata(d1 = {"\u0000:\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\b\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\r\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0007\n\u0002\b\u0005\u0018\u0000 \u00182\u00020\u0001:\u0001\u0018B\u000f\u0012\u0006\u0010\u0002\u001a\u00020\u0003¢\u0006\u0004\b\u0004\u0010\u0005J4\u0010\u0006\u001a\u00020\u00032\u0006\u0010\u0007\u001a\u00020\b2\b\u0010\t\u001a\u0004\u0018\u00010\n2\u0006\u0010\u000b\u001a\u00020\u00032\u0006\u0010\f\u001a\u00020\u00032\b\u0010\r\u001a\u0004\u0018\u00010\u000eH\u0016JR\u0010\u000f\u001a\u00020\u00102\u0006\u0010\u0011\u001a\u00020\u00122\b\u0010\t\u001a\u0004\u0018\u00010\n2\u0006\u0010\u000b\u001a\u00020\u00032\u0006\u0010\f\u001a\u00020\u00032\u0006\u0010\u0013\u001a\u00020\u00142\u0006\u0010\u0015\u001a\u00020\u00032\u0006\u0010\u0016\u001a\u00020\u00032\u0006\u0010\u0017\u001a\u00020\u00032\u0006\u0010\u0007\u001a\u00020\bH\u0016R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004¢\u0006\u0002\n\u0000¨\u0006\u0019"}, d2 = {"Lcom/exteragram/messenger/utils/ui/ColorRectSpan;", "Landroid/text/style/ReplacementSpan;", "color", _UrlKt.FRAGMENT_ENCODE_SET, "<init>", "(I)V", "getSize", "paint", "Landroid/graphics/Paint;", "text", _UrlKt.FRAGMENT_ENCODE_SET, "start", "end", "fm", "Landroid/graphics/Paint$FontMetricsInt;", "draw", _UrlKt.FRAGMENT_ENCODE_SET, "canvas", "Landroid/graphics/Canvas;", "x", _UrlKt.FRAGMENT_ENCODE_SET, "top", "y", "bottom", "Companion", "TMessagesProj"}, k = 1, mv = {2, 2, 0}, xi = 48)
 
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
