package app.nimarkogram.messenger.infocards;

import android.content.Context;
import androidx.annotation.StringRes;
import org.telegram.messenger.LocaleController;

import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;

public final class InfoCardRegistry {

    public interface CardCreator {
        BaseInfoCard create(Context context, Theme.ResourcesProvider resourcesProvider);
    }

    public static final class CardInfo {
        public final int id;
        public final CharSequence name;
        @StringRes public final int nameRes;
        public final int iconRes;
        public final int colorTop;
        public final int colorBottom;
        public final CardCreator creator;

        public CardInfo(int id, CharSequence name, int iconRes, int colorTop, int colorBottom, CardCreator creator) {
            this.id = id;
            this.name = name;
            this.nameRes = 0;
            this.iconRes = iconRes;
            this.colorTop = colorTop;
            this.colorBottom = colorBottom;
            this.creator = creator;
        }

        public CardInfo(int id, @StringRes int nameRes, int iconRes, int colorTop, int colorBottom, CardCreator creator) {
            this.id = id;
            this.name = null;
            this.nameRes = nameRes;
            this.iconRes = iconRes;
            this.colorTop = colorTop;
            this.colorBottom = colorBottom;
            this.creator = creator;
        }

        public CharSequence getName() {
            return nameRes != 0 ? LocaleController.getString(nameRes) : name;
        }
    }

    private static final LinkedHashMap<Integer, CardInfo> registry = new LinkedHashMap<>();
    private static boolean defaultsRegistered;

    public static synchronized void register(CardInfo pill) {
        if (pill != null) registry.put(pill.id, pill);
    }

    public static synchronized CardInfo get(int id) {
        
        ensureRegistered();
        return registry.get(id);
    }

    public static synchronized boolean isRegistered(int id) {
        ensureRegistered();
        return registry.containsKey(id);
    }

    public static synchronized Collection<CardInfo> all() {
        ensureRegistered();
        return Collections.unmodifiableCollection(new ArrayList<>(registry.values()));
    }

    public static BaseInfoCard create(int id, Context context, Theme.ResourcesProvider rp) {
        ensureRegistered();
        CardInfo info;
        synchronized (InfoCardRegistry.class) {
            info = registry.get(id);
        }
        if (info == null || info.creator == null) return null;
        BaseInfoCard pill = info.creator.create(context, rp);
        if (pill != null) {
            pill.setCardColors(info.colorTop, info.colorBottom);
            pill.setCardAccessibilityLabel(info.getName());
        }
        return pill;
    }

    public static synchronized void ensureRegistered() {
        if (defaultsRegistered) return;
        defaultsRegistered = true;

        register(new CardInfo(InfoCardType.TON.id, org.telegram.messenger.R.string.NM_CARDS_NameGram,
                org.telegram.messenger.R.drawable.menu_gram_24, -14965523, -15431455,
                (ctx, rp) -> new CryptoCard(ctx, rp, InfoCardType.TON.id, "ton",
                        org.telegram.messenger.R.drawable.menu_gram_24)));

        register(new CardInfo(InfoCardType.BTC.id, org.telegram.messenger.R.string.NM_CARDS_NameBitcoin,
                org.telegram.messenger.R.drawable.pill_btc, -1071598, -1608430,
                (ctx, rp) -> new CryptoCard(ctx, rp, InfoCardType.BTC.id, "btc",
                        org.telegram.messenger.R.drawable.pill_btc)));

        register(new CardInfo(InfoCardType.USD.id, org.telegram.messenger.R.string.NM_CARDS_NameUsd,
                org.telegram.messenger.R.drawable.pill_usd, -14840995, -15172775,
                (ctx, rp) -> new CryptoCard(ctx, rp, InfoCardType.USD.id, null,
                        org.telegram.messenger.R.drawable.pill_usd)));

        register(new CardInfo(InfoCardType.WEATHER.id,
                org.telegram.messenger.R.string.NM_CARDS_NameWeather,
                org.telegram.messenger.R.drawable.pill_weather, -10893326, -12933400,
                (ctx, rp) -> new WeatherCard(ctx, rp,
                        org.telegram.messenger.R.drawable.pill_weather)));

        register(new CardInfo(InfoCardType.CACHE.id,
                org.telegram.messenger.R.string.NM_CARDS_NameStorage,
                org.telegram.messenger.R.drawable.pill_cache, -11565578, -13276952,
                (ctx, rp) -> new CacheCard(ctx, rp,
                        org.telegram.messenger.R.drawable.pill_cache)));

        register(new CardInfo(InfoCardType.PROXY.id,
                org.telegram.messenger.R.string.NM_CARDS_NameProxy,
                org.telegram.messenger.R.drawable.pill_proxy, -11154873, -14175180,
                (ctx, rp) -> new ProxyCard(ctx, rp,
                        org.telegram.messenger.R.drawable.pill_proxy)));
    }

    private InfoCardRegistry() {}
}
