/* Modifications Copyright (C) 2026 Ettacent */

/*
 * Ported from Cherrygram (uz.unnarsx.cherrygram.core.icons.CGUIResources).
 * Originally Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 * Ported to Java for NimarkoGram.
 */

package app.nimarkogram.messenger.icons;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import java.util.function.BooleanSupplier;
import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.icons.icon_replaces.BaseIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.NoIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.SolarIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.LiquidGlassFullReplace;
import app.nimarkogram.messenger.icons.icon_replaces.PlumpyFullReplace;

@SuppressLint("UseCompatLoadingForDrawables")
public class NimarkoIconResources extends Resources {

    private static final int CACHE_LIMIT = 300;

    private static final Set<Integer> RENDER_24DP_ICONS = new HashSet<>(
            Arrays.asList(org.telegram.messenger.R.drawable.group_edit_profile));


    private static final Set<Integer> NON_SKINNABLE_DRAWABLES =
            new HashSet<>(Arrays.asList(
                    org.telegram.messenger.R.drawable.calendar_date,
                    org.telegram.messenger.R.drawable.menu_copy,
                    org.telegram.messenger.R.drawable.msg_autodelete_badge2,
                    org.telegram.messenger.R.drawable.msg_autodelete_badge2_solar,
                    org.telegram.messenger.R.drawable.msg_mini_autodelete_empty,
                    org.telegram.messenger.R.drawable.msg_mini_autodelete_empty_solar,
                    org.telegram.messenger.R.drawable.msg_other_new_filled,
                    org.telegram.messenger.R.drawable.msg_other_new_filled_text,
                    org.telegram.messenger.R.drawable.msg_other_new_outline,
                    org.telegram.messenger.R.drawable.settings_wallet,
                    org.telegram.messenger.R.drawable.settings_wallet_dark
            ));

    private static final class ReplacementState {
        final BaseIconReplace replacement;
        final int selection;
        final long generation;

        ReplacementState(BaseIconReplace replacement, int selection, long generation) {
            this.replacement = replacement;
            this.selection = selection;
            this.generation = generation;
        }
    }

    private final Resources wrapped;
    private final Object replacementLock = new Object();
    private volatile ReplacementState replacementState;
    private final Object folderWarmLock = new Object();
    private volatile int warmedFolderSelection = Integer.MIN_VALUE;
    private static volatile NimarkoIconResources sInstalled;

    private Bitmap backArrowBitmap;
    private boolean backArrowBitmapResolved;
    private final Object backArrowLock = new Object();

    private NavigationIcons navigationIcons;

    public static final class PreparedIcon {
        public final Bitmap bitmap;
        public final boolean noTint;

        private PreparedIcon(Bitmap bitmap, boolean noTint) {
            this.bitmap = bitmap;
            this.noTint = noTint;
        }
    }

    public static final class NavigationIcons {
        public final Bitmap back, close, menu;
        public final boolean backNoTint, closeNoTint, menuNoTint;
        public final int selection, densityDpi;
        public final long generation;
        private final SparseArray<PreparedIcon> icons;

        private NavigationIcons(ReplacementState state, int densityDpi, SparseArray<PreparedIcon> icons) {
            this.icons = icons;
            this.selection = state.selection;
            this.generation = state.generation;
            this.densityDpi = densityDpi;
            PreparedIcon b = icons.get(org.telegram.messenger.R.drawable.ic_ab_back);
            PreparedIcon c = icons.get(org.telegram.messenger.R.drawable.ic_close_white);
            PreparedIcon m = icons.get(org.telegram.messenger.R.drawable.list_reorder);
            back = b == null ? null : b.bitmap;
            close = c == null ? null : c.bitmap;
            menu = m == null ? null : m.bitmap;
            backNoTint = b != null && b.noTint;
            closeNoTint = c != null && c.noTint;
            menuNoTint = m != null && m.noTint;
        }

        public PreparedIcon getIcon(int stockId) {
            return icons.get(stockId);
        }
    }

    public static NavigationIcons activeNavigationIcons() {
        NimarkoIconResources self = sInstalled;
        if (self == null) return null;
        synchronized (self.backArrowLock) {
            return self.navigationIcons;
        }
    }

    public static PreparedIcon activeAnimatedIcon(int stockId) {
        NavigationIcons icons = activeNavigationIcons();
        return icons == null ? null : icons.getIcon(stockId);
    }
    @SuppressWarnings("deprecation")
    public NimarkoIconResources(Resources wrapped) {
        super(wrapped.getAssets(), wrapped.getDisplayMetrics(), wrapped.getConfiguration());
        this.wrapped = wrapped;
        applySelection(NimarkoConfig.iconReplacement, 1L);
        prepareBackArrowBitmap();
        sInstalled = this;
        prewarmFolderIconsAsync();
    }

    public static Bitmap activeBackArrowBitmap() {
        NimarkoIconResources self = sInstalled;
        return self == null ? null : self.preparedBackArrowBitmap();
    }

    public static Drawable getStockDrawable(Context context, int id) {
        final Resources resources = context.getResources();
        final Theme theme = context.getTheme();
        if (resources instanceof NimarkoIconResources) {
            return ((NimarkoIconResources) resources).loadRaw(id, 0, theme);
        }
        return resources.getDrawable(id, theme);
    }

    private Bitmap preparedBackArrowBitmap() {
        synchronized (backArrowLock) {
            return backArrowBitmapResolved ? backArrowBitmap : null;
        }
    }

    private Bitmap prepareBackArrowBitmap() {
        while (true) {
            final ReplacementState state = replacementState;
            synchronized (backArrowLock) {
                if (replacementState != state) {
                    continue;
                }
                if (backArrowBitmapResolved) {
                    return backArrowBitmap;
                }
            }

            final NavigationIcons loaded = loadNavigationIcons(state);
            synchronized (backArrowLock) {
                if (replacementState != state) {
                    recycleUnpublishedIcons(loaded);
                    continue;
                }
                if (!backArrowBitmapResolved) {
                    navigationIcons = loaded;
                    backArrowBitmap = loaded.back;
                    backArrowBitmapResolved = true;
                return backArrowBitmap;
            }
                recycleUnpublishedIcons(loaded);
                return backArrowBitmap;
            }
        }
    }

    private NavigationIcons loadNavigationIcons(ReplacementState state) {
        SparseArray<PreparedIcon> icons = new SparseArray<>();
        int[] ids = {
                org.telegram.messenger.R.drawable.ic_ab_back,
                org.telegram.messenger.R.drawable.ic_close_white,
                org.telegram.messenger.R.drawable.list_reorder,
                org.telegram.messenger.R.drawable.ic_action_play,
                org.telegram.messenger.R.drawable.ic_action_pause,
                org.telegram.messenger.R.drawable.msg_secret,
                org.telegram.messenger.R.drawable.msg_download,
                org.telegram.messenger.R.drawable.ng_media_cancel,
                org.telegram.messenger.R.drawable.ng_media_stop,
                org.telegram.messenger.R.drawable.ic_ab_other,
                org.telegram.messenger.R.drawable.msg_search,
                org.telegram.messenger.R.drawable.msg_sendfile,
                org.telegram.messenger.R.drawable.msg_retry,
                org.telegram.messenger.R.drawable.ic_ab_done,
                org.telegram.messenger.R.drawable.msg_add,
                org.telegram.messenger.R.drawable.arrow_more,
                org.telegram.messenger.R.drawable.media_live_on,
                org.telegram.messenger.R.drawable.media_live_off,
                org.telegram.messenger.R.drawable.ng_record_delete_body,
                org.telegram.messenger.R.drawable.ng_record_delete_lid
        };
        int[][] groups = {ids, NimarkoAnimatedIconCatalog.drawableIds(), NimarkoAnimatedCallIcons.drawableIds()};
        Set<Integer> visited = new HashSet<>();
        Bitmap pending = null;
        boolean complete = false;
        try {
            for (int[] group : groups) {
                for (int id : group) {
                    if (!visited.add(id)) continue;
                    pending = loadAnimatedIconBitmap(state, id);
                    if (pending != null) {
                        icons.put(id, new PreparedIcon(pending, state.replacement.isNoTint(id)));
                        pending = null;
                    }
                }
            }
            NavigationIcons result = new NavigationIcons(state, wrapped.getDisplayMetrics().densityDpi, icons);
            complete = true;
            return result;
        } finally {
            if (!complete) {
                for (int i = 0; i < icons.size(); i++) {
                    Bitmap bitmap = icons.valueAt(i).bitmap;
                    if (bitmap == pending) pending = null;
                    bitmap.recycle();
                }
                if (pending != null) pending.recycle();
            }
        }
    }

    private static void recycleUnpublishedIcons(NavigationIcons icons) {
        for (int i = 0; i < icons.icons.size(); i++) {
            icons.icons.valueAt(i).bitmap.recycle();
        }
    }

    private Bitmap loadAnimatedIconBitmap(ReplacementState state, int stockId) {
        if (NON_SKINNABLE_DRAWABLES.contains(stockId)) return null;
        Bitmap bitmap = null;
        try {
            final int wrappedId = state.replacement.wrap(stockId);
            if (wrappedId == stockId) {
                return null;
            }
            Drawable d = wrapped.getDrawable(wrappedId);
            if (d == null) {
                return null;
            }
            d = d.mutate();
            int size = Math.max(1, Math.min(256, (int) Math.ceil(24f * wrapped.getDisplayMetrics().density)));
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = h = size;
            }
            float fit = (float) size / Math.max(w, h);
            w = Math.max(1, Math.round(w * fit));
            h = Math.max(1, Math.round(h * fit));
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            int left = (size - w) / 2, top = (size - h) / 2;
            d.setBounds(left, top, left + w, top + h);
            d.draw(canvas);
            return bitmap;
        } catch (Throwable ignore) {
            if (bitmap != null) bitmap.recycle();
            return null;
        }
    }

    public void reloadReplacements() {
        reloadReplacements(null);
    }

    public long reloadReplacements(final Runnable onApplied) {
        final long generation;
        synchronized (replacementLock) {
            final int selection = NimarkoConfig.iconReplacement;
            ReplacementState state = replacementState;
            if (state == null || state.selection != selection) {
                generation = state == null ? 1L : state.generation + 1L;
                applySelection(selection, generation);
                clearCache();
            } else {
                generation = state.generation;
            }
        }
        prepareBackArrowBitmap();
        prewarmFolderIconsAsync();
        sInstalled = this;
        if (onApplied != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (isReplacementGenerationCurrent(generation)) {
                    onApplied.run();
                }
            });
        }
        return generation;
    }

    public boolean isReplacementGenerationCurrent(long generation) {
        ReplacementState state = replacementState;
        return state != null
                && state.generation == generation
                && state.selection == NimarkoConfig.iconReplacement;
    }

    public boolean hasReplacement(int id) {
        return !NON_SKINNABLE_DRAWABLES.contains(id) && replacementState.replacement.wrap(id) != id;
    }

    public long getReplacementGeneration() {
        return replacementState.generation;
    }

    public int getAppliedSelection() {
        return replacementState.selection;
    }

    public static Drawable preserveTintPolicy(Drawable source, Resources resources, Bitmap bitmap) {
        return source instanceof NoTintBitmapDrawable
                ? new NoTintBitmapDrawable(resources, bitmap)
                : new BitmapDrawable(resources, bitmap);
    }

    public static Drawable copyDrawable(Drawable source, Resources resources) {
        if (source == null) {
            return null;
        }
        if (source instanceof SizedBitmapDrawable) {
            return ((SizedBitmapDrawable) source).copy(resources);
        }
        Drawable.ConstantState state = source.getConstantState();
        if (state == null) {
            return null;
        }
        Drawable copy = (resources == null ? state.newDrawable() : state.newDrawable(resources)).mutate();
        ColorFilter colorFilter = source.getColorFilter();
        if (colorFilter != null) {
            copy.setColorFilter(colorFilter);
        }
        return copy;
    }

    public static Drawable copyDrawable(Drawable source, Resources resources, BooleanSupplier darkTheme) {
        return source instanceof SizedBitmapDrawable
                ? ((SizedBitmapDrawable) source).copy(resources, darkTheme)
                : copyDrawable(source, resources);
    }

    private void applySelection(int selection, long generation) {
        warmedFolderSelection = Integer.MIN_VALUE;
        final BaseIconReplace replacement;
        switch (selection) {
            case NimarkoConfig.ICON_REPLACE_SOLAR:
                replacement = new SolarIconReplace();
                break;
            case NimarkoConfig.ICON_REPLACE_LIQUID_GLASS:
                replacement = new LiquidGlassFullReplace();
                break;
            case NimarkoConfig.ICON_REPLACE_PLUMPY:
                replacement = new PlumpyFullReplace();
                break;
            default:
                replacement = new NoIconReplace();
                break;
        }
        synchronized (backArrowLock) {
            backArrowBitmap = null;
            backArrowBitmapResolved = false;
            navigationIcons = null;
            replacementState = new ReplacementState(replacement, selection, generation);
        }
    }

    public void prewarmFolderIconsAsync() {
        final ReplacementState state = replacementState;
        if (warmedFolderSelection == state.selection) {
            return;
        }
        try {
            Utilities.globalQueue.postRunnable(() -> {
                if (replacementState == state) {
                    prewarmFolderIconsBlocking();
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public void prewarmFolderIconsBlocking() {
        final ReplacementState state = replacementState;
        final int selection = state.selection;
        if (warmedFolderSelection == selection) {
            return;
        }
        synchronized (folderWarmLock) {
            if (warmedFolderSelection == selection) {
                return;
            }
            boolean complete = true;
            for (Integer drawableId : new HashSet<>(
                    app.nimarkogram.messenger.preferences.folders.helpers
                            .FolderIconHelper.folderIcons.values())) {
                if (drawableId == null) {
                    continue;
                }
                try {
                    getDrawable(drawableId);
                } catch (Throwable ignored) {
                    complete = false;
                }
            }
            if (complete && replacementState == state) {
                warmedFolderSelection = selection;
            }
        }
    }


    private static final class CacheKey {
        final int id;
        final int density;
        final Theme theme;
        final long generation;

        final Configuration configuration;
        final int displayDensity;

        CacheKey(int id, int density, Theme theme, long generation, Configuration configuration, int displayDensity) {
            this.id = id;
            this.density = density;
            this.theme = theme;
            this.generation = generation;
            this.configuration = new Configuration(configuration);
            this.displayDensity = displayDensity;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey k = (CacheKey) o;
            return id == k.id && density == k.density && generation == k.generation
                    && displayDensity == k.displayDensity && configuration.equals(k.configuration)
                    && Objects.equals(theme, k.theme);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, density, theme, generation, configuration, displayDensity);
        }
    }

    private final Object cacheLock = new Object();
    private final LinkedHashMap<CacheKey, Drawable> drawableCache =
            new LinkedHashMap<CacheKey, Drawable>(CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, Drawable> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private interface DrawableLoader { Drawable load(); }

    private Drawable cachedGetOrLoad(CacheKey key, DrawableLoader loader) {
        Drawable hit;
        synchronized (cacheLock) {
            hit = drawableCache.get(key);
        }
        if (hit != null) {
            return cached(key, hit);
        }

        final Drawable loaded = loader.load();
        synchronized (cacheLock) {
            hit = drawableCache.get(key);
            if (hit == null) {
                ReplacementState current = replacementState;
                if (loaded != null && current != null && current.generation == key.generation) {
                    drawableCache.put(key, loaded);
                }
                hit = loaded;
            }
        }
        return cached(key, hit);
    }

    private void clearCache() {
        synchronized (cacheLock) {
            drawableCache.clear();
        }
    }

    private Drawable cached(CacheKey key, Drawable loaded) {
        if (loaded == null) return null;
        if (loaded instanceof SizedBitmapDrawable) {
            return copyDrawable(loaded, wrapped);
        }
        Drawable.ConstantState cs = loaded.getConstantState();
        if (cs == null) return loaded;
        return cs.newDrawable(wrapped, key.theme).mutate();
    }

    private Drawable loadRaw(int id, int density, Theme theme) {
        if (density != 0) {
            return theme != null ? wrapped.getDrawableForDensity(id, density, theme)
                                 : wrapped.getDrawableForDensity(id, density);
        }
        return theme != null ? wrapped.getDrawable(id, theme) : wrapped.getDrawable(id);
    }

    private Drawable getSkinnedDrawable(int id, int density, Theme theme) {
        while (true) {
            final ReplacementState state = replacementState;
            final CacheKey key = new CacheKey(id, density, theme, state.generation,
                    wrapped.getConfiguration(), wrapped.getDisplayMetrics().densityDpi);
            final Drawable drawable = cachedGetOrLoad(
                    key, () -> skinned(id, density, theme, state.replacement));
            if (replacementState == state && key.configuration.equals(wrapped.getConfiguration())
                    && key.displayDensity == wrapped.getDisplayMetrics().densityDpi) {
                return drawable;
            }
        }
    }

    private Drawable skinned(int stockId, int density, Theme theme, BaseIconReplace replacement) {
        if (NON_SKINNABLE_DRAWABLES.contains(stockId)) {
            return loadRaw(stockId, density, theme);
        }
        final int wrappedId = replacement.wrap(stockId);
        if (wrappedId == stockId) return loadRaw(wrappedId, density, theme);
        final boolean noTint = replacement.isNoTint(stockId);
        boolean keepLiquidSendMaster = wrappedId == org.telegram.messenger.R.drawable.attach_send_liquid;
        Drawable twin = loadRaw(keepLiquidSendMaster ? org.telegram.messenger.R.drawable.ng_liquid_send_master : wrappedId,
                DisplayMetrics.DENSITY_XXXHIGH, theme);
        if (!(twin instanceof BitmapDrawable)) return twin;
        Bitmap bmp = ((BitmapDrawable) twin).getBitmap();
        if (bmp == null) return twin;
        if (noTint) twin = new NoTintBitmapDrawable(wrapped, bmp);

        final DisplayMetrics metrics = wrapped.getDisplayMetrics();
        final int displayDensity = metrics.densityDpi;
        final int defaultSize = (int) Math.ceil(24f * metrics.density);
        int w = defaultSize;
        int h = w;
        try {
            Drawable stock = loadRaw(stockId, density, theme);
            if (stock != null && stock.getIntrinsicWidth() > 0 && stock.getIntrinsicHeight() > 0) {
                w = stock.getIntrinsicWidth();
                h = stock.getIntrinsicHeight();
            }
        } catch (Throwable ignore) {  }
        if (RENDER_24DP_ICONS.contains(stockId)) {
            w = h = defaultSize;
        }

        if (w <= 0 || h <= 0) return twin;
        float scale = Math.min((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
        if (scale >= 1f || stockId == org.telegram.messenger.R.drawable.video_actions || keepLiquidSendMaster) {
            int targetWidth = Math.max(1, Math.round(bmp.getWidth() * scale));
            int targetHeight = Math.max(1, Math.round(bmp.getHeight() * scale));
            BitmapDrawable result = noTint ? new NoTintBitmapDrawable(wrapped, bmp, targetWidth, targetHeight)
                    : new SizedBitmapDrawable(wrapped, bmp, targetWidth, targetHeight);
            if (keepLiquidSendMaster) {
                result.setFilterBitmap(true);
            }
            return result;
        }
        int nw = Math.max(1, Math.round(bmp.getWidth()  * scale));
        int nh = Math.max(1, Math.round(bmp.getHeight() * scale));
        if (nw == bmp.getWidth() && nh == bmp.getHeight()) return twin;
        try {
            Bitmap scaled =
                    Bitmap.createScaledBitmap(bmp, nw, nh, true);
            scaled.setDensity(displayDensity);
            return noTint ? new NoTintBitmapDrawable(wrapped, scaled)
                          : new BitmapDrawable(wrapped, scaled);
        } catch (Throwable t) {
            return twin;
        }
    }

    private static class SizedBitmapDrawable extends BitmapDrawable {
        private final int intrinsicWidth;
        private final int intrinsicHeight;
        private int intrinsicDensity;
        private ColorStateList iconTint;
        private PorterDuff.Mode iconTintMode = PorterDuff.Mode.SRC_IN;

        SizedBitmapDrawable(Resources res, Bitmap bitmap) {
            this(res, bitmap, 0, 0);
        }

        SizedBitmapDrawable(Resources res, Bitmap bitmap, int width, int height) {
            super(res, bitmap);
            intrinsicWidth = width;
            intrinsicHeight = height;
            intrinsicDensity = res == null ? DisplayMetrics.DENSITY_DEFAULT : res.getDisplayMetrics().densityDpi;
            if (intrinsicDensity == 0) {
                intrinsicDensity = DisplayMetrics.DENSITY_DEFAULT;
            }
        }

        SizedBitmapDrawable copy(Resources res) {
            return copy(res, null);
        }

        SizedBitmapDrawable copy(Resources res, BooleanSupplier darkTheme) {
            int density = res == null ? intrinsicDensity : res.getDisplayMetrics().densityDpi;
            if (density == 0) {
                density = DisplayMetrics.DENSITY_DEFAULT;
            }
            int width = Math.max(1, Math.round(getIntrinsicWidth() * (float) density / intrinsicDensity));
            int height = Math.max(1, Math.round(getIntrinsicHeight() * (float) density / intrinsicDensity));
            SizedBitmapDrawable copy = this instanceof NoTintBitmapDrawable
                    ? new NoTintBitmapDrawable(res, getBitmap(), width, height)
                    : new SizedBitmapDrawable(res, getBitmap(), width, height);
            copy.intrinsicDensity = density;
            copy.setTargetDensity(density);
            copy.getPaint().set(getPaint());
            copy.setGravity(getGravity());
            copy.setTileModeXY(getTileModeX(), getTileModeY());
            copy.setAutoMirrored(isAutoMirrored());
            copy.setChangingConfigurations(getChangingConfigurations());
            copy.setTintMode(iconTintMode);
            copy.setTintList(iconTint);
            copy.setColorFilter(getColorFilter());
            return copy;
        }

        @Override public void setTintList(ColorStateList tint) {
            iconTint = tint;
            super.setTintList(tint);
        }

        @Override public void setTintMode(PorterDuff.Mode mode) {
            iconTintMode = mode;
            super.setTintMode(mode);
        }

        @Override public int getIntrinsicWidth() {
            return intrinsicWidth > 0 ? intrinsicWidth : super.getIntrinsicWidth();
        }

        @Override public int getIntrinsicHeight() {
            return intrinsicHeight > 0 ? intrinsicHeight : super.getIntrinsicHeight();
        }
    }

    private static final class NoTintBitmapDrawable extends SizedBitmapDrawable {
        NoTintBitmapDrawable(Resources res, Bitmap bmp) { this(res, bmp, 0, 0); }
        NoTintBitmapDrawable(Resources res, Bitmap bmp, int width, int height) {
            super(res, bmp, width, height);
        }


        @Override public void setColorFilter(ColorFilter colorFilter) {
            super.setColorFilter(null);
        }
        @Override public void setTintList(ColorStateList tint) {
            super.setTintList(null);
        }
        @Override public Drawable mutate() { return this; }
    }


    @Override
    public Configuration getConfiguration() {
        try {
            return wrapped.getConfiguration();
        } catch (Throwable ignored) {
            return super.getConfiguration();
        }
    }

    @Override
    public DisplayMetrics getDisplayMetrics() {
        return wrapped == null ? super.getDisplayMetrics() : wrapped.getDisplayMetrics();
    }
    @SuppressWarnings("deprecation")
    @Override
    public Drawable getDrawable(int id) throws NotFoundException {
        return getSkinnedDrawable(id, 0, null);
    }

    @Override
    public Drawable getDrawable(int id, Theme theme) throws NotFoundException {
        return getSkinnedDrawable(id, 0, theme);
    }

    @SuppressWarnings("deprecation")
    @Override
    public Drawable getDrawableForDensity(int id, int density) throws NotFoundException {
        return getSkinnedDrawable(id, density, null);
    }

    @Override
    public Drawable getDrawableForDensity(int id, int density, Theme theme) {
        return getSkinnedDrawable(id, density, theme);
    }
}
