/**
 * Ported from Cherrygram (uz.unnarsx.cherrygram.core.icons.icon_replaces.BaseIconReplace).
 * Originally Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 */

package app.nimarkogram.messenger.utils.icons.icon_replaces

abstract class BaseIconReplace {
    abstract val replaces: HashMap<Int, Int>

    fun wrap(id: Int): Int {
        return replaces[id] ?: id
    }

}
