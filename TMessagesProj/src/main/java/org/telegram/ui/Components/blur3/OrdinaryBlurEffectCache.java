package org.telegram.ui.Components.blur3;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;

@RequiresApi(Build.VERSION_CODES.S)
final class OrdinaryBlurEffectCache {
    private static final int LIMIT = 16;
    private static final ArrayList<Entry> entries = new ArrayList<>(LIMIT);

    private static final class Entry {
        final float x, y;
        final int passes;
        final RenderEffect input, effect;
        Entry(float x, float y, RenderEffect input, RenderEffect effect, int passes) {
            this.x = x;
            this.y = y;
            this.input = input;
            this.effect = effect;
            this.passes = passes;
        }
    }

    static synchronized RenderEffect get(float x, float y, RenderEffect input, int passes) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (Float.compare(entry.x, x) == 0 && Float.compare(entry.y, y) == 0
                    && entry.input == input && entry.passes == passes) {
                if (i != entries.size() - 1) {
                    entries.remove(i);
                    entries.add(entry);
                }
                return entry.effect;
            }
        }
        RenderEffect effect = input;
        for (int i = 0; i < passes; i++) {
            effect = effect == null
                    ? RenderEffect.createBlurEffect(x, y, Shader.TileMode.CLAMP)
                    : RenderEffect.createBlurEffect(x, y, effect, Shader.TileMode.CLAMP);
        }
        if (entries.size() == LIMIT) entries.remove(0);
        entries.add(new Entry(x, y, input, effect, passes));
        return effect;
    }

    private OrdinaryBlurEffectCache() {}
}
