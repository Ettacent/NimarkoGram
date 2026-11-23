/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;

import org.telegram.ui.DialogsActivity;
import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;
import app.nimarkogram.messenger.preferences.tabs.MainTabsPreviewCell;
import app.nimarkogram.messenger.utils.ui.MainTabsManager;

public class BottomTabsPreferencesActivity extends BasePreferencesActivity {
    private static final int ID_SHOW_TABS = 1;
    private static final int ID_SHOW_TITLE = 2;
    private static final int ID_FORCE_OPEN_CHATS = 4;
    private static final int ID_SHOW_SEARCH_IN_TABS = 5;
    private static final int ID_RESET_ORDER = 6;

    private MainTabsPreviewCell editorCell;

    private ArrayList<MainTabsManager.Tab> tabs;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_AP_BottomTabs);
    }

    @Override
    public boolean onFragmentCreate() {
        tabs = new ArrayList<>(MainTabsManager.INSTANCE.getAllTabs());
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        if (editorCell != null) {
            editorCell.setOnReorderCommitted(null);
        }
        super.onFragmentDestroy();
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(-1, LocaleController.getString(R.string.NM_BT_LayoutHeader)));
        UItem enableTabs = SettingsHelper.asSwitchCG(ID_SHOW_TABS,
                        LocaleController.getString(R.string.NM_BT_ShowTabs))
                .setChecked(AppConfig.showMainTabs);
        enableTabs.hideDivider = true;
        items.add(enableTabs);

        if (AppConfig.showMainTabs) {
            items.add(UItem.asShadow(-2, null));
            if (editorCell == null && getContext() != null) {
                editorCell = new MainTabsPreviewCell(getContext());
                editorCell.setEditMode(true);
                editorCell.setTabs(getContext(), getResourceProvider(), tabs);

                editorCell.setOnReorderCommitted(this::postCgTabsUpdated);
            } else if (editorCell != null) {
                editorCell.refresh();
            }
            if (editorCell != null) {
                items.add(UItem.asCustom(editorCell,
                        DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
                items.add(UItem.asShadow(-3, LocaleController.getString(R.string.NM_BT_EditorFooter)));
            }

            items.add(UItem.asHeader(-4, LocaleController.getString(R.string.NM_SettingsSectionDisplay)));
            items.add(UItem.asCheck(ID_SHOW_TITLE,
                            LocaleController.getString(R.string.NM_BT_ShowTabsTitle))
                    .setChecked(AppConfig.showMainTabsTitle));
            items.add(SettingsHelper.asSwitchCG(ID_SHOW_SEARCH_IN_TABS,
                            LocaleController.getString(R.string.NM_BT_ShowSearchInTabs),
                            LocaleController.getString(R.string.NM_BT_ShowSearchInTabs_Desc))
                    .setChecked(AppConfig.showSearchInTabs));
            items.add(SettingsHelper.asSwitchCG(ID_FORCE_OPEN_CHATS,
                            LocaleController.getString(R.string.NM_BT_ForceOpenChats),
                            LocaleController.getString(R.string.NM_MainTabs_ForceOpenChats_Desc))
                    .setChecked(AppConfig.mainTabsForceOpenChats));
            items.add(asSettingsLink(ID_RESET_ORDER, IconBackgroundColors.ORANGE,
                    R.drawable.msg_reset, LocaleController.getString(R.string.Reset)));
        }
        items.add(SettingsFooterItem.of(-5, AppConfig.showMainTabs
                ? LocaleController.getString(R.string.NM_SettingsSummaryBottomTabs) : null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_SHOW_TABS) {
            AppConfig.toggleShowMainTabs();
            applyCheck(item, view, AppConfig.showMainTabs);
            updateItemsAfterToggle();

            postCgTabsUpdated();
        } else if (id == ID_SHOW_TITLE) {
            AppConfig.toggleShowMainTabsTitle();
            applyCheck(item, view, AppConfig.showMainTabsTitle);
            if (editorCell != null) {
                editorCell.refresh();
            }
            postCgTabsUpdated();
        } else if (id == ID_SHOW_SEARCH_IN_TABS) {
            AppConfig.toggleShowSearchInTabs();
            applyCheck(item, view, AppConfig.showSearchInTabs);
            if (editorCell != null) {
                editorCell.refresh();
            }
            postCgTabsUpdated();
        } else if (id == ID_FORCE_OPEN_CHATS) {
            AppConfig.toggleMainTabsForceOpenChats();
            applyCheck(item, view, AppConfig.mainTabsForceOpenChats);
        } else if (id == ID_RESET_ORDER) {

            AppConfig.setMainTabsOrder(null);
            tabs.clear();
            tabs.addAll(MainTabsManager.INSTANCE.getAllTabs());
            if (editorCell != null) {
                editorCell.setTabs(getContext(), getResourceProvider(), tabs);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            postCgTabsUpdated();
        }
    }

    private void postCgTabsUpdated() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.cgTabsUpdated);
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        updateCheckState(view, value);
    }
}
