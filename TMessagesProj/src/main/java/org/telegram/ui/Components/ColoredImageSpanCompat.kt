/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components

import android.text.style.ReplacementSpan

abstract class ColoredImageSpanCompat : ReplacementSpan() {
    @JvmSynthetic
    open fun setOverrideColor(color: Int) {
        applyLegacyOverrideColor(color)
    }

    protected abstract fun applyLegacyOverrideColor(color: Int)
}
