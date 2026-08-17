/*
 * Ported from Cherrygram (uz.unnarsx.cherrygram.core.icons.ReplaceKtx).
 * Originally Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 * Ported to Java for NimarkoGram.
 */
package app.nimarkogram.messenger.icons;

import java.util.HashMap;

public final class IconReplaceUtils {
    private IconReplaceUtils() {}

    public static HashMap<Integer, Integer> put(HashMap<Integer, Integer> map, int from, int to) {
        map.put(from, to);
        return map;
    }
}
