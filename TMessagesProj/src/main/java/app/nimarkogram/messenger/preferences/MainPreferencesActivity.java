package app.nimarkogram.messenger.preferences;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.plugins.ui.PluginsActivity;
import app.nimarkogram.messenger.utils.AppRestartHelper;
import app.nimarkogram.messenger.wsbypass.preferences.WsBypassPreferencesActivity;

import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.SettingsSearchCell;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.Components.EmptyTextProgressView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

public class MainPreferencesActivity extends BasePreferencesActivity {

    public static final int ID_GENERAL      = 1;
    public static final int ID_APPEARANCE   = 2;
    public static final int ID_CHATS        = 3;
    public static final int ID_CAMERA       = 4;
    public static final int ID_PRIVACY      = 6;

    public static final int ID_RESTART      = 10;

    public static final int ID_PLUGINS        = 20;
    public static final int ID_DEBUG          = 21;
    private static final int ID_EXPERIMENTAL   = 22;
    public static final int ID_NIMARKO_MEDIA  = 23;
    private static final int ID_CHERRYGRAM_FORK = 24;
    public static final int ID_BANNERS        = 25;
    public static final int ID_UPDATES        = 26;
    public static final int ID_WSBYPASS       = 27;
    public static final int ID_TEXTANIM       = 28;
    public static final int ID_PILLSTACK      = 29;
    public static final int ID_SOURCE_CODE    = 30;

    private static final String SOURCE_REPOSITORY_URL = "https://github.com/Ettacent/NimarkoGram";
    private static final int SEARCH_MENU_ID = 1000;

    private ActionBarMenuItem searchItem;
    private EmptyTextProgressView emptyView;
    private boolean searching;
    private String searchQuery = "";

    private UItem category(int id, IconBackgroundColors colors, int icon, int title, int subtitle, String alias) {
        return SettingsActivity.SettingCell.Factory.of(
                        id, colors.top, colors.bottom, icon,
                        LocaleController.getString(title),
                        subtitle == 0 ? null : LocaleController.getString(subtitle))
                .setSearchable(this)
                .setLinkAlias(alias, this);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(SEARCH_MENU_ID, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        searching = true;
                        searchQuery = "";
                        refreshSearch(false);
                    }

                    @Override
                    public void onSearchCollapse() {
                        searching = false;
                        searchQuery = "";
                        refreshSearch(false);
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        searchQuery = editText.getText().toString();
                        refreshSearch(true);
                    }
                });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.NM_SettingsSearchHint));
        searchItem.setContentDescription(LocaleController.getString(R.string.Search));
        emptyView = new EmptyTextProgressView(context, null, getResourceProvider());
        emptyView.setText(LocaleController.getString(R.string.NM_SettingsSearchNoResults));
        emptyView.showTextView();
        ((android.widget.FrameLayout) view).addView(
                emptyView,
                org.telegram.ui.Components.LayoutHelper.createFrame(
                        org.telegram.ui.Components.LayoutHelper.MATCH_PARENT,
                        org.telegram.ui.Components.LayoutHelper.MATCH_PARENT));
        listView.setEmptyView(emptyView);
        return view;
    }

    @Override
    public void fillItems(ArrayList<UItem> arrayList, UniversalAdapter universalAdapter) {
        if (searching) {
            fillSearchItems(arrayList);
            return;
        }

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionMain)));
        arrayList.add(category(ID_GENERAL, IconBackgroundColors.BLUE,
                R.drawable.msg_settings_solar, R.string.NM_Cat_General, R.string.NM_SettingsSummaryGeneral, "nimarko_general"));
        arrayList.add(category(ID_APPEARANCE, IconBackgroundColors.PURPLE,
                R.drawable.msg_theme_solar, R.string.NM_Cat_Appearance, R.string.NM_SettingsSummaryAppearance, "nimarko_appearance"));
        arrayList.add(category(ID_CHATS, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_msgbubble3_solar, R.string.NM_Cat_Chats, R.string.NM_SettingsSummaryChats, "nimarko_chats"));
        arrayList.add(category(ID_CAMERA, IconBackgroundColors.CYAN,
                R.drawable.camera_solar, R.string.NM_Cat_Camera, R.string.NM_SettingsSummaryCamera, "nimarko_camera"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionPrivacyNetwork)));
        arrayList.add(category(ID_PRIVACY, IconBackgroundColors.GREEN,
                R.drawable.msg_secret_solar, R.string.NM_Cat_Privacy, R.string.NM_SettingsSummaryPrivacy, "nimarko_privacy"));
        arrayList.add(category(ID_WSBYPASS, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_satellite_solar, R.string.NM_WSB_Title, R.string.NM_SettingsSummaryBypass, "nimarko_wsbypass"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionFeatures)));
        if (PluginsController.isPluginEngineSupported()) {
            arrayList.add(category(ID_PLUGINS, IconBackgroundColors.PURPLE,
                    R.drawable.msg_plugins, R.string.Plugins, R.string.NM_SettingsSummaryPlugins, "nimarko_plugins"));
        }
        arrayList.add(category(ID_NIMARKO_MEDIA, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_download_solar, R.string.NM_DownloadMedia, R.string.NM_SettingsSummaryMedia, "nimarko_media"));
        arrayList.add(category(ID_BANNERS, IconBackgroundColors.ORANGE,
                R.drawable.msg_photos_solar, R.string.NM_BAN_Title, R.string.NM_SettingsSummaryBanners, "nimarko_banners"));
        arrayList.add(category(ID_TEXTANIM, IconBackgroundColors.PURPLE,
                R.drawable.msg_edit_solar, R.string.NM_TA_Title, R.string.NM_SettingsSummaryTextAnimation, "nimarko_textanim"));
        arrayList.add(category(ID_PILLSTACK, IconBackgroundColors.CYAN,
                R.drawable.msg_search_solar, R.string.NM_CARDS_Title, R.string.NM_SettingsSummaryInfoCards, "nimarko_infocards"));
        arrayList.add(UItem.asShadow(null));

        arrayList.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsSectionApp)));
        arrayList.add(category(ID_UPDATES, IconBackgroundColors.BLUE,
                R.drawable.msg_info_solar, R.string.UP_CheckForUpdates, R.string.NM_SettingsSummaryUpdates, "nimarko_updates"));
        arrayList.add(category(ID_DEBUG, IconBackgroundColors.GRAY,
                R.drawable.msg_log_solar, R.string.NM_SettingsAdvanced, R.string.NM_SettingsSummaryAdvanced, "nimarko_debug"));
        arrayList.add(category(ID_SOURCE_CODE, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_link_2_solar, R.string.NM_HUB_SourceCode, R.string.NM_SettingsSummarySource, "nimarko_source_code"));
        arrayList.add(category(ID_RESTART, IconBackgroundColors.ORANGE_DEEP,
                R.drawable.msg_retry_solar, R.string.NM_HUB_Restart, R.string.NM_SettingsSummaryRestart, "nimarko_restart"));
        arrayList.add(UItem.asShadow(null));
    }

    private void fillSearchItems(ArrayList<UItem> items) {
        if (TextUtils.isEmpty(searchQuery)) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.NM_SettingsQuickAccess)));
        }
        ArrayList<NimarkoSettingsSearchIndex.Entry> results =
                new ArrayList<>(NimarkoSettingsSearchIndex.search(searchQuery));
        if (results.isEmpty()) {
            return;
        }

        int previousScreen = Integer.MIN_VALUE;
        for (NimarkoSettingsSearchIndex.Entry entry : results) {
            String[] path = entry.path();
            int icon = previousScreen == entry.screen ? 0 : entry.iconRes;
            ProfileActivity.SearchAdapter.SearchResult result =
                    new ProfileActivity.SearchAdapter.SearchResult(
                            entry.guid,
                            entry.title(),
                            null,
                            path != null && path.length > 0 ? path[0] : null,
                            path != null && path.length > 1 ? path[1] : null,
                            icon,
                            () -> openSearchEntry(entry));
            CharSequence title = TextUtils.isEmpty(searchQuery)
                    ? entry.title()
                    : AndroidUtilities.generateSearchName(entry.title(), null, searchQuery.trim());
            items.add(SettingsSearchCell.Factory.of(title, result));
            previousScreen = entry.screen;
        }
    }

    private void refreshSearch(boolean animated) {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(animated);
            if (listView.layoutManager != null) {
                listView.layoutManager.scrollToPositionWithOffset(0, 0);
            }
        }
    }

    private void openSearchEntry(NimarkoSettingsSearchIndex.Entry entry) {
        BaseFragment fragment = null;
        switch (entry.screen) {
            case NimarkoSettingsSearchIndex.SCREEN_GENERAL:
                fragment = GeneralPreferencesActivity.forSetting(entry.itemId).openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_APPEARANCE:
                fragment = AppearancePreferencesActivity.forSetting(entry.itemId).openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_CHATS:
                fragment = ChatsPreferencesActivity.forSetting(entry.itemId).openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_CAMERA:
                fragment = new CameraPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_PRIVACY:
                fragment = new PrivacyPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_PLUGINS:
                fragment = new PluginsActivity();
                break;
            case NimarkoSettingsSearchIndex.SCREEN_MEDIA:
                fragment = new NimarkoMediaPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_BANNERS:
                fragment = new BannerPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_BYPASS:
                fragment = new WsBypassPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_TEXT_ANIMATION:
                fragment = new NimarkoTextAnimPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_INFO_CARDS:
                fragment = new app.nimarkogram.messenger.infocards.preferences.InfoCardsPreferencesActivity()
                        .openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_ADVANCED:
                fragment = new DebugPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_FOLDERS:
                fragment = new FoldersPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_BOTTOM_TABS:
                fragment = new BottomTabsPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_MESSAGES_PROFILES:
                fragment = new MessagesAndProfilesPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_MESSAGE_MENU:
                fragment = new MessageMenuPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_MESSAGE_MENU_ITEMS:
                fragment = new MessageMenuItemsPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.SCREEN_MESSAGE_MENU_ORDER:
                fragment = new MessageMenuOrderPreferencesActivity();
                break;
            case NimarkoSettingsSearchIndex.SCREEN_MESSAGE_FILTERS:
                fragment = new MessageFiltersPreferencesActivity();
                break;
            case NimarkoSettingsSearchIndex.SCREEN_RECENT:
                fragment = new RecentEmojisStickersPreferencesActivity().openAtSetting(entry.itemId);
                break;
            case NimarkoSettingsSearchIndex.ACTION_UPDATES:
                app.nimarkogram.messenger.updater.NimarkoUpdaterSheet.showAlert(this, false, null);
                return;
            case NimarkoSettingsSearchIndex.ACTION_SOURCE:
                org.telegram.messenger.browser.Browser.openUrl(
                        getParentActivity() != null ? getParentActivity() : getContext(), SOURCE_REPOSITORY_URL);
                return;
            case NimarkoSettingsSearchIndex.ACTION_RESTART:
                AppRestartHelper.triggerRebirth(getParentActivity() != null ? getParentActivity() : getContext());
                return;
            default:
                break;
        }
        if (fragment != null) {
            presentFragment(fragment);
        }
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.Settings);
    }

    @Override
    public void onClick(UItem uItem, View view, int i, float f, float f2) {
        if (uItem.object instanceof ProfileActivity.SearchAdapter.SearchResult) {
            ((ProfileActivity.SearchAdapter.SearchResult) uItem.object).openRunnable.run();
            return;
        }
        switch (uItem.id) {
            case ID_GENERAL:
                presentFragment(new GeneralPreferencesActivity());
                break;
            case ID_APPEARANCE:
                presentFragment(new AppearancePreferencesActivity());
                break;
            case ID_CHATS:
                presentFragment(new ChatsPreferencesActivity());
                break;
            case ID_CAMERA:
                presentFragment(new CameraPreferencesActivity());
                break;
            case ID_PRIVACY:
                presentFragment(new PrivacyPreferencesActivity());
                break;
            case ID_PLUGINS:
                presentFragment(new PluginsActivity());
                break;
            case ID_NIMARKO_MEDIA:
                presentFragment(new NimarkoMediaPreferencesActivity());
                break;
            case ID_BANNERS:
                presentFragment(new BannerPreferencesActivity());
                break;
            case ID_WSBYPASS:
                presentFragment(new WsBypassPreferencesActivity());
                break;
            case ID_PILLSTACK:
                presentFragment(new app.nimarkogram.messenger.infocards.preferences.InfoCardsPreferencesActivity());
                break;
            case ID_TEXTANIM:
                presentFragment(new NimarkoTextAnimPreferencesActivity());
                break;
            case ID_UPDATES:
                app.nimarkogram.messenger.updater.NimarkoUpdaterSheet.showAlert(this, false, null);
                break;
            case ID_DEBUG:
                presentFragment(new DebugPreferencesActivity());
                break;
            case ID_EXPERIMENTAL:
                presentFragment(new ExperimentalPreferencesActivity());
                break;
            case ID_RESTART:
                AppRestartHelper.triggerRebirth(getParentActivity() != null ? getParentActivity() : getContext());
                break;
            case ID_SOURCE_CODE:
                org.telegram.messenger.browser.Browser.openUrl(
                        getParentActivity() != null ? getParentActivity() : getContext(),
                        SOURCE_REPOSITORY_URL);
                break;
            case ID_CHERRYGRAM_FORK:
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(),
                        "https://github.com/arslan4k1390/Cherrygram");
                break;
            default:
                break;
        }
    }
}
