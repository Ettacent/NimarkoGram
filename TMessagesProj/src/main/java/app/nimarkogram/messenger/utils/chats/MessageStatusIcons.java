/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils.chats;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;


import org.telegram.messenger.ApplicationLoader;

import org.telegram.ui.ActionBar.Theme;
import app.nimarkogram.messenger.icons.NimarkoIconResources;
import java.util.Objects;

final class MessageStatusIcons {
    private static final SparseArray<Bitmap> masks = new SparseArray<>();
    private static final SparseArray<Drawable> sources = new SparseArray<>();
    private static int cachedDensity;

    private static Resources cachedResources;
    private static long cachedGeneration;
    private static boolean cachedDark;
    private MessageStatusIcons() { }

    static synchronized Resources ensureCurrentResources() {
        Resources resources = ApplicationLoader.applicationContext.getResources();
        int density = resources.getDisplayMetrics().densityDpi;
        long generation = resources instanceof NimarkoIconResources
                ? ((NimarkoIconResources) resources).getReplacementGeneration() : -1L;
        boolean dark = Theme.isCurrentThemeDark();
        if (cachedResources != resources || cachedGeneration != generation || cachedDensity != density || cachedDark != dark) {
            masks.clear();
            sources.clear();
            cachedDensity = density;
            cachedResources = resources;
            cachedGeneration = generation;
            cachedDark = dark;
            NimarkoChatHelper.forwardsDrawable = NimarkoChatHelper.editedDrawable = null;
            NimarkoChatHelper.forwardsSpan = NimarkoChatHelper.editedSpan = null;
            NimarkoChatsHelper.forwardsDrawable = NimarkoChatsHelper.editedDrawable = null;
            NimarkoChatsHelper.forwardsSpan = NimarkoChatsHelper.editedSpan = null;
        }
        return resources;
    }

    static synchronized Drawable create(int resourceId) {
        Resources resources = ensureCurrentResources();
        int density = resources.getDisplayMetrics().densityDpi;
        Bitmap mask = masks.get(resourceId);
        if (mask == null) {
            Drawable vector = Objects.requireNonNull(resources.getDrawable(resourceId)).mutate();
            int width = Math.max(1, vector.getIntrinsicWidth());
            int height = Math.max(1, vector.getIntrinsicHeight());
            mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mask.setDensity(density);
            vector.setBounds(0, 0, width, height);
            vector.draw(new Canvas(mask));
            masks.put(resourceId, mask);
            sources.put(resourceId, vector);
        }
        return NimarkoIconResources.preserveTintPolicy(sources.get(resourceId), resources, mask).mutate();
    }
}
