package app.nimarkogram.messenger.preferences;

import android.content.Context;
import android.view.View;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.folders.cells.FoldersPreviewCell;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class FoldersPreferencesActivity extends BasePreferencesActivity {
    private static final int ID_PREVIEW = 0;
    private static final int ID_HIDE_ALL_CHATS = 1;
    private static final int ID_HIDE_COUNTER = 2;
    private static final int ID_TAB_ICON_TYPE = 3;
    private static final int ID_ADD_STROKE = 4;

    private static final int ID_FOLDER_NAME_HEADER = 5;
    private static final int ID_FOLDERS_AT_BOTTOM = 6;

    private FoldersPreviewCell previewCell;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_AP_Folders);
    }

    private FoldersPreviewCell ensurePreviewCell(Context context) {
        if (previewCell == null) {
            previewCell = new FoldersPreviewCell(context);
            previewCell.setBackgroundColor(org.telegram.ui.ActionBar.Theme.getColor(
                    org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhite));
        }
        return previewCell;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        Context ctx = getContext();
        if (ctx != null) {
            items.add(UItem.asCustom(ID_PREVIEW, ensurePreviewCell(ctx)));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionFolderTabs)));
        items.add(UItem.asCheck(ID_HIDE_ALL_CHATS, LocaleController.getString(R.string.NM_FO_TabsHideAllChats))
                .setChecked(NimarkoConfig.tabsHideAllChats));
        items.add(UItem.asCheck(ID_HIDE_COUNTER, LocaleController.getString(R.string.NM_FO_TabsNoCounter))
                .setChecked(NimarkoConfig.tabsNoUnread));
        items.add(asSettingsValue(ID_TAB_ICON_TYPE, IconBackgroundColors.BLUE,
                R.drawable.msg_customize,
                LocaleController.getString(R.string.NM_FO_TabStyle),
                safeAt(tabModeOptions(), NimarkoConfig.tabMode)));
        items.add(SettingsHelper.asSwitchCG(ID_ADD_STROKE,
                        LocaleController.getString(R.string.NM_FO_TabStyleStroke),
                        LocaleController.getString(R.string.NM_FO_TabStyleStroke_Desc))
                .setChecked(NimarkoConfig.tabStyleStroke));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionFolderLayout)));
        items.add(SettingsHelper.asSwitchCG(ID_FOLDER_NAME_HEADER,
                LocaleController.getString(R.string.NM_FO_FolderNameInHeader))
                .setChecked(NimarkoConfig.folderNameInHeader));
        items.add(SettingsHelper.asSwitchCG(ID_FOLDERS_AT_BOTTOM,
                        LocaleController.getString(R.string.NM_FO_FoldersAtBottom),
                        LocaleController.getString(R.string.NM_FO_FoldersAtBottom_Desc))
                .setChecked(NimarkoConfig.foldersAtBottom));
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_HIDE_ALL_CHATS) {
            NimarkoConfig.toggleTabsHideAllChats();
            applyCheck(item, view, NimarkoConfig.tabsHideAllChats);
            if (previewCell != null) previewCell.updateAllChatsTabName(true);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        } else if (id == ID_HIDE_COUNTER) {
            NimarkoConfig.toggleTabsNoUnread();
            applyCheck(item, view, NimarkoConfig.tabsNoUnread);
            if (previewCell != null) previewCell.updateTabCounter(true);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
        } else if (id == ID_TAB_ICON_TYPE) {
            PopupHelper.showSimpleAlert(this,
                    LocaleController.getString(R.string.NM_FO_TabStyle),
                    tabModeOptions(),
                    NimarkoConfig.tabMode,
                    sel -> {
                        NimarkoConfig.setTabMode(sel);
                        refreshValueCell(view, safeAt(tabModeOptions(), sel));
                        if (previewCell != null) {
                            previewCell.updateTabIcons(true);
                            previewCell.updateTabTitle(true);
                        }
                        if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
                        getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
                    });
        } else if (id == ID_ADD_STROKE) {
            NimarkoConfig.toggleTabStyleStroke();
            applyCheck(item, view, NimarkoConfig.tabStyleStroke);
            if (previewCell != null) previewCell.invalidate();
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (id == ID_FOLDER_NAME_HEADER) {
            NimarkoConfig.toggleFolderNameInHeader();
            applyCheck(item, view, NimarkoConfig.folderNameInHeader);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
        } else if (id == ID_FOLDERS_AT_BOTTOM) {
            NimarkoConfig.toggleFoldersAtBottom();
            applyCheck(item, view, NimarkoConfig.foldersAtBottom);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
            showRestartBulletin();
        }
    }

    private void applyCheck(UItem item, View view, boolean value) {
        item.checked = value;
        updateCheckState(view, value);
    }

    private void refreshValueCell(View view, String newValue) {
        if (view instanceof TextCell) {
            ((TextCell) view).setValue(newValue, true);
        } else if (view instanceof SettingsActivity.SettingCell) {
            ((SettingsActivity.SettingCell) view).setValue(newValue);
        }
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    }

    private String[] tabModeOptions() {
        return new String[]{
                LocaleController.getString(R.string.NM_FO_TabModeMix),
                LocaleController.getString(R.string.NM_FO_TabModeText),
                LocaleController.getString(R.string.NM_FO_TabModeIcon),
        };
    }

    private static String safeAt(String[] arr, int i) {
        if (arr == null || arr.length == 0) return "";
        if (i < 0 || i >= arr.length) return arr[0];
        return arr[i];
    }
}
