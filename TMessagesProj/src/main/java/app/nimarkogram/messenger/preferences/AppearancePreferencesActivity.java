/* Modifications Copyright (C) 2026 Ettacent */

/**
 * This is the source code of Nimarko for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.preferences;

import android.os.Build;
import app.nimarkogram.messenger.preferences.components.AvatarCornersPreviewCell;
import app.nimarkogram.messenger.preferences.components.StickerSizeCell;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.icons.IconResources;
import app.nimarkogram.messenger.preferences.helpers.AlertDialogSwitchers;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class AppearancePreferencesActivity extends UniversalPreferencesActivity {

    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_NAVIGATION = 1;
    private static final int PAGE_INTERFACE = 2;
    private static final int PAGE_CHAT = 3;
    private static final int PAGE_AVATARS = 4;

    private static final int openNavigationRow = 1_001;
    private static final int openInterfaceRow = 1_002;
    private static final int openChatRow = 1_003;
    private static final int openAvatarsRow = 1_004;

    private final int page;

    private static final int centerTitleRow = 1;
    private static final int hideSearchBar = 2;
    private static final int snowflakesRow = 3;

    private static final int iconPackRow = 4;
    private static final int oneUISwitchesRow = 5;
    private static final int disableDividersRow = 6;
    private static final int glareOnElementsRow = 10;
    private static final int forumAvatarsRow = 12;
    private static final int forceBlurRow = 13;
    private static final int hideStatusRow = 14;
    private static final int customTitleRow = 15;
    private static final int mediaGlowRow = 16;
    private static final int hideBubbleTailRow = 18;
    private static final int onlineIndicatorRow = 19;
    private static final int hideStickerTimeRow = 20;
    private static final int iosStyleComposerRow = 21;
    private static final int avatarCornersPreviewRow = 22;
    private static final int stickerSizePreviewRow = 23;
    private static final int messageSizeRow = 24;
    private static final int centerChatTitleRow = 25;
    private static final int unreadBadgeRow = 26;
    private static final int customWallpapersRow = 27;
    private static final int chatSnowflakesRow = 28;
    private static final int hideMuteButtonRow = 29;
    private static final int weekdayNearDateRow = 30;
    private static final int enhancedGlassBlurRow = 31;

    private static final int motionBlurRow = 32;
    private static final int foldersRow = 7;
    private static final int bottomTabsRow = 8;
    private static final int messagesAndProfilesRow = 9;

    private AvatarCornersPreviewCell avatarCornersCell;
    private StickerSizeCell stickerSizeCell;

    private Resources boundIconResources;
    private Configuration boundIconConfiguration;
    private long boundIconGeneration = Long.MIN_VALUE;
    public AppearancePreferencesActivity() {
        this(PAGE_OVERVIEW);
    }

    private AppearancePreferencesActivity(int page) {
        this.page = page;
    }

    public static AppearancePreferencesActivity forSetting(int itemId) {
        int targetPage = switch (itemId) {
            case centerTitleRow, hideSearchBar, hideStatusRow, customTitleRow,
                    foldersRow, bottomTabsRow -> PAGE_NAVIGATION;
            case snowflakesRow, iconPackRow, oneUISwitchesRow, disableDividersRow,
                    glareOnElementsRow, forceBlurRow, mediaGlowRow, chatSnowflakesRow,
                    enhancedGlassBlurRow, motionBlurRow -> PAGE_INTERFACE;
            case messagesAndProfilesRow, messageSizeRow, iosStyleComposerRow, hideBubbleTailRow,
                    centerChatTitleRow, unreadBadgeRow, customWallpapersRow, hideMuteButtonRow,
                    weekdayNearDateRow -> PAGE_CHAT;
            case forumAvatarsRow, onlineIndicatorRow, hideStickerTimeRow,
                    avatarCornersPreviewRow, stickerSizePreviewRow -> PAGE_AVATARS;
            default -> PAGE_OVERVIEW;
        };
        return new AppearancePreferencesActivity(targetPage);
    }

    @Override
    protected CharSequence getTitle() {
        return switch (page) {
            case PAGE_NAVIGATION -> getString(R.string.NM_SettingsSectionNavigationHeader);
            case PAGE_INTERFACE -> getString(R.string.NM_SettingsSectionInterfaceEffects);
            case PAGE_CHAT -> getString(R.string.NM_SettingsSectionChatAppearance);
            case PAGE_AVATARS -> getString(R.string.NM_SettingsSectionAvatarsStickers);
            default -> getString(R.string.AP_Header_Appearance);
        };
    }

    @Override
    public View createView(Context context) {
        setMD3(true);
        View view = super.createView(context);
        rememberIconResources(view.getResources());
        return view;
    }

    private void rememberIconResources(Resources resources) {
        boundIconResources = resources;
        boundIconConfiguration = new Configuration(resources.getConfiguration());
        boundIconGeneration = resources instanceof IconResources
                ? ((IconResources) resources).getReplacementGeneration() : Long.MIN_VALUE;
    }

    @Override public void onResume() {
        super.onResume();
        onIconPackResourcesChanged();
    }

    @Override public boolean onIconPackResourcesChanged() {
        if (fragmentView == null || actionBar == null || listView == null || listView.adapter == null) return false;
        Resources resources = fragmentView.getResources();
        if (!(resources instanceof IconResources) || boundIconConfiguration == null
                || !boundIconConfiguration.equals(resources.getConfiguration())) return false;
        long generation = ((IconResources) resources).getReplacementGeneration();
        if (resources != boundIconResources || generation != boundIconGeneration) {
            actionBar.setBackButtonDrawable(resources.getDrawable(R.drawable.ic_ab_back).mutate());
            listView.adapter.update(false);
            rememberIconResources(resources);
        }
        return true;
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        switch (page) {
            case PAGE_NAVIGATION -> {
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionNavigationLayout)));
                fillLayout(items);
                items.add(UItem.asShadow(null));
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionNavigationHeader)));
                fillHeader(items);
                items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryNavigationHeader)));
            }
            case PAGE_INTERFACE -> {
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionInterface)));
                fillInterface(items);
                items.add(UItem.asShadow(null));
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionEffects)));
                fillEffects(items);
                items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryInterfaceEffects)));
            }
            case PAGE_CHAT -> {
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionChatAppearance)));
                fillChat(items);
                items.add(UItem.asShadow(getString(R.string.NM_SettingsSummaryChatView)));
            }
            case PAGE_AVATARS -> {
                items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionAvatarsStickers)));
                fillAvatarsAndStickers(items);
            }
            default -> fillOverview(items);
        }
    }

    private void fillOverview(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.NM_SettingsSectionLayout)));
        items.add(asSettingsLink(openNavigationRow, IconBackgroundColors.BLUE,
                R.drawable.msg_folders, getString(R.string.NM_SettingsSectionNavigationHeader),
                getString(R.string.NM_SettingsSummaryNavigationHeader)));
        items.add(asSettingsLink(openInterfaceRow, IconBackgroundColors.PURPLE,
                R.drawable.msg_colors, getString(R.string.NM_SettingsSectionInterfaceEffects),
                getString(R.string.NM_SettingsSummaryInterfaceEffects)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.MessagesSettings)));
        items.add(asSettingsLink(openChatRow, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_msgbubble3, getString(R.string.NM_SettingsSectionChatAppearance),
                getString(R.string.NM_SettingsSummaryChatView)));
        items.add(asSettingsLink(openAvatarsRow, IconBackgroundColors.ORANGE,
                R.drawable.msg_contacts_name, getString(R.string.NM_SettingsSectionAvatarsStickers),
                getString(R.string.NM_SettingsSummaryAvatarsStickers)));
        items.add(UItem.asShadow(null));
    }

    private void fillLayout(ArrayList<UItem> items) {
        items.add(asSettingsLink(foldersRow, IconBackgroundColors.BLUE,
                R.drawable.msg_folders, getString(R.string.NM_Filters_Header),
                getString(R.string.NM_SettingsSummaryFolders)));
        items.add(asSettingsLink(bottomTabsRow, IconBackgroundColors.PURPLE,
                R.drawable.tabs_reorder, getString(R.string.NM_MainTabs_Header),
                getString(R.string.NM_SettingsSummaryBottomTabs)));
    }

    private void fillInterface(ArrayList<UItem> items) {
        items.add(asSettingsValue(iconPackRow, IconBackgroundColors.PURPLE,
                R.drawable.msg_colors, getString(R.string.AP_IconReplacements), getIconPackValueText()));
        items.add(asSettingsValue(oneUISwitchesRow, IconBackgroundColors.GREEN,
                R.drawable.msg_hybrid, getString(R.string.NM_SwitchStyle), getSwitchStyleValueText()));
        items.add(SettingsHelper.asSwitchCG(disableDividersRow, getString(R.string.AP_DisableDividers))
                .setChecked(AppConfig.disableDividers)
        );
    }

    private void fillHeader(ArrayList<UItem> items) {
        items.add(asSettingsValue(customTitleRow, IconBackgroundColors.ORANGE,
                R.drawable.msg_jobtitle, getString(R.string.NM_CustomTitle), getCustomTitleValueText()));
        items.add(SettingsHelper.asSwitchCG(centerTitleRow, getString(R.string.AP_CenterTitle))
                .setChecked(AppConfig.centerTitle)
        );
        items.add(SettingsHelper.asSwitchCG(hideSearchBar, getString(R.string.AP_HideSearchBar))
                .setChecked(AppConfig.hideSearchBar)
        );
        items.add(SettingsHelper.asSwitchCG(hideStatusRow, getString(R.string.NM_HideActionBarStatus))
                .setChecked(AppConfig.hideActionBarStatus));
    }

    private void fillChat(ArrayList<UItem> items) {
        items.add(asSettingsLink(messagesAndProfilesRow, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_contacts_name, getString(R.string.NM_ProfileReplyBackground),
                getString(R.string.NM_SettingsSummaryMessagesProfiles)));
        items.add(asSettingsLink(messageSizeRow, IconBackgroundColors.CYAN,
                R.drawable.msg_zoomin, getString(R.string.NM_Messages_Size)));
        items.add(SettingsHelper.asSwitchCG(iosStyleComposerRow,
                        getString(R.string.NM_IOSStyleComposer),
                        getString(R.string.NM_IOSStyleComposer_Desc))
                .setChecked(AppConfig.iosStyleComposer));
        items.add(SettingsHelper.asSwitchCG(hideBubbleTailRow,
                        getString(R.string.NM_HideBubbleTail),
                        getString(R.string.NM_HideBubbleTail_Desc))
                .setChecked(AppConfig.hideBubbleTail));
        items.add(SettingsHelper.asSwitchCG(centerChatTitleRow,
                        getString(R.string.NM_CenterTitleInChat))
                .setChecked(AppConfig.centerChatTitle));
        items.add(SettingsHelper.asSwitchCG(unreadBadgeRow,
                        getString(R.string.NM_UnreadBadgeOnBackButton),
                        getString(R.string.NM_UnreadBadgeOnBackButton_Desc))
                .setChecked(AppConfig.unreadBadgeOnBackButton));
        items.add(SettingsHelper.asSwitchCG(customWallpapersRow,
                        getString(R.string.NM_CustomWallpapers),
                        getString(R.string.NM_CustomWallpapers_Desc))
                .setChecked(AppConfig.customWallpapers));
        items.add(SettingsHelper.asSwitchCG(hideMuteButtonRow,
                        getString(R.string.NM_HideMuteUnmuteButton))
                .setChecked(AppConfig.hideMuteUnmuteButton));
        items.add(SettingsHelper.asSwitchCG(weekdayNearDateRow,
                        getString(R.string.NM_WeekdayNearDate),
                        getString(R.string.NM_WeekdayNearDate_Desc))
                .setChecked(AppConfig.weekdayNearDate));
    }

    private void fillEffects(ArrayList<UItem> items) {
        if (Build.VERSION.SDK_INT >= 31) {
            items.add(SettingsHelper.asSwitchCG(enhancedGlassBlurRow,
                            getString(R.string.NM_EnhancedGlassBlur),
                            getString(R.string.NM_EnhancedGlassBlur_Desc))
                    .setChecked(AppConfig.enhancedGlassBlur));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            items.add(SettingsHelper.asSwitchCG(motionBlurRow,
                            getString(R.string.NM_MotionBlur),
                            getString(R.string.NM_MotionBlur_Desc))
                    .setChecked(AppConfig.motionBlur));
        }
        items.add(SettingsHelper.asSwitchCG(forceBlurRow, getString(R.string.NM_ForceBlur))
                .setChecked(AppConfig.forceBlur));
        items.add(SettingsHelper.asSwitchCG(glareOnElementsRow,
                        getString(R.string.AP_GlareOnElements),
                        getString(R.string.AP_GlareOnElementsInfo))
                .setChecked(AppConfig.glareOnElements)
        );
        items.add(SettingsHelper.asSwitchCG(mediaGlowRow,
                        getString(R.string.NM_MediaGlow),
                        getString(R.string.NM_MediaGlow_Desc))
                .setChecked(AppConfig.mediaGlow)
        );
        items.add(SettingsHelper.asSwitchCG(snowflakesRow, getString(R.string.NM_SnowInHeader))
                .setChecked(AppConfig.drawSnowInActionBar));
        items.add(SettingsHelper.asSwitchCG(chatSnowflakesRow, getString(R.string.NM_SnowflakesInChat))
                .setChecked(AppConfig.drawSnowInChat));
    }

    private void fillAvatarsAndStickers(ArrayList<UItem> items) {
        if (avatarCornersCell == null) {
            avatarCornersCell = new AvatarCornersPreviewCell(getContext(), this);
        }
        items.add(UItem.asCustom(avatarCornersPreviewRow, avatarCornersCell));
        items.add(SettingsHelper.asSwitchCG(forumAvatarsRow,
                        getString(R.string.NM_ForumAvatarsLikeChats),
                        getString(R.string.NM_ForumAvatarsLikeChats_Desc))
                .setChecked(AppConfig.forumAvatarsLikeChats)
        );
        items.add(SettingsHelper.asSwitchCG(onlineIndicatorRow,
                        getString(R.string.NM_OnlineIndicatorInGroups),
                        getString(R.string.NM_OnlineIndicatorInGroups_Desc))
                .setChecked(AppConfig.onlineIndicatorInGroups));
        if (stickerSizeCell == null) {
            stickerSizeCell = new StickerSizeCell(getContext(), this);
        }
        items.add(UItem.asCustom(stickerSizePreviewRow, stickerSizeCell));
        items.add(SettingsHelper.asSwitchCG(hideStickerTimeRow, getString(R.string.NM_TimeOnStick))
                .setChecked(AppConfig.hideStickerTime));
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == openNavigationRow) {
            presentFragment(new AppearancePreferencesActivity(PAGE_NAVIGATION));
            return;
        } else if (item.id == openInterfaceRow) {
            presentFragment(new AppearancePreferencesActivity(PAGE_INTERFACE));
            return;
        } else if (item.id == openChatRow) {
            presentFragment(new AppearancePreferencesActivity(PAGE_CHAT));
            return;
        } else if (item.id == openAvatarsRow) {
            presentFragment(new AppearancePreferencesActivity(PAGE_AVATARS));
            return;
        } else if (item.id == centerTitleRow) {

            if (getActionBar() != null) {
                getActionBar().prepareCenterTitleAnimation();
            }
            AppConfig.toggleCenterTitle();
            updateCheckState(view, AppConfig.centerTitle);
            if (getActionBar() != null) {
                getActionBar().requestLayout();
            }

            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
            return;
        } else  if (item.id == hideSearchBar) {
            AppConfig.toggleHideSearchBar();
            updateCheckState(view, AppConfig.hideSearchBar);

            getNotificationCenter().postNotificationName(NotificationCenter.cgUpdateSearchFiledVisibility);
        } else if (item.id == snowflakesRow) {
            AppConfig.toggleDrawSnowInActionBar();
            updateCheckState(view, AppConfig.drawSnowInActionBar);

            showRestartBulletin();
        } else if (item.id == iconPackRow) {
            presentFragment(new IconPackSelectorActivity());   // dedicated screen with live previews
        } else if (item.id == oneUISwitchesRow) {
            ArrayList<CharSequence> opts = new ArrayList<>();
            opts.add(getString(R.string.Default));
            opts.add("One UI");
            opts.add("MD3");
            PopupHelper.show(opts, getString(R.string.NM_SwitchStyle),
                    AppConfig.switchStyle, getContext(), i -> {
                        AppConfig.setSwitchStyle(i);
                        SettingsHelper.updateButtonValue(view, getSwitchStyleValueText());

                        if (listView != null) {
                            for (int k = 0; k < listView.getChildCount(); k++) {
                                View c = listView.getChildAt(k);
                                if (c != null) c.invalidate();
                            }
                        }
                        if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);

                        showRestartBulletin();
                    });
        } else if (item.id == disableDividersRow) {
            AppConfig.toggleDisableDividers();
            item.checked = AppConfig.disableDividers;
            updateCheckState(view, AppConfig.disableDividers);

            Theme.applyCommonTheme();
            updateItemsAfterToggle();

            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == glareOnElementsRow) {
            AppConfig.toggleGlareOnElements();
            updateCheckState(view, AppConfig.glareOnElements);

            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == mediaGlowRow) {
            AppConfig.toggleMediaGlow();
            item.checked = AppConfig.mediaGlow;
            updateCheckState(view, AppConfig.mediaGlow);
            updateItemsAfterToggle();   // show/hide strength/blur/transition rows
        } else if (item.id == forumAvatarsRow) {
            AppConfig.toggleForumAvatarsLikeChats();
            updateCheckState(view, AppConfig.forumAvatarsLikeChats);

            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == forceBlurRow) {
            AppConfig.toggleForceBlur();
            updateCheckState(view, AppConfig.forceBlur);
            showRestartBulletin();
        } else if (item.id == enhancedGlassBlurRow) {
            AppConfig.toggleEnhancedGlassBlur();
            updateCheckState(view, AppConfig.enhancedGlassBlur);
            Theme.applyCommonTheme();
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == motionBlurRow) {
            AppConfig.toggleMotionBlur();
            updateCheckState(view, AppConfig.motionBlur);
        } else if (item.id == hideStatusRow) {
            AppConfig.toggleHideActionBarStatus();
            updateCheckState(view, AppConfig.hideActionBarStatus);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == iosStyleComposerRow) {
            AppConfig.toggleIosStyleComposer();
            updateCheckState(view, AppConfig.iosStyleComposer);

            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
        } else if (item.id == hideBubbleTailRow) {
            AppConfig.toggleHideBubbleTail();
            updateCheckState(view, AppConfig.hideBubbleTail);

            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.nmUpdateBubbleShape);
        } else if (item.id == onlineIndicatorRow) {
            AppConfig.toggleOnlineIndicatorInGroups();
            updateCheckState(view, AppConfig.onlineIndicatorInGroups);

            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.nmUpdateOnlineIndicator);
        } else if (item.id == hideStickerTimeRow) {
            AppConfig.toggleHideStickerTime();
            updateCheckState(view, AppConfig.hideStickerTime);

            if (stickerSizeCell != null) stickerSizeCell.refreshPreview();
        } else if (item.id == customTitleRow) {
            showCustomTitleDialog(view);
        } else if (item.id == foldersRow) {
            presentFragment(new FoldersPreferencesActivity());
        } else if (item.id == bottomTabsRow) {
            presentFragment(new BottomTabsPreferencesActivity());
        } else if (item.id == messagesAndProfilesRow) {
            presentFragment(new MessagesAndProfilesPreferencesActivity());
        } else if (item.id == messageSizeRow) {
            AlertDialogSwitchers.showMessageSize(this);
        } else if (item.id == centerChatTitleRow) {
            AppConfig.toggleCenterChatTitle();
            updateCheckState(view, AppConfig.centerChatTitle);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == unreadBadgeRow) {
            AppConfig.toggleUnreadBadgeOnBackButton();
            updateCheckState(view, AppConfig.unreadBadgeOnBackButton);
        } else if (item.id == customWallpapersRow) {
            AppConfig.toggleCustomWallpapers();
            updateCheckState(view, AppConfig.customWallpapers);
        } else if (item.id == chatSnowflakesRow) {
            AppConfig.toggleDrawSnowInChat();
            updateCheckState(view, AppConfig.drawSnowInChat);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == hideMuteButtonRow) {
            AppConfig.toggleHideMuteUnmuteButton();
            updateCheckState(view, AppConfig.hideMuteUnmuteButton);
        } else if (item.id == weekdayNearDateRow) {
            AppConfig.toggleWeekdayNearDate();
            updateCheckState(view, AppConfig.weekdayNearDate);
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {

        return false;
    }

    private String getIconPackValueText()  {
        return switch (AppConfig.iconReplacement) {
            case AppConfig.ICON_REPLACE_SOLAR -> getString(R.string.NM_IconPack_SolarTitle);
            case AppConfig.ICON_REPLACE_LIQUID_GLASS -> getString(R.string.NM_IconPack_LiquidTitle);
            case AppConfig.ICON_REPLACE_PLUMPY -> getString(R.string.NM_IconPack_PlumpyTitle);
            default -> getString(R.string.Default);
        };
    }

    private String getSwitchStyleValueText() {
        return switch (AppConfig.switchStyle) {
            case AppConfig.SWITCH_STYLE_ONEUI -> "One UI";
            case AppConfig.SWITCH_STYLE_MD3 -> "MD3";
            default -> getString(R.string.Default);
        };
    }

    private String getCustomTitleValueText() {
        return AppConfig.customTitleEnabled && !AppConfig.customTitleText.trim().isEmpty()
                ? AppConfig.customTitleText : getString(R.string.Default);
    }

    private void showCustomTitleDialog(View view) {
        Context ctx = getContext();
        if (ctx == null) return;
        EditText input = new EditText(ctx);
        input.setText(AppConfig.customTitleText);
        input.setHint(getString(R.string.AppName));
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setSingleLine(true);
        FrameLayout container = new FrameLayout(ctx);
        container.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), AndroidUtilities.dp(4));
        container.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(getString(R.string.NM_CustomTitle));
        b.setView(container);
        b.setPositiveButton(getString(R.string.OK), (d, w) -> {
            String t = input.getText().toString().trim();
            AppConfig.setCustomTitle(!t.isEmpty(), t);
            SettingsHelper.updateButtonValue(view, getCustomTitleValueText());
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        });
        b.setNegativeButton(getString(R.string.Cancel), null);
        b.show();
    }

}
