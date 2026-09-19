/**
 * NimarkoMedia native settings screen. Mirrors the Python plugin's
 * create_settings: auto-download switch, YouTube default-format selector, ask
 * YouTube format every time. Plus a test-download tile and the supported
 * platforms info card.
 *
 * Reached from MainPreferencesActivity → "Разное" / "Misc" → NimarkoMedia.
 */
package app.nimarkogram.messenger.preferences;

import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;

public class NimarkoMediaPreferencesActivity extends BasePreferencesActivity {

    private static final int ID_AUTO_DOWNLOAD = 100;
    private static final int ID_YT_ASK        = 101;
    private static final int ID_YT_FMT_VIDEO  = 102;
    private static final int ID_YT_FMT_AUDIO  = 103;
    private static final int ID_HEADER_DOWNLOADS = 110;
    private static final int ID_SHADOW_AUTO      = 111;
    private static final int ID_HEADER_FORMAT    = 112;
    private static final int ID_SHADOW_PLATFORMS = 113;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_DownloadMedia);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String supportedPlatforms = LocaleController.getString(R.string.NM_NM_SupportedPlatforms)
                + "\n" + LocaleController.getString(R.string.NM_NM_PlatformsList);
        items.add(UItem.asHeader(ID_HEADER_DOWNLOADS,
                LocaleController.getString(R.string.NM_SettingsSectionDownloads)));
        items.add(UItem.asCheck(ID_AUTO_DOWNLOAD,
                LocaleController.getString(R.string.NM_NM_AutoDownload))
                .setChecked(NimarkoConfig.nimarkoMediaAuto));
        items.add(UItem.asShadow(ID_SHADOW_AUTO,
                LocaleController.getString(R.string.NM_NM_AutoDownload_Desc)));

        if (NimarkoConfig.nimarkoMediaAuto) {
            items.add(UItem.asHeader(ID_HEADER_FORMAT,
                    LocaleController.getString(R.string.NM_NM_YtFormat)));
            items.add(UItem.asCheck(ID_YT_ASK,
                    LocaleController.getString(R.string.NM_NM_YtAsk))
                    .setChecked(NimarkoConfig.nimarkoMediaYtAsk));
            if (!NimarkoConfig.nimarkoMediaYtAsk) {
                items.add(UItem.asRadio(ID_YT_FMT_VIDEO,
                        LocaleController.getString(R.string.NM_NM_FormatVideo))
                        .setChecked(NimarkoConfig.nimarkoMediaYtFmt == 0));
                items.add(UItem.asRadio(ID_YT_FMT_AUDIO,
                        LocaleController.getString(R.string.NM_NM_FormatAudio))
                        .setChecked(NimarkoConfig.nimarkoMediaYtFmt == 1));
            }
        }
        items.add(UItem.asShadow(ID_SHADOW_PLATFORMS, supportedPlatforms));
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        if (uItem == null) return;
        switch (uItem.id) {
            case ID_AUTO_DOWNLOAD:
                NimarkoConfig.toggleNimarkoMediaAuto();
                uItem.checked = NimarkoConfig.nimarkoMediaAuto;
                updateCheckState(view, NimarkoConfig.nimarkoMediaAuto);
                reloadMainInfo();
                break;
            case ID_YT_ASK:
                NimarkoConfig.toggleNimarkoMediaYtAsk();
                uItem.checked = NimarkoConfig.nimarkoMediaYtAsk;
                updateCheckState(view, NimarkoConfig.nimarkoMediaYtAsk);
                reloadMainInfo();
                break;
            case ID_YT_FMT_VIDEO:
                NimarkoConfig.setNimarkoMediaYtFmt(0);
                uItem.checked = true;
                updateCheckState(view, true);
                reloadMainInfo();
                break;
            case ID_YT_FMT_AUDIO:
                NimarkoConfig.setNimarkoMediaYtFmt(1);
                uItem.checked = true;
                updateCheckState(view, true);
                reloadMainInfo();
                break;
        }
    }

    /** Refreshes the recycler so switches/radios show the new state. */
    private void reloadMainInfo() {
        updateItemsAfterToggle();
    }
}
