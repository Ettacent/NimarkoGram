package app.nimarkogram.messenger.preferences;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;
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
    private ArrayList<MainTabsManager.Tab> initialTabs;
    private boolean resetToDefaults;
    private boolean fragmentAlive;
    private int uiGeneration;
    private int pendingStructureRefreshGeneration;

    private final Runnable delayedStructureRefresh = () -> {
        if (!fragmentAlive || pendingStructureRefreshGeneration != uiGeneration) {
            return;
        }
        if (editorCell != null) {
            editorCell.setOnReorderCommitted(null);
        }
        editorCell = null;
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
            listView.adapter.notifyDataSetChanged();
        }
    };

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_AP_BottomTabs);
    }

    @Override
    public boolean onFragmentCreate() {
        fragmentAlive = true;
        uiGeneration++;
        initialTabs = new ArrayList<>();
        for (MainTabsManager.Tab t : MainTabsManager.INSTANCE.getAllTabs()) {
            initialTabs.add(new MainTabsManager.Tab(t.getType(), t.enabled));
        }
        tabs = new ArrayList<>();
        for (MainTabsManager.Tab t : MainTabsManager.INSTANCE.getAllTabs()) {
            tabs.add(new MainTabsManager.Tab(t.getType(), t.enabled));
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        fragmentAlive = false;
        uiGeneration++;
        AndroidUtilities.cancelRunOnUIThread(delayedStructureRefresh);
        if (editorCell != null) {
            editorCell.setOnReorderCommitted(null);
        }
        super.onFragmentDestroy();
        commitTabs(true);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_BT_LayoutHeader)));
        UItem enableTabs = SettingsHelper.asSwitchCG(ID_SHOW_TABS,
                        LocaleController.getString(R.string.NM_BT_ShowTabs))
                .setChecked(NimarkoConfig.showMainTabs);
        enableTabs.hideDivider = true;
        items.add(enableTabs);
        items.add(UItem.asShadow(LocaleController.getString(R.string.NM_SettingsSummaryBottomTabs)));

        if (NimarkoConfig.showMainTabs) {
            if (editorCell == null && getContext() != null) {
                editorCell = new MainTabsPreviewCell(getContext());
                editorCell.setEditMode(true);
                editorCell.setTabs(getContext(), getResourceProvider(), tabs);
                editorCell.setOnReorderCommitted(() -> {
                    resetToDefaults = false;
                    postCgTabsUpdated();
                });
            } else if (editorCell != null) {
                editorCell.refresh();
            }
            if (editorCell != null) {
                items.add(UItem.asCustom(editorCell,
                        org.telegram.ui.DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
                items.add(UItem.asShadow(LocaleController.getString(R.string.NM_BT_EditorFooter)));
            }

            items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionDisplay)));
            items.add(UItem.asCheck(ID_SHOW_TITLE,
                            LocaleController.getString(R.string.NM_BT_ShowTabsTitle))
                    .setChecked(NimarkoConfig.showMainTabsTitle));
            items.add(SettingsHelper.asSwitchCG(ID_SHOW_SEARCH_IN_TABS,
                            LocaleController.getString(R.string.NM_BT_ShowSearchInTabs),
                            LocaleController.getString(R.string.NM_BT_ShowSearchInTabs_Desc))
                    .setChecked(NimarkoConfig.showSearchInTabs));
            items.add(SettingsHelper.asSwitchCG(ID_FORCE_OPEN_CHATS,
                            LocaleController.getString(R.string.NM_BT_ForceOpenChats),
                            LocaleController.getString(R.string.CP_MainTabs_ForceOpenChats_Desc))
                    .setChecked(NimarkoConfig.mainTabsForceOpenChats));
            items.add(asSettingsLink(ID_RESET_ORDER, IconBackgroundColors.ORANGE,
                    R.drawable.msg_reset, LocaleController.getString(R.string.Reset)));
            items.add(UItem.asShadow(null));
        }
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_SHOW_TABS) {
            NimarkoConfig.toggleShowMainTabs();
            applyCheck(item, view, NimarkoConfig.showMainTabs);
            AndroidUtilities.cancelRunOnUIThread(delayedStructureRefresh);
            pendingStructureRefreshGeneration = uiGeneration;
            AndroidUtilities.runOnUIThread(delayedStructureRefresh, 220);
            postCgTabsUpdated();
            showRestartBulletin();
            rebuildMainTabsFragments();
        } else if (id == ID_SHOW_TITLE) {
            NimarkoConfig.toggleShowMainTabsTitle();
            applyCheck(item, view, NimarkoConfig.showMainTabsTitle);
            if (editorCell != null) {
                editorCell.refresh();
            }
            postCgTabsUpdated();
            rebuildMainTabsFragments();
        } else if (id == ID_SHOW_SEARCH_IN_TABS) {
            NimarkoConfig.toggleShowSearchInTabs();
            applyCheck(item, view, NimarkoConfig.showSearchInTabs);
            if (editorCell != null) {
                editorCell.refresh();
            }
            postCgTabsUpdated();
            rebuildMainTabsFragments();
        } else if (id == ID_FORCE_OPEN_CHATS) {
            NimarkoConfig.toggleMainTabsForceOpenChats();
            applyCheck(item, view, NimarkoConfig.mainTabsForceOpenChats);
        } else if (id == ID_RESET_ORDER) {
            NimarkoConfig.setMainTabsOrder(null);
            resetToDefaults = true;
            tabs.clear();
            for (MainTabsManager.Tab t : MainTabsManager.INSTANCE.getAllTabs()) {
                tabs.add(new MainTabsManager.Tab(t.getType(), t.enabled));
            }
            initialTabs = new ArrayList<>();
            for (MainTabsManager.Tab t : tabs) {
                initialTabs.add(new MainTabsManager.Tab(t.getType(), t.enabled));
            }
            if (editorCell != null) {
                editorCell.setTabs(getContext(), getResourceProvider(), tabs);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            postCgTabsUpdated();
            rebuildMainTabsFragments();
        }
    }

    private void commitTabs(boolean notify) {
        if (tabs == null || resetToDefaults) return;
        MainTabsManager.INSTANCE.saveTabs(tabs);
        if (notify && !tabs.equals(initialTabs)) {
            postCgTabsUpdated();
        }
    }

    private void postCgTabsUpdated() {
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.cgTabsUpdated),
                80);
    }

    private void rebuildMainTabsFragments() {
        if (getParentLayout() != null) {
            getParentLayout().rebuildAllFragmentViews(false, false);
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        updateCheckState(view, value);
    }
}
