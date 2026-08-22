package app.nimarkogram.messenger.infocards.preferences;

import android.util.SparseArray;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.preferences.BasePreferencesActivity;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.infocards.CacheCard;
import app.nimarkogram.messenger.infocards.CryptoCard;
import app.nimarkogram.messenger.infocards.InfoCardRates;
import app.nimarkogram.messenger.infocards.InfoCardRegistry;
import app.nimarkogram.messenger.infocards.InfoCardType;
import app.nimarkogram.messenger.infocards.InfoCardsConfig;
import app.nimarkogram.messenger.infocards.ProxyCard;
import app.nimarkogram.messenger.infocards.WeatherCard;

public class InfoCardsPreferencesActivity extends BasePreferencesActivity implements InfoCardRowView.Listener {

    private static final int ID_ENABLED            = 100;
    private static final int ID_INFINITE_SCROLL    = 101;
    private static final int ID_AUTO_SCROLL        = 102;

    private static final int ID_CARD_BASE          = 1000;

    private int activeReorderSectionId = -1;

    private static final String[] CURRENCIES = {
            "AUTO", "USD", "EUR", "RUB", "GBP", "UAH", "KZT", "PLN", "TRY", "CNY", "JPY", "BYN", "INR"
    };

    private final SparseArray<InfoCardRowView> rows = new SparseArray<>();
    private InfoCardRates.CallbackHandle ratesCallback;
    private CacheCard.SizeCallbackHandle cacheCallback;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_CARDS_Title);
    }

    @Override
    public View createView(android.content.Context context) {
        View view = super.createView(context);
        listView.listenReorder(this::onReordered);
        listView.allowReorder(true);
        if (ratesCallback != null) ratesCallback.cancel();
        ratesCallback = InfoCardRates.fetchWeak(false, () -> {
            ratesCallback = null;
            if (!isFinished) reload();
        });
        if (cacheCallback != null) cacheCallback.cancel();
        cacheCallback = CacheCard.computeAsyncWeak(() -> {
            cacheCallback = null;
            if (!isFinished) reload();
        });
        return view;
    }

    @Override
    public void onFragmentDestroy() {
        if (ratesCallback != null) {
            ratesCallback.cancel();
            ratesCallback = null;
        }
        if (cacheCallback != null) {
            cacheCallback.cancel();
            cacheCallback = null;
        }
        rows.clear();
        super.onFragmentDestroy();
    }

    private InfoCardRowView getRow(InfoCardRegistry.CardInfo info, boolean active) {
        InfoCardRowView row = rows.get(info.id);
        if (row == null) {
            row = new InfoCardRowView(getParentActivity(), getResourceProvider());
            row.setListener(this);
            rows.put(info.id, row);
        }
        row.bind(info, active, valueFor(info.id), active, active && hasOptions(info.id));
        return row;
    }

    private static boolean hasOptions(int id) {
        return id == InfoCardType.TON.id || id == InfoCardType.BTC.id || id == InfoCardType.USD.id;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final boolean enabled = InfoCardsConfig.isEnabled();

        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_CARDS_GeneralHeader)));
        items.add(UItem.asCheck(ID_ENABLED, LocaleController.getString(R.string.NM_CARDS_Enable))
                .setChecked(enabled));

        if (enabled) {
            items.add(UItem.asCheck(ID_INFINITE_SCROLL,
                            LocaleController.getString(R.string.NM_CARDS_InfiniteScroll))
                    .setChecked(InfoCardsConfig.isInfiniteScrolling()));

            items.add(UItem.asCheck(ID_AUTO_SCROLL,
                            LocaleController.getString(R.string.NM_CARDS_AutoScroll))
                    .setChecked(InfoCardsConfig.isAutoScroll()));

            final List<Integer> active = InfoCardsConfig.getActiveCards();
            final List<Integer> hidden = InfoCardsConfig.getHiddenCards();

            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(LocaleController.getString(R.string.NM_CARDS_ActiveHeader)));

            activeReorderSectionId = adapter.reorderSectionStart();
            for (int pillId : active) {
                InfoCardRegistry.CardInfo info = InfoCardRegistry.get(pillId);
                if (info == null) continue;
                items.add(UItem.asCustom(ID_CARD_BASE + pillId, getRow(info, true)));
            }
            adapter.reorderSectionEnd();

            if (!hidden.isEmpty()) {
                items.add(UItem.asShadow(null));
                items.add(UItem.asHeader(LocaleController.getString(R.string.NM_CARDS_HiddenHeader)));
                for (int pillId : hidden) {
                    InfoCardRegistry.CardInfo info = InfoCardRegistry.get(pillId);
                    if (info == null) continue;
                    items.add(UItem.asCustom(ID_CARD_BASE + pillId, getRow(info, false)));
                }
            }
        }

        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_CARDS_Footer)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null) return;
        final int id = item.id;

        if (id == ID_ENABLED) {
            InfoCardsConfig.setEnabled(!InfoCardsConfig.isEnabled());
            updateCheckState(view, InfoCardsConfig.isEnabled());
            reload();
        } else if (id == ID_INFINITE_SCROLL) {
            boolean v = !InfoCardsConfig.isInfiniteScrolling();
            InfoCardsConfig.setInfiniteScrolling(v);
            updateCheckState(view, v);
        } else if (id == ID_AUTO_SCROLL) {
            boolean v = !InfoCardsConfig.isAutoScroll();
            InfoCardsConfig.setAutoScroll(v);
            updateCheckState(view, v);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.infoCardsSettingsChanged);
        }
    }


    @Override
    public void onToggle(int pillId, boolean active) {
        if (active) {
            moveToActive(pillId);
        } else {
            moveToHidden(pillId);
        }
        reload();
    }

    @Override
    public void onBodyTap(int pillId) {
        if (!InfoCardsConfig.isCardActive(pillId) || !hasOptions(pillId)) return;
        showCurrencyPicker(pillId);
    }


    private void showCurrencyPicker(int pillId) {
        ArrayList<String> labels = new ArrayList<>(Arrays.asList(CURRENCIES));
        labels.set(0, LocaleController.getString(R.string.Default));
        int selected = Math.max(0, Arrays.asList(CURRENCIES).indexOf(InfoCardsConfig.getTargetCurrency(pillId)));
        PopupHelper.show(labels, LocaleController.getString(R.string.NM_CARDS_Title), selected, getParentActivity(), i -> {
            InfoCardsConfig.setTargetCurrency(pillId, CURRENCIES[i]);
            reload();
        });
    }


    private void moveToHidden(int pillId) {
        List<Integer> active = InfoCardsConfig.getActiveCards();
        List<Integer> hidden = InfoCardsConfig.getHiddenCards();
        if (active.size() <= 1) return;
        if (!active.remove((Integer) pillId)) return;
        if (!hidden.contains(pillId)) hidden.add(pillId);
        InfoCardsConfig.setLayout(active, hidden);
    }

    private void moveToActive(int pillId) {
        List<Integer> active = InfoCardsConfig.getActiveCards();
        List<Integer> hidden = InfoCardsConfig.getHiddenCards();
        if (!hidden.remove((Integer) pillId)) return;
        if (!active.contains(pillId)) active.add(pillId);
        InfoCardsConfig.setLayout(active, hidden);
    }

    private void onReordered(int sectionId, ArrayList<UItem> reordered) {
        if (sectionId != activeReorderSectionId) return;
        ArrayList<Integer> newActive = new ArrayList<>();
        for (UItem it : reordered) {
            if (it.id >= ID_CARD_BASE && it.id < ID_CARD_BASE + 1000) {
                newActive.add(it.id - ID_CARD_BASE);
            }
        }
        if (newActive.isEmpty()) return;
        InfoCardsConfig.setLayout(newActive, InfoCardsConfig.getHiddenCards());
    }

    private void reload() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private static CharSequence valueFor(int id) {
        CharSequence v;
        if (id == InfoCardType.TON.id || id == InfoCardType.BTC.id || id == InfoCardType.USD.id) {
            v = CryptoCard.liveValueText(id);
        } else if (id == InfoCardType.PROXY.id) {
            v = ProxyCard.liveValueText();
        } else if (id == InfoCardType.CACHE.id) {
            v = CacheCard.liveValueText();
        } else if (id == InfoCardType.WEATHER.id) {
            v = WeatherCard.liveValueText();
        } else {
            v = null;
        }
        return v == null ? "" : v;
    }
}
