/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * It is licensed under GNU GPL v. 2 or later.
 */

/* Modifications Copyright (C) 2026 Ettacent */

/**
 * This is the source code of Nimarko for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Ettacent, 2022-2026.
 */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.view.View;

import org.telegram.messenger.R;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;
import app.nimarkogram.messenger.utils.VibrateUtils;

public class MessageMenuPreferencesActivity extends UniversalPreferencesActivity {

    static final int SETTING_ITEMS = 1;
    static final int SETTING_ORDER = 2;
    static final int SETTING_MODERN_MENU = 3;
    static final int SETTING_COMPACT_LAYOUT = 4;
    static final int SETTING_HAPTIC = 5;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.NM_MessageMenu);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionActions)));
        items.add(asSettingsLink(SETTING_ITEMS, IconBackgroundColors.BLUE,
                R.drawable.msg_settings, getString(R.string.NM_MessageMenuItems)));
        items.add(asSettingsLink(SETTING_ORDER, IconBackgroundColors.PURPLE,
                R.drawable.msg_reorder, getString(R.string.NM_Menu_Reorder)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionAppearanceFeedback)));
        if (VibrateUtils.hasVibrator()) {
            items.add(SettingsHelper.asSwitchCG(SETTING_HAPTIC,
                            getString(R.string.NM_Menu_Haptic),
                            getString(R.string.NM_Menu_Haptic_Desc))
                    .setChecked(AppConfig.messageMenuHaptic));
        }
        items.add(SettingsHelper.asSwitchCG(SETTING_MODERN_MENU,
                        getString(R.string.NM_Menu_TelegramPlus),
                        getString(R.string.NM_Menu_TelegramPlus_Desc))
                .setChecked(AppConfig.telegramPlusMessageMenu));
        items.add(SettingsHelper.asSwitchCG(SETTING_COMPACT_LAYOUT,
                        getString(R.string.NM_MessageMenuCompactLayout),
                        getString(R.string.NM_MessageMenuCompactLayout_Desc) + " "
                                + getString(R.string.NM_MessageMenuCompactLayout_Dot))
                .setChecked(AppConfig.msgMenuItemsCompactView));
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
                AppConfig.toggleMessageMenuHaptic();
                updateCheckState(view, AppConfig.messageMenuHaptic);
            }
            case SETTING_MODERN_MENU -> {
                AppConfig.toggleTelegramPlusMessageMenu();
                updateCheckState(view, AppConfig.telegramPlusMessageMenu);
            }
            case SETTING_COMPACT_LAYOUT -> {
                AppConfig.toggleMsgMenuItemsCompactView();
                updateCheckState(view, AppConfig.msgMenuItemsCompactView);
            }
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
