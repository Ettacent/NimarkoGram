 
package app.nimarkogram.messenger.preferences;

import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;

public class NimarkoTextAnimPreferencesActivity extends BasePreferencesActivity {

    private static final int ID_MASTER  = 200;
    private static final int ID_APPEAR  = 201;
    private static final int ID_CURSOR  = 202;
    private static final int ID_DELETE  = 203;
    private static final int ID_SPOILER = 204;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_TA_Title);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_TA_HeaderMain)));
        items.add(UItem.asButtonCheck(ID_MASTER,
                        LocaleController.getString(R.string.NM_TA_Master),
                        LocaleController.getString(R.string.NM_TA_Master_Desc))
                .setChecked(NimarkoConfig.nimarkoTextAnim));

        if (NimarkoConfig.nimarkoTextAnim) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(LocaleController.getString(R.string.NM_TA_HeaderEffects)));
            items.add(UItem.asButtonCheck(ID_APPEAR,
                            LocaleController.getString(R.string.NM_TA_Appear),
                            LocaleController.getString(R.string.NM_TA_Appear_Desc))
                    .setChecked(NimarkoConfig.nimarkoTextAnimAppear));
            items.add(UItem.asButtonCheck(ID_CURSOR,
                            LocaleController.getString(R.string.NM_TA_Cursor),
                            LocaleController.getString(R.string.NM_TA_Cursor_Desc))
                    .setChecked(NimarkoConfig.nimarkoTextAnimCursor));
            items.add(UItem.asButtonCheck(ID_DELETE,
                            LocaleController.getString(R.string.NM_TA_Delete),
                            LocaleController.getString(R.string.NM_TA_Delete_Desc))
                    .setChecked(NimarkoConfig.nimarkoTextAnimDelete));
            items.add(UItem.asButtonCheck(ID_SPOILER,
                            LocaleController.getString(R.string.NM_TA_Spoiler),
                            LocaleController.getString(R.string.NM_TA_Spoiler_Desc))
                    .setChecked(NimarkoConfig.nimarkoTextAnimSpoiler));
            items.add(UItem.asShadow(null));
        } else {
            items.add(UItem.asShadow(LocaleController.getString(R.string.NM_TA_Master_Hint)));
        }
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        if (uItem == null) return;
        switch (uItem.id) {
            case ID_MASTER:
                NimarkoConfig.toggleNimarkoTextAnim();
                applyCheck(uItem, view, NimarkoConfig.nimarkoTextAnim);
                reload();
                break;
            case ID_APPEAR:
                NimarkoConfig.toggleNimarkoTextAnimAppear();
                applyCheck(uItem, view, NimarkoConfig.nimarkoTextAnimAppear);
                break;
            case ID_CURSOR:
                NimarkoConfig.toggleNimarkoTextAnimCursor();
                applyCheck(uItem, view, NimarkoConfig.nimarkoTextAnimCursor);
                break;
            case ID_DELETE:
                NimarkoConfig.toggleNimarkoTextAnimDelete();
                applyCheck(uItem, view, NimarkoConfig.nimarkoTextAnimDelete);
                break;
            case ID_SPOILER:
                NimarkoConfig.toggleNimarkoTextAnimSpoiler();
                applyCheck(uItem, view, NimarkoConfig.nimarkoTextAnimSpoiler);
                break;
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        updateCheckState(view, value);
    }

    private void reload() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
