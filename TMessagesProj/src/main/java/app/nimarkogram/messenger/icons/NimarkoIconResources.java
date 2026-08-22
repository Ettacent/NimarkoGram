/*
 * Ported from Cherrygram (uz.unnarsx.cherrygram.core.icons.CGUIResources).
 * Originally Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 * Ported to Java for NimarkoGram.
 */

package app.nimarkogram.messenger.icons;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.icons.icon_replaces.BaseIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.NoIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.SolarIconReplace;
import app.nimarkogram.messenger.icons.icon_replaces.LiquidGlassFullReplace;
import app.nimarkogram.messenger.icons.icon_replaces.PlumpyFullReplace;

@SuppressLint("UseCompatLoadingForDrawables")
public class NimarkoIconResources extends Resources {

    private static final int CACHE_LIMIT = 300;

    private static final java.util.Set<Integer> RENDER_24DP_ICONS = new java.util.HashSet<>(
            java.util.Arrays.asList(org.telegram.messenger.R.drawable.group_edit_profile));

    private static final java.util.Set<Integer> NON_SKINNABLE_DRAWABLES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    org.telegram.messenger.R.drawable.msg_autodelete_badge2,
                    org.telegram.messenger.R.drawable.msg_autodelete_badge2_solar,
                    org.telegram.messenger.R.drawable.msg_mini_autodelete_empty,
                    org.telegram.messenger.R.drawable.msg_mini_autodelete_empty_solar
            ));

    private static final java.util.Set<Integer> STOCK_COMPOSER_DRAWABLES =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    org.telegram.messenger.R.drawable.attach_send,
                    org.telegram.messenger.R.drawable.input_attach,
                    org.telegram.messenger.R.drawable.input_bot1,
                    org.telegram.messenger.R.drawable.input_bot2,
                    org.telegram.messenger.R.drawable.input_calendar1,
                    org.telegram.messenger.R.drawable.input_calendar2,
                    org.telegram.messenger.R.drawable.input_clear,
                    org.telegram.messenger.R.drawable.input_done,
                    org.telegram.messenger.R.drawable.input_forward,
                    org.telegram.messenger.R.drawable.input_gift_s,
                    org.telegram.messenger.R.drawable.input_keyboard,
                    org.telegram.messenger.R.drawable.input_message,
                    org.telegram.messenger.R.drawable.input_mic,
                    org.telegram.messenger.R.drawable.input_mic_pressed,
                    org.telegram.messenger.R.drawable.input_notify_off,
                    org.telegram.messenger.R.drawable.input_notify_on,
                    org.telegram.messenger.R.drawable.input_reply,
                    org.telegram.messenger.R.drawable.input_schedule,
                    org.telegram.messenger.R.drawable.input_smile,
                    org.telegram.messenger.R.drawable.input_suggest_paid_24,
                    org.telegram.messenger.R.drawable.input_video,
                    org.telegram.messenger.R.drawable.input_video_pressed,
                    org.telegram.messenger.R.drawable.input_video_story,
                    org.telegram.messenger.R.drawable.input_video_story_remove,
                    org.telegram.messenger.R.drawable.msg_input_attach2,
                    org.telegram.messenger.R.drawable.msg_input_gift,
                    org.telegram.messenger.R.drawable.msg_input_like,
                    org.telegram.messenger.R.drawable.send_plane_24
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

    private android.graphics.Bitmap backArrowBitmap;
    private boolean backArrowBitmapResolved;
    private final Object backArrowLock = new Object();

    @SuppressWarnings("deprecation")
    public NimarkoIconResources(Resources wrapped) {
        super(wrapped.getAssets(), wrapped.getDisplayMetrics(), wrapped.getConfiguration());
        this.wrapped = wrapped;
        applySelection(NimarkoConfig.iconReplacement, 1L);
        prepareBackArrowBitmap();
        sInstalled = this;
        prewarmFolderIconsAsync();
    }

    public static android.graphics.Bitmap activeBackArrowBitmap() {
        NimarkoIconResources self = sInstalled;
        return self == null ? null : self.preparedBackArrowBitmap();
    }

    public static Drawable getStockDrawable(android.content.Context context, int id) {
        final Resources resources = context.getResources();
        final Theme theme = context.getTheme();
        if (resources instanceof NimarkoIconResources) {
            return ((NimarkoIconResources) resources).loadRaw(id, 0, theme);
        }
        return resources.getDrawable(id, theme);
    }

    private android.graphics.Bitmap preparedBackArrowBitmap() {
        synchronized (backArrowLock) {
            return backArrowBitmapResolved ? backArrowBitmap : null;
        }
    }

    private android.graphics.Bitmap prepareBackArrowBitmap() {
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

            final android.graphics.Bitmap loaded = loadBackArrowBitmap(state);
            synchronized (backArrowLock) {
                if (replacementState != state) {
                    if (loaded != null) {
                        loaded.recycle();
                    }
                    continue;
                }
                if (!backArrowBitmapResolved) {
                    backArrowBitmap = loaded;
                    backArrowBitmapResolved = true;
                    return loaded;
                }
                if (loaded != null) {
                    loaded.recycle();
                }
                return backArrowBitmap;
            }
        }
    }

    private android.graphics.Bitmap loadBackArrowBitmap(ReplacementState state) {
        try {
            final int stockId = org.telegram.messenger.R.drawable.ic_ab_back;
            final int wrappedId = state.replacement.wrap(stockId);
            if (wrappedId == stockId) {
                return null;
            }
            Drawable d = wrapped.getDrawable(wrappedId);
            if (d == null) {
                return null;
            }
            d = d.mutate();
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = h = org.telegram.messenger.AndroidUtilities.dp(24);
            }
            android.graphics.Bitmap bmp =
                    android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            return bmp;
        } catch (Throwable ignore) {
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
        if (onApplied != null) {
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> {
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
            replacementState = new ReplacementState(replacement, selection, generation);
        }
    }

    public void prewarmFolderIconsAsync() {
        final ReplacementState state = replacementState;
        if (warmedFolderSelection == state.selection) {
            return;
        }
        try {
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
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
            for (Integer drawableId : new java.util.HashSet<>(
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

        CacheKey(int id, int density, Theme theme, long generation) {
            this.id = id;
            this.density = density;
            this.theme = theme;
            this.generation = generation;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey k = (CacheKey) o;
            return id == k.id && density == k.density && generation == k.generation
                    && Objects.equals(theme, k.theme);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, density, theme, generation);
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
        if (loaded instanceof NoTintBitmapDrawable) {
            android.graphics.Bitmap bmp = ((NoTintBitmapDrawable) loaded).getBitmap();
            if (bmp != null) return new NoTintBitmapDrawable(wrapped, bmp);
            return loaded;
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
            final CacheKey key = new CacheKey(id, density, theme, state.generation);
            final Drawable drawable = cachedGetOrLoad(
                    key, () -> skinned(id, density, theme, state.replacement));
            if (replacementState == state) {
                return drawable;
            }
        }
    }

    private Drawable skinned(int stockId, int density, Theme theme, BaseIconReplace replacement) {
        if (NON_SKINNABLE_DRAWABLES.contains(stockId)
                || STOCK_COMPOSER_DRAWABLES.contains(stockId)) {
            return loadRaw(stockId, density, theme);
        }
        final int wrappedId = replacement.wrap(stockId);
        if (wrappedId == stockId) return loadRaw(wrappedId, density, theme);
        final boolean noTint = replacement.isNoTint(stockId);
        Drawable twin = loadRaw(wrappedId, android.util.DisplayMetrics.DENSITY_XXXHIGH, theme);
        if (!(twin instanceof android.graphics.drawable.BitmapDrawable)) return twin;
        android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) twin).getBitmap();
        if (bmp == null) return twin;
        if (noTint) twin = new NoTintBitmapDrawable(wrapped, bmp);

        int w = org.telegram.messenger.AndroidUtilities.dp(24f);
        int h = w;
        try {
            Drawable stock = loadRaw(stockId, density, theme);
            if (stock != null && stock.getIntrinsicWidth() > 0 && stock.getIntrinsicHeight() > 0) {
                w = stock.getIntrinsicWidth();
                h = stock.getIntrinsicHeight();
            }
        } catch (Throwable ignore) {  }
        if (RENDER_24DP_ICONS.contains(stockId)) {
            w = h = org.telegram.messenger.AndroidUtilities.dp(24f);
        }

        if (w <= 0 || h <= 0) return twin;
        float scale = Math.min((float) w / bmp.getWidth(), (float) h / bmp.getHeight());
        if (scale >= 1f) return twin;
        int nw = Math.max(1, Math.round(bmp.getWidth()  * scale));
        int nh = Math.max(1, Math.round(bmp.getHeight() * scale));
        if (nw == bmp.getWidth() && nh == bmp.getHeight()) return twin;
        try {
            android.graphics.Bitmap scaled =
                    android.graphics.Bitmap.createScaledBitmap(bmp, nw, nh, true);
            scaled.setDensity(org.telegram.messenger.AndroidUtilities.displayMetrics.densityDpi);
            return noTint ? new NoTintBitmapDrawable(wrapped, scaled)
                          : new android.graphics.drawable.BitmapDrawable(wrapped, scaled);
        } catch (Throwable t) {
            return twin;
        }
    }

    private static final class NoTintBitmapDrawable extends android.graphics.drawable.BitmapDrawable {
        NoTintBitmapDrawable(Resources res, android.graphics.Bitmap bmp) { super(res, bmp); }
        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            super.setColorFilter(org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? null : colorFilter);
        }
        @Override public void setTintList(android.content.res.ColorStateList tint) {
            super.setTintList(org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? null : tint);
        }
        @Override public Drawable mutate() { return this; }
    }


    @Override
    public android.content.res.Configuration getConfiguration() {
        try {
            return wrapped.getConfiguration();
        } catch (Throwable ignored) {
            return super.getConfiguration();
        }
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
