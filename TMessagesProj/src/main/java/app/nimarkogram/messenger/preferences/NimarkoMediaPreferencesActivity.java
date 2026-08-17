 
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

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_DownloadMedia);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_NM_HeaderSettings)));
        items.add(UItem.asCheck(ID_AUTO_DOWNLOAD,
                LocaleController.getString(R.string.NM_NM_AutoDownload))
                .setChecked(NimarkoConfig.nimarkoMediaAuto));
        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_NM_AutoDownload_Desc)));

        if (NimarkoConfig.nimarkoMediaAuto) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.NM_NM_YtFormat)));
            
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
            items.add(UItem.asShadow(null));
        }

        if (!NimarkoConfig.nimarkoMediaAuto) {
            items.add(UItem.asShadow(null)); 
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_NM_SupportedPlatforms)
                + "\n" + LocaleController.getString(R.string.NM_NM_PlatformsList)));
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        if (uItem == null) return;
        switch (uItem.id) {
            case ID_AUTO_DOWNLOAD:
                NimarkoConfig.toggleNimarkoMediaAuto();
                updateCheckState(view, NimarkoConfig.nimarkoMediaAuto);
                reloadMainInfo();
                break;
            case ID_YT_ASK:
                NimarkoConfig.toggleNimarkoMediaYtAsk();
                updateCheckState(view, NimarkoConfig.nimarkoMediaYtAsk);
                reloadMainInfo();
                break;
            case ID_YT_FMT_VIDEO:
                NimarkoConfig.setNimarkoMediaYtFmt(0);
                updateCheckState(view, true);
                reloadMainInfo();
                break;
            case ID_YT_FMT_AUDIO:
                NimarkoConfig.setNimarkoMediaYtFmt(1);
                updateCheckState(view, true);
                reloadMainInfo();
                break;
        }
    }

    private void reloadMainInfo() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
