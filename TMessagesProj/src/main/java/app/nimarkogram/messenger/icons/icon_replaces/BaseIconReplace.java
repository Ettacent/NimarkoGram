/*
 * Ported from Cherrygram (uz.unnarsx.cherrygram.core.icons.icon_replaces.BaseIconReplace).
 * Originally Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 * Ported to Java for NimarkoGram.
 */
package app.nimarkogram.messenger.icons.icon_replaces;

import java.util.HashMap;
import java.util.HashSet;

public abstract class BaseIconReplace {

    protected final HashMap<Integer, Integer> replaces = new HashMap<>();

    protected final HashSet<Integer> noTint = new HashSet<>();

    public final int wrap(int id) {
        Integer mapped = replaces.get(id);
        return mapped != null ? mapped : id;
    }

    public final boolean isNoTint(int id) {
        return noTint.contains(id);
    }

    public final HashMap<Integer, Integer> getReplaces() {
        return replaces;
    }
}
