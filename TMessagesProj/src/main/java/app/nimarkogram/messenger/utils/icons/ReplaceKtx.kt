/**
 * Ported from Cherrygram (uz.unnarsx.cherrygram.core.icons.ReplaceKtx).
 * Originally Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 */

package app.nimarkogram.messenger.utils.icons

import android.util.SparseIntArray

fun newSparseInt(vararg intPairs: Pair<Int, Int>) = SparseIntArray().apply {
    intPairs.forEach {
        this.put(it.first, it.second)
    }
}

fun newHashMap(vararg intPairs: Pair<Int, Int>) = HashMap<Int, Int>().apply {
    intPairs.forEach {
        this[it.first] = it.second
    }
}
