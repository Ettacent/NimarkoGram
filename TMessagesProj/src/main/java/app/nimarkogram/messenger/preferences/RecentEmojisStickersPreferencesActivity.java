package app.nimarkogram.messenger.preferences;

import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;

public class RecentEmojisStickersPreferencesActivity extends BasePreferencesActivity {
    private static final int ID_EMOJI_SLIDER = 1;
    private static final int ID_STICKERS_SLIDER = 2;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_CH_RecentEmojisStickers);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // 1. Emoji
        items.add(UItem.asHeader(LocaleController.getString(R.string.Emoji)));
        items.add(
                UItem.asIntSlideView(
                        ID_EMOJI_SLIDER,
                        25,
                        NimarkoConfig.recentEmojisAmplifier,
                        80,
                        String::valueOf,
                        NimarkoConfig::setRecentEmojisAmplifier
                )
        );
        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_SettingsDesc_RecentEmoji)));

        // 2. Stickers
        items.add(UItem.asHeader(LocaleController.getString(R.string.AccDescrStickers)));
        items.add(
                UItem.asIntSlideView(
                        ID_STICKERS_SLIDER,
                        10,
                        NimarkoConfig.recentStickersAmplifier,
                        50,
                        String::valueOf,
                        NimarkoConfig::setRecentStickersAmplifier
                )
        );
        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_SettingsDesc_RecentStickers)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        // Sliders apply via their callbacks; no row clicks to handle.
    }

    @Override
    public void onPause() {
        super.onPause();
        notifyReload();
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        notifyReload();
        super.onFragmentDestroy();
    }

    private void notifyReload() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.emojiLoaded);
        getNotificationCenter().postNotificationName(
                NotificationCenter.recentDocumentsDidLoad, false, MediaDataController.TYPE_IMAGE);
    }
}
