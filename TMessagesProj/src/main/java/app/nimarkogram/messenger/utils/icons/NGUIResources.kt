/**
 * Ported from Cherrygram (uz.unnarsx.cherrygram.core.icons.CGUIResources).
 * Originally Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 */

package app.nimarkogram.messenger.utils.icons

import android.annotation.SuppressLint
import android.content.res.*
import android.graphics.drawable.Drawable
import android.util.Log
import app.nimarkogram.messenger.NimarkoConfig
import app.nimarkogram.messenger.utils.icons.icon_replaces.BaseIconReplace
import app.nimarkogram.messenger.utils.icons.icon_replaces.NoIconReplace
import app.nimarkogram.messenger.utils.icons.icon_replaces.SolarIconReplace
import org.telegram.messenger.BuildVars

@Suppress("DEPRECATION")
@SuppressLint("UseCompatLoadingForDrawables")
class NGUIResources(private val wrapped: Resources) : Resources(wrapped.assets, wrapped.displayMetrics, wrapped.configuration) {

    private var activeReplacement: BaseIconReplace = pickReplacement()

    fun reloadReplacements() {
        activeReplacement = pickReplacement()
        clearCache()
    }

    private fun pickReplacement(): BaseIconReplace {
        return if (NimarkoConfig.iconReplacement == NimarkoConfig.ICON_REPLACE_SOLAR) {
            SolarIconReplace()
        } else {
            NoIconReplace()
        }
    }

    private val drawableCache = object : LinkedHashMap<Triple<Int, Int?, Theme?>, Drawable?>(300, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<Int, Int?, Theme?>, Drawable?>?): Boolean {
            if (size > 300) {
                clearCache()
                return true
            }
            return false
        }
    }

    private var cacheHits = 0
    private var cacheMisses = 0

    private fun clearCache() {
        drawableCache.clear()
        cacheHits = 0
        cacheMisses = 0

        if (BuildVars.DEBUG_VERSION) Log.d("NGUIResources", "🗑 Cache cleared automatically (limit exceeded)!")
    }

    private fun getCachedDrawable(
        cacheKey: Triple<Int, Int?, Theme?>,
        wrappedId: Int,
        loader: () -> Drawable?
    ): Drawable? {
        return drawableCache.getOrPut(cacheKey) {
            cacheMisses++
            if (BuildVars.DEBUG_VERSION) Log.w("NGUIResources", "🛑 Cache MISS ($cacheMisses misses) - Loading new drawable for id=$wrappedId, key=$cacheKey")
            loader()
        }?.let { drawable ->
            drawable.constantState?.newDrawable(wrapped, cacheKey.third)?.mutate() ?: drawable
        }?.also {
            cacheHits++
            if (BuildVars.DEBUG_VERSION) Log.d("NGUIResources", "✅ Cache HIT ($cacheHits hits) - Using cached drawable for id=$wrappedId, key=$cacheKey")
        }
    }

    @Deprecated("Deprecated in Java")
    @Throws(NotFoundException::class)
    override fun getDrawable(id: Int): Drawable? {
        val wrappedId = activeReplacement.wrap(id)
        val cacheKey = Triple(wrappedId, null, null)

        return getCachedDrawable(cacheKey, wrappedId) { wrapped.getDrawable(wrappedId, null) }
    }

    @Throws(NotFoundException::class)
    override fun getDrawable(id: Int, theme: Theme?): Drawable? {
        val wrappedId = activeReplacement.wrap(id)
        val cacheKey = Triple(wrappedId, null, theme)

        return getCachedDrawable(cacheKey, wrappedId) { wrapped.getDrawable(wrappedId, theme) }
    }

    @Deprecated("Deprecated in Java")
    @Throws(NotFoundException::class)
    override fun getDrawableForDensity(id: Int, density: Int): Drawable? {
        val wrappedId = activeReplacement.wrap(id)
        val cacheKey = Triple(wrappedId, density, null)

        return getCachedDrawable(cacheKey, wrappedId) { wrapped.getDrawableForDensity(wrappedId, density, null) }
    }

    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? {
        val wrappedId = activeReplacement.wrap(id)
        val cacheKey = Triple(wrappedId, density, theme)

        return getCachedDrawable(cacheKey, wrappedId) { wrapped.getDrawableForDensity(wrappedId, density, theme) }
    }

}
