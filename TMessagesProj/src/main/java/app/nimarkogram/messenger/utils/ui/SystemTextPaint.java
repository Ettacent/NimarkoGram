/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.utils.ui;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import java.util.WeakHashMap;
public final class SystemTextPaint extends TextPaint {
    private static final WeakHashMap<SystemTextPaint, Boolean> paints = new WeakHashMap<>();
    private static volatile int adjustment;
    private Typeface baseTypeface;
    private Typeface appliedTypeface;
    private boolean resolvedTypeface;
    private boolean readingTypeface;
    public static int getWeightAdjustment() {
        return adjustment;
    }
    public SystemTextPaint(int flags) {
        super(flags);
        synchronized (paints) {
            paints.put(this, Boolean.TRUE);
            applyTypeface();
        }
    }
    public static void configure(Configuration configuration, boolean enabled) {
        int value = Build.VERSION.SDK_INT >= 31 && enabled ? configuration.fontWeightAdjustment : 0;
        if (value == Integer.MAX_VALUE) value = 0;
        value = Math.max(-1000, Math.min(1000, value));
        synchronized (paints) {
            if (adjustment == value) return;
            adjustment = value;
            for (SystemTextPaint paint : paints.keySet()) {
                if (paint != null) paint.applyTypeface();
            }
        }
    }
    @Override
    public Typeface getTypeface() {
        boolean wasReadingTypeface = readingTypeface;
        readingTypeface = true;
        try {
            return super.getTypeface();
        } finally {
            readingTypeface = wasReadingTypeface;
        }
    }

    @Override
    public Typeface setTypeface(Typeface typeface) {
        if (readingTypeface) {
            appliedTypeface = typeface;
            return super.setTypeface(typeface);
        }
        if (!resolvedTypeface && typeface == appliedTypeface) return typeface;
        baseTypeface = typeface;
        resolvedTypeface = false;
        return applyTypeface();
    }
    public void copyTypefaceFrom(Paint source) {
        if (source == this) return;
        if (source instanceof SystemTextPaint) {
            SystemTextPaint other = (SystemTextPaint) source;
            baseTypeface = other.baseTypeface;
            resolvedTypeface = other.resolvedTypeface;
        } else {
            baseTypeface = source.getTypeface();
            resolvedTypeface = true;
        }
        applyTypeface();
    }
    @Override
    public void set(Paint source) {
        if (source == this) return;
        super.set(source);
        copyTypefaceFrom(source);
    }
    @Override
    public void set(TextPaint source) {
        if (source == this) return;
        super.set(source);
        copyTypefaceFrom(source);
    }
    @Override
    public void reset() {
        super.reset();
        baseTypeface = null;
        resolvedTypeface = false;
        applyTypeface();
    }
    private Typeface applyTypeface() {
        Typeface typeface = resolvedTypeface ? baseTypeface : adjusted(baseTypeface);
        Typeface result = super.setTypeface(typeface);
        appliedTypeface = typeface;
        return result;
    }
    public static void setSpanTypeface(Paint paint, Typeface typeface) {
        paint.setTypeface(paint instanceof SystemTextPaint ? typeface : adjusted(typeface));
    }
    public static void copyTypeface(Paint target, Paint source) {
        if (target instanceof SystemTextPaint) {
            ((SystemTextPaint) target).copyTypefaceFrom(source);
        } else {
            target.setTypeface(source.getTypeface());
        }
    }
    private static Typeface adjusted(Typeface typeface) {
        int weightAdjustment = adjustment;
        if (weightAdjustment != 0 && Build.VERSION.SDK_INT >= 31) {
            Typeface family = typeface == null ? Typeface.DEFAULT : typeface;
            int weight = Math.max(1, Math.min(1000, family.getWeight() + weightAdjustment));
            typeface = Typeface.create(family, weight, family.isItalic());
        }
        return typeface;
    }
}
