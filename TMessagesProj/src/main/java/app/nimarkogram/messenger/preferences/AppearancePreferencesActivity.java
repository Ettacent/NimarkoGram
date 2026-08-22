/*
 * This file is part of NimarkoGram for Android.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 * Copyright Ettacent, 2026.
 */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
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

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.helpers.NimarkoAlertDialogSwitchers;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class AppearancePreferencesActivity extends NimarkoUniversalPreferencesActivity {

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

    private static final int foldersRow = 7;
    private static final int bottomTabsRow = 8;
    private static final int messagesAndProfilesRow = 9;

    private app.nimarkogram.messenger.preferences.components.AvatarCornersPreviewCell avatarCornersCell;
    private app.nimarkogram.messenger.preferences.components.StickerSizeCell stickerSizeCell;

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
                    glareOnElementsRow, forceBlurRow, mediaGlowRow, chatSnowflakesRow -> PAGE_INTERFACE;
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
        return super.createView(context);
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
                R.drawable.msg_folders, getString(R.string.CP_Filters_Header),
                getString(R.string.NM_SettingsSummaryFolders)));
        items.add(asSettingsLink(bottomTabsRow, IconBackgroundColors.PURPLE,
                R.drawable.tabs_reorder, getString(R.string.CP_MainTabs_Header),
                getString(R.string.NM_SettingsSummaryBottomTabs)));
    }

    private void fillInterface(ArrayList<UItem> items) {
        items.add(asSettingsValue(iconPackRow, IconBackgroundColors.PURPLE,
                R.drawable.msg_colors, getString(R.string.AP_IconReplacements), getIconPackValueText()));
        items.add(asSettingsValue(oneUISwitchesRow, IconBackgroundColors.GREEN,
                R.drawable.msg_hybrid, getString(R.string.NM_SwitchStyle), getSwitchStyleValueText()));
        items.add(SettingsHelper.asSwitchCG(disableDividersRow, getString(R.string.AP_DisableDividers))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.disableDividers)
        );
    }

    private void fillHeader(ArrayList<UItem> items) {
        items.add(asSettingsValue(customTitleRow, IconBackgroundColors.ORANGE,
                R.drawable.msg_jobtitle, getString(R.string.NM_CustomTitle), getCustomTitleValueText()));
        items.add(SettingsHelper.asSwitchCG(centerTitleRow, getString(R.string.AP_CenterTitle))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.centerTitle)
        );
        items.add(SettingsHelper.asSwitchCG(hideSearchBar, getString(R.string.AP_HideSearchBar))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.hideSearchBar)
        );
        items.add(SettingsHelper.asSwitchCG(hideStatusRow, getString(R.string.NM_HideActionBarStatus))
                .setChecked(NimarkoConfig.hideActionBarStatus));
    }

    private void fillChat(ArrayList<UItem> items) {
        items.add(asSettingsLink(messagesAndProfilesRow, IconBackgroundColors.BLUE_DEEP,
                R.drawable.msg_contacts_name, getString(R.string.CP_ProfileReplyBackground),
                getString(R.string.NM_SettingsSummaryMessagesProfiles)));
        items.add(asSettingsLink(messageSizeRow, IconBackgroundColors.CYAN,
                R.drawable.msg_zoomin, getString(R.string.CP_Messages_Size)));
        items.add(SettingsHelper.asSwitchCG(iosStyleComposerRow,
                        getString(R.string.NM_IOSStyleComposer),
                        getString(R.string.NM_IOSStyleComposer_Desc))
                .setChecked(NimarkoConfig.iosStyleComposer));
        items.add(SettingsHelper.asSwitchCG(hideBubbleTailRow,
                        getString(R.string.NM_HideBubbleTail),
                        getString(R.string.NM_HideBubbleTail_Desc))
                .setChecked(NimarkoConfig.hideBubbleTail));
        items.add(SettingsHelper.asSwitchCG(centerChatTitleRow,
                        getString(R.string.NM_CP_CenterTitleInChat))
                .setChecked(NimarkoConfig.centerChatTitle));
        items.add(SettingsHelper.asSwitchCG(unreadBadgeRow,
                        getString(R.string.CP_UnreadBadgeOnBackButton),
                        getString(R.string.CP_UnreadBadgeOnBackButton_Desc))
                .setChecked(NimarkoConfig.unreadBadgeOnBackButton));
        items.add(SettingsHelper.asSwitchCG(customWallpapersRow,
                        getString(R.string.CP_CustomWallpapers),
                        getString(R.string.CP_CustomWallpapers_Desc))
                .setChecked(NimarkoConfig.customWallpapers));
        items.add(SettingsHelper.asSwitchCG(hideMuteButtonRow,
                        getString(R.string.CP_HideMuteUnmuteButton))
                .setChecked(NimarkoConfig.hideMuteUnmuteButton));
        items.add(SettingsHelper.asSwitchCG(weekdayNearDateRow,
                        getString(R.string.NM_CP_WeekdayNearDate),
                        getString(R.string.NM_CP_WeekdayNearDate_Desc))
                .setChecked(NimarkoConfig.weekdayNearDate));
    }

    private void fillEffects(ArrayList<UItem> items) {
        items.add(SettingsHelper.asSwitchCG(forceBlurRow, getString(R.string.NM_ForceBlur))
                .setChecked(NimarkoConfig.forceBlur));
        items.add(SettingsHelper.asSwitchCG(glareOnElementsRow,
                        getString(R.string.AP_GlareOnElements),
                        getString(R.string.AP_GlareOnElementsInfo))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.glareOnElements)
        );
        items.add(SettingsHelper.asSwitchCG(mediaGlowRow,
                        getString(R.string.NM_MediaGlow),
                        getString(R.string.NM_MediaGlow_Desc))
                .setChecked(NimarkoConfig.mediaGlow)
        );
        items.add(SettingsHelper.asSwitchCG(snowflakesRow, getString(R.string.CP_Snowflakes_Header))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.drawSnowInActionBar));
        items.add(SettingsHelper.asSwitchCG(chatSnowflakesRow, getString(R.string.NM_CP_SnowflakesInChat))
                .setChecked(NimarkoConfig.drawSnowInChat));
    }

    private void fillAvatarsAndStickers(ArrayList<UItem> items) {
        if (avatarCornersCell == null) {
            avatarCornersCell = new app.nimarkogram.messenger.preferences.components.AvatarCornersPreviewCell(getContext(), this);
        }
        items.add(UItem.asCustom(avatarCornersPreviewRow, avatarCornersCell));
        items.add(SettingsHelper.asSwitchCG(forumAvatarsRow,
                        getString(R.string.NM_ForumAvatarsLikeChats),
                        getString(R.string.NM_ForumAvatarsLikeChats_Desc))
                .setChecked(NimarkoConfig.forumAvatarsLikeChats)
        );
        items.add(SettingsHelper.asSwitchCG(onlineIndicatorRow,
                        getString(R.string.NM_OnlineIndicatorInGroups),
                        getString(R.string.NM_OnlineIndicatorInGroups_Desc))
                .setChecked(NimarkoConfig.onlineIndicatorInGroups));
        if (stickerSizeCell == null) {
            stickerSizeCell = new app.nimarkogram.messenger.preferences.components.StickerSizeCell(getContext(), this);
        }
        items.add(UItem.asCustom(stickerSizePreviewRow, stickerSizeCell));
        items.add(SettingsHelper.asSwitchCG(hideStickerTimeRow, getString(R.string.CP_TimeOnStick))
                .setChecked(NimarkoConfig.hideStickerTime));
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
            NimarkoConfig.toggleCenterTitle();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.centerTitle);
            if (getActionBar() != null) {
                getActionBar().requestLayout();
            }

            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
            return;
        } else  if (item.id == hideSearchBar) {
            NimarkoConfig.toggleHideSearchBar();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.hideSearchBar);

            getNotificationCenter().postNotificationName(NotificationCenter.cgUpdateSearchFiledVisibility);
        } else if (item.id == snowflakesRow) {
            NimarkoConfig.toggleDrawSnowInActionBar();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.drawSnowInActionBar);

            showRestartBulletin();
        } else if (item.id == iconPackRow) {
            presentFragment(new IconPackSelectorActivity());
        } else if (item.id == oneUISwitchesRow) {
            java.util.ArrayList<CharSequence> opts = new java.util.ArrayList<>();
            opts.add(getString(R.string.Default));
            opts.add("One UI");
            opts.add("MD3");
            app.nimarkogram.messenger.preferences.helpers.PopupHelper.show(opts, getString(R.string.NM_SwitchStyle),
                    NimarkoConfig.switchStyle, getContext(), i -> {
                        NimarkoConfig.setSwitchStyle(i);
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
            NimarkoConfig.toggleDisableDividers();
            updateCheckState(view, app.nimarkogram.messenger.NimarkoConfig.disableDividers);

            Theme.applyCommonTheme();
            listView.adapter.update(true);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == glareOnElementsRow) {
            NimarkoConfig.toggleGlareOnElements();
            updateCheckState(view, NimarkoConfig.glareOnElements);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == mediaGlowRow) {
            NimarkoConfig.toggleMediaGlow();
            updateCheckState(view, NimarkoConfig.mediaGlow);
            listView.adapter.update(true);
        } else if (item.id == forumAvatarsRow) {
            NimarkoConfig.toggleForumAvatarsLikeChats();
            updateCheckState(view, NimarkoConfig.forumAvatarsLikeChats);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == forceBlurRow) {
            NimarkoConfig.toggleForceBlur();
            updateCheckState(view, NimarkoConfig.forceBlur);
            Theme.applyCommonTheme();
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == hideStatusRow) {
            NimarkoConfig.toggleHideActionBarStatus();
            updateCheckState(view, NimarkoConfig.hideActionBarStatus);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == iosStyleComposerRow) {
            NimarkoConfig.toggleIosStyleComposer();
            updateCheckState(view, NimarkoConfig.iosStyleComposer);
            if (getParentLayout() != null) {
                getParentLayout().rebuildAllFragmentViews(false, false);
            }
        } else if (item.id == hideBubbleTailRow) {
            NimarkoConfig.toggleHideBubbleTail();
            updateCheckState(view, NimarkoConfig.hideBubbleTail);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.nmUpdateBubbleShape);
        } else if (item.id == onlineIndicatorRow) {
            NimarkoConfig.toggleOnlineIndicatorInGroups();
            updateCheckState(view, NimarkoConfig.onlineIndicatorInGroups);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.nmUpdateOnlineIndicator);
        } else if (item.id == hideStickerTimeRow) {
            NimarkoConfig.toggleHideStickerTime();
            updateCheckState(view, NimarkoConfig.hideStickerTime);
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
            NimarkoAlertDialogSwitchers.showMessageSize(this);
        } else if (item.id == centerChatTitleRow) {
            NimarkoConfig.toggleCenterChatTitle();
            updateCheckState(view, NimarkoConfig.centerChatTitle);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == unreadBadgeRow) {
            NimarkoConfig.toggleUnreadBadgeOnBackButton();
            updateCheckState(view, NimarkoConfig.unreadBadgeOnBackButton);
        } else if (item.id == customWallpapersRow) {
            NimarkoConfig.toggleCustomWallpapers();
            updateCheckState(view, NimarkoConfig.customWallpapers);
        } else if (item.id == chatSnowflakesRow) {
            NimarkoConfig.toggleDrawSnowInChat();
            updateCheckState(view, NimarkoConfig.drawSnowInChat);
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        } else if (item.id == hideMuteButtonRow) {
            NimarkoConfig.toggleHideMuteUnmuteButton();
            updateCheckState(view, NimarkoConfig.hideMuteUnmuteButton);
        } else if (item.id == weekdayNearDateRow) {
            NimarkoConfig.toggleWeekdayNearDate();
            updateCheckState(view, NimarkoConfig.weekdayNearDate);
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private String getIconPackValueText()  {
        return switch (app.nimarkogram.messenger.NimarkoConfig.iconReplacement) {
            case NimarkoConfig.ICON_REPLACE_SOLAR -> getString(R.string.AP_IconReplacement_Solar);
            case NimarkoConfig.ICON_REPLACE_LIQUID_GLASS -> getString(R.string.NM_IconPack_LiquidTitle);
            case NimarkoConfig.ICON_REPLACE_PLUMPY -> getString(R.string.NM_IconPack_PlumpyTitle);
            default -> getString(R.string.Default);
        };
    }

    private String getSwitchStyleValueText() {
        return switch (NimarkoConfig.switchStyle) {
            case NimarkoConfig.SWITCH_STYLE_ONEUI -> "One UI";
            case NimarkoConfig.SWITCH_STYLE_MD3 -> "MD3";
            default -> getString(R.string.Default);
        };
    }

    private String getCustomTitleValueText() {
        return NimarkoConfig.customTitleEnabled && !NimarkoConfig.customTitleText.trim().isEmpty()
                ? NimarkoConfig.customTitleText : getString(R.string.Default);
    }

    private void showCustomTitleDialog(View view) {
        Context ctx = getContext();
        if (ctx == null) return;
        EditText input = new EditText(ctx);
        input.setText(NimarkoConfig.customTitleText);
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
            NimarkoConfig.setCustomTitle(!t.isEmpty(), t);
            SettingsHelper.updateButtonValue(view, getCustomTitleValueText());
            if (getParentLayout() != null) getParentLayout().rebuildAllFragmentViews(false, false);
        });
        b.setNegativeButton(getString(R.string.Cancel), null);
        b.show();
    }

}
