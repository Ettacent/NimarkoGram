/**
 * This file is part of NimarkoGram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * NimarkoGram modifications:
 * Copyright Ettacent, 2026.
 *
 * Portions derived from Cherrygram:
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;
import app.nimarkogram.messenger.utils.VibrateUtils;

public class MessageMenuPreferencesActivity extends NimarkoUniversalPreferencesActivity {

    static final int SETTING_ITEMS = 1;
    static final int SETTING_ORDER = 2;
    static final int SETTING_MODERN_MENU = 3;
    static final int SETTING_COMPACT_LAYOUT = 4;
    static final int SETTING_HAPTIC = 5;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.CP_MessageMenu);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionActions)));
        items.add(asSettingsLink(SETTING_ITEMS, IconBackgroundColors.BLUE,
                R.drawable.msg_list, getString(R.string.CP_MessageMenuItems)));
        items.add(asSettingsLink(SETTING_ORDER, IconBackgroundColors.PURPLE,
                R.drawable.msg_reorder, getString(R.string.NM_Menu_Reorder)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionAppearanceFeedback)));
        if (VibrateUtils.hasVibrator()) {
            items.add(SettingsHelper.asSwitchCG(SETTING_HAPTIC,
                            getString(R.string.NM_Menu_Haptic),
                            getString(R.string.NM_Menu_Haptic_Desc))
                    .setChecked(NimarkoConfig.messageMenuHaptic));
        }
        items.add(SettingsHelper.asSwitchCG(SETTING_MODERN_MENU,
                        getString(R.string.NM_Menu_TelegramPlus),
                        getString(R.string.NM_Menu_TelegramPlus_Desc))
                .setChecked(NimarkoConfig.telegramPlusMessageMenu));
        items.add(SettingsHelper.asSwitchCG(SETTING_COMPACT_LAYOUT,
                        getString(R.string.CP_MessageMenuCompactLayout),
                        getString(R.string.CP_MessageMenuCompactLayout_Desc) + " "
                                + getString(R.string.CP_MessageMenuCompactLayout_Dot))
                .setChecked(NimarkoConfig.msgMenuItemsCompactView));
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null) {
            return;
        }
        switch (item.id) {
            case SETTING_ITEMS -> presentFragment(new MessageMenuItemsPreferencesActivity());
            case SETTING_ORDER -> presentFragment(new MessageMenuOrderPreferencesActivity());
            case SETTING_HAPTIC -> {
                NimarkoConfig.toggleMessageMenuHaptic();
                updateCheckState(view, NimarkoConfig.messageMenuHaptic);
            }
            case SETTING_MODERN_MENU -> {
                NimarkoConfig.toggleTelegramPlusMessageMenu();
                updateCheckState(view, NimarkoConfig.telegramPlusMessageMenu);
            }
            case SETTING_COMPACT_LAYOUT -> {
                NimarkoConfig.toggleMsgMenuItemsCompactView();
                updateCheckState(view, NimarkoConfig.msgMenuItemsCompactView);
            }
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
