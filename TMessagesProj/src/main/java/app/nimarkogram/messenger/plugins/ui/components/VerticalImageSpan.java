package app.nimarkogram.messenger.plugins.ui.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class VerticalImageSpan extends ImageSpan {
    public VerticalImageSpan(Drawable drawable) {
        super(drawable);
    }

    public static SpannableStringBuilder createSpan(Context context, int resId, String source, String match, int colorKey, Theme.ResourcesProvider resourcesProvider) {
        SpannableStringBuilder builder = new SpannableStringBuilder(source);
        ArrayList<Integer> indexes = new ArrayList<>();
        int index = source.indexOf(match);
        while (index >= 0) {
            indexes.add(index);
            index = source.indexOf(match, index + 1);
        }
        
        Drawable drawable = ContextCompat.getDrawable(context, resId);
        if (drawable == null) return builder;
        
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(colorKey, resourcesProvider), PorterDuff.Mode.MULTIPLY));
        
        for (Integer idx : indexes) {
            builder.setSpan(new VerticalImageSpan(drawable), idx, match.length() + idx, 33);
        }
        return builder;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        Rect bounds = getDrawable().getBounds();
        if (fm != null) {
            Paint.FontMetricsInt paintFm = paint.getFontMetricsInt();
            int fontHeight = paintFm.descent - paintFm.ascent;
            int drawableHeight = bounds.bottom - bounds.top;
            
            int centerY = paintFm.ascent + fontHeight / 2;
            
            fm.ascent = centerY - drawableHeight / 2;
            fm.top = fm.ascent;
            fm.bottom = centerY + drawableHeight / 2;
            fm.descent = fm.bottom;
        }
        return bounds.right;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        Drawable drawable = getDrawable();
        canvas.save();
        int fontDescent = paint.getFontMetricsInt().descent;
        int transY = (y + fontDescent) - ((fontDescent - paint.getFontMetricsInt().ascent) / 2) - ((drawable.getBounds().bottom - drawable.getBounds().top) / 2);
        canvas.translate(x, transY);
        if (LocaleController.isRTL) {
            canvas.scale(-1.0f, 1.0f, drawable.getIntrinsicWidth() / 2.0f, drawable.getIntrinsicHeight() / 2.0f);
        }
        drawable.draw(canvas);
        canvas.restore();
    }
}