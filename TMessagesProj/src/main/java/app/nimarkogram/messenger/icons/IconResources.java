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
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import java.util.function.BooleanSupplier;
import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.icons.icon_replaces.BaseIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.NoIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.SolarIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.LiquidGlassFullReplace;
import app.nimarkogram.messenger.icons.icon_replaces.PlumpyFullReplace;

@SuppressLint("UseCompatLoadingForDrawables")
public class IconResources extends Resources {

    private static final int CACHE_LIMIT = 300;

    private static final Handler ICON_UI_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService ICON_PREPARATION_QUEUE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ng-icon-prepare");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
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
                    org.telegram.messenger.R.drawable.msg_other_new_outline
            ));

    private static final class ReplacementState {
        final BaseIconReplace replacement;
        final int selection;
        final long generation;

        final Configuration configuration;
        final int densityDpi;
        final float density;

        ReplacementState(BaseIconReplace replacement, int selection, long generation, Resources resources) {
            this.replacement = replacement;
            this.selection = selection;
            this.generation = generation;
            configuration = new Configuration(resources.getConfiguration());
            DisplayMetrics metrics = resources.getDisplayMetrics();
            densityDpi = metrics.densityDpi;
            density = metrics.density;
        }
    }

    private final Resources wrapped;
    private final Object replacementLock = new Object();
    private volatile ReplacementState replacementState;
    private final Object folderWarmLock = new Object();
    private volatile ReplacementState warmedFolderState;
    private ReplacementState queuedFolderState;
    private static volatile WeakReference<IconResources> sInstalled = new WeakReference<>(null);

    private Bitmap backArrowBitmap;
    private boolean backArrowBitmapResolved;
    private final Object backArrowLock = new Object();

    private NavigationIcons navigationIcons;

    private boolean iconPreparationQueued;
    private final ArrayList<Runnable> appliedCallbacks = new ArrayList<>();
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
        IconResources self = sInstalled.get();
        if (self == null) return null;
        ReplacementState state = self.refreshConfiguration();
        synchronized (self.backArrowLock) {
            return self.replacementState == state && self.matchesConfiguration(state) ? self.navigationIcons : null;
        }
    }

    public static PreparedIcon activeAnimatedIcon(int stockId) {
        NavigationIcons icons = activeNavigationIcons();
        return icons == null ? null : icons.getIcon(stockId);
    }
    @SuppressWarnings("deprecation")
    public IconResources(Resources wrapped) {
        this(wrapped, true);
    }

    @SuppressWarnings("deprecation")
    public IconResources(Resources wrapped, boolean activate) {
        super(wrapped.getAssets(), wrapped.getDisplayMetrics(), wrapped.getConfiguration());
        this.wrapped = wrapped;
        applySelection(AppConfig.iconReplacement, 1L);
        if (activate) activate();
    }

    public void activate() {
        publishAsInstalled();
        prepareAsync();
    }

    public final void publishAsInstalled() {
        sInstalled = new WeakReference<>(this);
    }

    public final void prepareAsync() {
        prepareIconsAsync();
        prewarmFolderIconsAsync();
    }

    public static Bitmap activeBackArrowBitmap() {
        IconResources self = sInstalled.get();
        return self == null ? null : self.preparedBackArrowBitmap();
    }

    public static Drawable getStockDrawable(Context context, int id) {
        final Resources resources = context.getResources();
        final Theme theme = context.getTheme();
        if (resources instanceof IconResources) {
            return ((IconResources) resources).loadRaw(id, 0, theme);
        }
        return resources.getDrawable(id, theme);
    }

    public Resources getOriginalResources() {
        return wrapped;
    }

    private Bitmap preparedBackArrowBitmap() {
        ReplacementState state = refreshConfiguration();
        synchronized (backArrowLock) {
            return replacementState == state && matchesConfiguration(state) && backArrowBitmapResolved ? backArrowBitmap : null;
        }
    }

    private boolean matchesConfiguration(ReplacementState state) {
        DisplayMetrics metrics = wrapped.getDisplayMetrics();
        return state.densityDpi == metrics.densityDpi && state.density == metrics.density
                && state.configuration.equals(wrapped.getConfiguration());
    }

    private ReplacementState refreshConfiguration() {
        ReplacementState state = replacementState;
        if (!matchesConfiguration(state)) {
            synchronized (replacementLock) {
                state = replacementState;
                if (!matchesConfiguration(state)) {
                    publishReplacementState(new ReplacementState(state.replacement,
                            state.selection, state.generation + 1L, wrapped));
                    clearCache();
                }
                state = replacementState;
            }
            prepareIconsAsync();
            prewarmFolderIconsAsync();
        }
        return state;
    }

    private void prepareIconsAsync() {
        synchronized (backArrowLock) {
            if (iconPreparationQueued || backArrowBitmapResolved) return;
            iconPreparationQueued = true;
        }
        WeakReference<IconResources> reference = new WeakReference<>(this);
        try {
            ICON_PREPARATION_QUEUE.execute(() -> {
                IconResources resources = reference.get();
                if (resources != null) resources.prepareIconsInBackground();
            });
        } catch (Throwable ignored) {
            synchronized (backArrowLock) {
                iconPreparationQueued = false;
            }
        }
    }

    private void prepareIconsInBackground() {
        ReplacementState state = null;
        NavigationIcons loaded = null;
        boolean published = false;
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) return;
            state = refreshConfiguration();
            loaded = loadNavigationIcons(state);
            synchronized (backArrowLock) {
                if (loaded != null && replacementState == state && matchesConfiguration(state)) {
                    navigationIcons = loaded;
                    backArrowBitmap = loaded.back;
                    backArrowBitmapResolved = true;
                    published = true;
                }
            }
            if (published) postIconsReady(state);
        } catch (Throwable ignored) {
        } finally {
            if (loaded != null && !published) recycleUnpublishedIcons(loaded);
            final boolean superseded;
            synchronized (backArrowLock) {
                iconPreparationQueued = false;
                superseded = state != null
                        && (replacementState != state || !matchesConfiguration(state));
            }
            if (superseded) {
                refreshConfiguration();
                prepareIconsAsync();
            }
        }
    }

    private void postIconsReady(ReplacementState state) {
        ICON_UI_HANDLER.post(() -> {
            final ArrayList<Runnable> callbacks;
            synchronized (backArrowLock) {
                if (replacementState != state || !matchesConfiguration(state) || !backArrowBitmapResolved) {
                    return;
                }
                callbacks = new ArrayList<>(appliedCallbacks);
                appliedCallbacks.clear();
            }
            for (Runnable callback : callbacks) {
                if (isReplacementGenerationCurrent(state.generation)) callback.run();
            }
            if (sInstalled.get() == this && replacementState == state && matchesConfiguration(state)) {
                List<View> roots = AndroidUtilities.allGlobalViews();
                if (roots != null) {
                    for (View root : roots) invalidateIconViews(root);
                } else if (org.telegram.ui.LaunchActivity.instance != null) {
                    invalidateIconViews(org.telegram.ui.LaunchActivity.instance.getWindow().peekDecorView());
                }
            }
        });
    }

    private static void invalidateIconViews(View view) {
        if (view == null) return;
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateIconViews(group.getChildAt(i));
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
        int[][] groups = {ids, AnimatedIconCatalog.drawableIds(), AnimatedCallIcons.drawableIds()};
        Set<Integer> visited = new HashSet<>();
        Bitmap pending = null;
        boolean complete = false;
        try {
            for (int[] group : groups) {
                for (int id : group) {
                    if (replacementState != state || !matchesConfiguration(state)) return null;
                    if (!visited.add(id)) continue;
                    pending = loadAnimatedIconBitmap(state, id);
                    if (pending != null) {
                        icons.put(id, new PreparedIcon(pending, state.replacement.isNoTint(id)));
                        pending = null;
                    }
                }
            }
            NavigationIcons result = new NavigationIcons(state, state.densityDpi, icons);
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
            Drawable d = wrapped.getDrawableForDensity(wrappedId, state.densityDpi);
            if (d == null) {
                return null;
            }
            d = d.mutate();
            int size = Math.max(1, Math.min(256, (int) Math.ceil(24f * state.density)));
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
        final ReplacementState applied;
        synchronized (replacementLock) {
            final int selection = AppConfig.iconReplacement;
            ReplacementState state = replacementState;
            if (state == null || state.selection != selection) {
                applySelection(selection, state == null ? 1L : state.generation + 1L);
                clearCache();
            } else if (!matchesConfiguration(state)) {
                publishReplacementState(new ReplacementState(state.replacement,
                        selection, state.generation + 1L, wrapped));
                clearCache();
            }
            applied = replacementState;
        }
        if (onApplied != null) {
            synchronized (backArrowLock) {
                if (replacementState == applied) {
                    appliedCallbacks.add(onApplied);
                }
            }
        }
        activate();
        postIconsReady(applied);
        return applied.generation;
    }

    public boolean isReplacementGenerationCurrent(long generation) {
        ReplacementState state = refreshConfiguration();
        return state != null
                && state.generation == generation
                && state.selection == AppConfig.iconReplacement;
    }

    public boolean hasReplacement(int id) {
        return !NON_SKINNABLE_DRAWABLES.contains(id) && refreshConfiguration().replacement.wrap(id) != id;
    }

    public long getReplacementGeneration() {
        return refreshConfiguration().generation;
    }

    public int getAppliedSelection() {
        return refreshConfiguration().selection;
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
        final BaseIconReplace replacement;
        switch (selection) {
            case AppConfig.ICON_REPLACE_SOLAR:
                replacement = new SolarIconReplace();
                break;
            case AppConfig.ICON_REPLACE_LIQUID_GLASS:
                replacement = new LiquidGlassFullReplace();
                break;
            case AppConfig.ICON_REPLACE_PLUMPY:
                replacement = new PlumpyFullReplace();
                break;
            default:
                replacement = new NoIconReplace();
                break;
        }
        publishReplacementState(new ReplacementState(replacement, selection, generation, wrapped));
    }

    private void publishReplacementState(ReplacementState state) {
        synchronized (backArrowLock) {
            warmedFolderState = null;
            backArrowBitmap = null;
            backArrowBitmapResolved = false;
            navigationIcons = null;
            appliedCallbacks.clear();
            replacementState = state;
        }
    }

    public void prewarmFolderIconsAsync() {
        final ReplacementState state = replacementState;
        synchronized (backArrowLock) {
            if (warmedFolderState == state || queuedFolderState == state) return;
            queuedFolderState = state;
        }
        WeakReference<IconResources> reference = new WeakReference<>(this);
        try {
            Utilities.globalQueue.postRunnable(() -> {
                IconResources resources = reference.get();
                if (resources == null) return;
                try {
                    resources.prewarmFolderIcons(state);
                } finally {
                    synchronized (resources.backArrowLock) {
                        if (resources.queuedFolderState == state) resources.queuedFolderState = null;
                    }
                }
            });
        } catch (Throwable ignored) {
            synchronized (backArrowLock) {
                if (queuedFolderState == state) queuedFolderState = null;
            }
        }
    }

    public void prewarmFolderIconsBlocking() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prewarmFolderIconsAsync();
            return;
        }
        prewarmFolderIcons(refreshConfiguration());
    }

    private void prewarmFolderIcons(ReplacementState state) {
        if (Looper.myLooper() == Looper.getMainLooper()
                || replacementState != state || !matchesConfiguration(state) || warmedFolderState == state) return;
        synchronized (folderWarmLock) {
            if (replacementState != state || !matchesConfiguration(state) || warmedFolderState == state) return;
            boolean complete = true;
            for (Integer drawableId : new HashSet<>(
                    app.nimarkogram.messenger.preferences.folders.helpers
                            .FolderIconHelper.folderIcons.values())) {
                if (drawableId == null) {
                    continue;
                }
                if (replacementState != state || !matchesConfiguration(state)) return;
                try {
                    getDrawable(drawableId);
                } catch (Throwable ignored) {
                    complete = false;
                }
            }
            if (complete && replacementState == state && matchesConfiguration(state)) {
                warmedFolderState = state;
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
            final ReplacementState state = refreshConfiguration();
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
