/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Paint;
import android.text.TextUtils;

public final class ScheduledQueuePresentation {
    private ScheduledQueuePresentation() {}

    static int width(Paint paint, CharSequence text) {
        return TextUtils.isEmpty(text) ? 0 : (int) Math.ceil(paint.measureText(text, 0, text.length()));
    }

    static int avatarReduction(int titleWidth, int capacity, int avatarSize, int compactSize) {
        return titleWidth > Math.max(0, capacity) ? Math.max(0, avatarSize - compactSize) : 0;
    }

    static int titleLeft(int columnLeft, int columnWidth, int titleWidth, float islandCenter, boolean centered, boolean rtl) {
        int last = columnLeft + Math.max(0, columnWidth - titleWidth);
        if (!centered) return rtl ? last : columnLeft;
        return Math.max(columnLeft, Math.min(last, Math.round(islandCenter - titleWidth / 2f)));
    }

    static CharSequence label(Paint paint, CharSequence full, CharSequence shortLabel, int capacity) {
        if (TextUtils.isEmpty(shortLabel) || TextUtils.isEmpty(full)) return full;
        int width = width(paint, full);
        return width > Math.max(0, capacity) && width(paint, shortLabel) < width ? shortLabel : full;
    }

    public static String emptyLabel(String value) {
        if (TextUtils.isEmpty(value)) return value;
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
        int punctuation = end;
        while (end > 0) {
            if (value.charAt(end - 1) == '…') end--;
            else if (end >= 3 && value.startsWith("...", end - 3)) end -= 3;
            else break;
        }
        if (end == punctuation || end == 0) return value;
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
        return end == 0 ? value : value.substring(0, end);
    }
}
