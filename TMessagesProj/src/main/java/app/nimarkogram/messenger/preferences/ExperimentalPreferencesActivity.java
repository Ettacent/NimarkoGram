/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */
package app.nimarkogram.messenger.preferences;

import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.NimarkoFeatureHooks;

public class ExperimentalPreferencesActivity extends BasePreferencesActivity {
    private static final int ID_EDGE_TO_EDGE       = 1;
    private static final int ID_NO_ROUNDING        = 2;
    private static final int ID_DISCUSS_INSTEAD_OF_MUTE = 3;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_EXP_Title);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_EXP_Header_Beta)));
        items.add(UItem.asCheck(ID_EDGE_TO_EDGE, LocaleController.getString(R.string.NM_EXP_EdgeToEdge))
                .setChecked(NimarkoConfig.edgeToEdgeMode));
        items.add(UItem.asCheck(ID_NO_ROUNDING, LocaleController.getString(R.string.NM_EXP_NoRounding))
                .setChecked(NimarkoConfig.noRounding));
        items.add(UItem.asCheck(ID_DISCUSS_INSTEAD_OF_MUTE, LocaleController.getString(R.string.NM_EXP_DiscussInsteadOfMute))
                .setChecked(NimarkoConfig.discussInsteadOfMute));
        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_EXP_Footer)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_EDGE_TO_EDGE) {
            NimarkoConfig.toggleEdgeToEdgeMode();
            applyCheck(item, view, NimarkoConfig.edgeToEdgeMode);
            showRestartBulletin();
        } else if (id == ID_NO_ROUNDING) {
            NimarkoConfig.toggleNoRounding();
            applyCheck(item, view, NimarkoConfig.noRounding);
            showRestartBulletin();
        } else if (id == ID_DISCUSS_INSTEAD_OF_MUTE) {
            NimarkoConfig.toggleDiscussInsteadOfMute();
            applyCheck(item, view, NimarkoConfig.discussInsteadOfMute);
            
            NimarkoFeatureHooks.setDiscussInsteadOfMute(NimarkoConfig.discussInsteadOfMute);
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }
}
