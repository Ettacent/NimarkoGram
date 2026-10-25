/* Modifications Copyright (C) 2026 Ettacent */



package app.nimarkogram.messenger.preferences;

import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.AppConfig;

public class TextAnimPreferencesActivity extends BasePreferencesActivity {

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
        items.add(UItem.asHeader(-1, LocaleController.getString(R.string.NM_TA_HeaderMain)));
        items.add(UItem.asButtonCheck(ID_MASTER,
                        LocaleController.getString(R.string.NM_TA_Master),
                        LocaleController.getString(R.string.NM_TA_Master_Desc))
                .setChecked(AppConfig.nimarkoTextAnim));

        if (AppConfig.nimarkoTextAnim) {
            items.add(UItem.asHeader(-3, LocaleController.getString(R.string.NM_TA_HeaderEffects)));
            items.add(UItem.asButtonCheck(ID_APPEAR,
                            LocaleController.getString(R.string.NM_TA_Appear),
                            LocaleController.getString(R.string.NM_TA_Appear_Desc))
                    .setChecked(AppConfig.nimarkoTextAnimAppear));
            items.add(UItem.asButtonCheck(ID_CURSOR,
                            LocaleController.getString(R.string.NM_TA_Cursor),
                            LocaleController.getString(R.string.NM_TA_Cursor_Desc))
                    .setChecked(AppConfig.nimarkoTextAnimCursor));
            items.add(UItem.asButtonCheck(ID_DELETE,
                            LocaleController.getString(R.string.NM_TA_Delete),
                            LocaleController.getString(R.string.NM_TA_Delete_Desc))
                    .setChecked(AppConfig.nimarkoTextAnimDelete));
            items.add(UItem.asButtonCheck(ID_SPOILER,
                            LocaleController.getString(R.string.NM_TA_Spoiler),
                            LocaleController.getString(R.string.NM_TA_Spoiler_Desc))
                    .setChecked(AppConfig.nimarkoTextAnimSpoiler));
        }
        items.add(SettingsFooterItem.of(-4, AppConfig.nimarkoTextAnim
                ? LocaleController.getString(R.string.NM_SettingsSummaryTextAnimation) : null));
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        if (uItem == null) return;
        switch (uItem.id) {
            case ID_MASTER:
                AppConfig.toggleNimarkoTextAnim();
                applyCheck(uItem, view, AppConfig.nimarkoTextAnim);
                updateItemsAfterToggle();
                break;
            case ID_APPEAR:
                AppConfig.toggleNimarkoTextAnimAppear();
                applyCheck(uItem, view, AppConfig.nimarkoTextAnimAppear);
                break;
            case ID_CURSOR:
                AppConfig.toggleNimarkoTextAnimCursor();
                applyCheck(uItem, view, AppConfig.nimarkoTextAnimCursor);
                break;
            case ID_DELETE:
                AppConfig.toggleNimarkoTextAnimDelete();
                applyCheck(uItem, view, AppConfig.nimarkoTextAnimDelete);
                break;
            case ID_SPOILER:
                AppConfig.toggleNimarkoTextAnimSpoiler();
                applyCheck(uItem, view, AppConfig.nimarkoTextAnimSpoiler);
                break;
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        updateCheckState(view, value);
    }

}
