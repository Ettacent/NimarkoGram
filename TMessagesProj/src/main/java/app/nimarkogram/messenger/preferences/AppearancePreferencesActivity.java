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

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class AppearancePreferencesActivity extends NimarkoUniversalPreferencesActivity {

    private final int centerTitleRow = 1;
    private final int hideSearchBar = 2;
    private final int snowflakesRow = 3;

    private final int iconPackRow = 4;
    private final int oneUISwitchesRow = 5;
    private final int disableDividersRow = 6;
    private final int glareOnElementsRow = 10;
    private final int forumAvatarsRow = 12;
    private final int forceBlurRow = 13;
    private final int hideStatusRow = 14;
    private final int customTitleRow = 15;
    private final int mediaGlowRow = 16;
    private final int disableSendHintsRow = 17;
    private final int hideBubbleTailRow = 18;
    private final int onlineIndicatorRow = 19;
    private final int hideStickerTimeRow = 20;
    private final int iosStyleComposerRow = 21;

    private final int foldersRow = 7;
    private final int bottomTabsRow = 8;
    private final int messagesAndProfilesRow = 9;

    private app.nimarkogram.messenger.preferences.components.AvatarCornersPreviewCell avatarCornersCell;
    private app.nimarkogram.messenger.preferences.components.StickerSizeCell stickerSizeCell;

    @Override
    protected CharSequence getTitle() {
         
        return getString(R.string.AP_Header_Appearance);
    }

    @Override
    public View createView(Context context) {
        setMD3(true);
        return super.createView(context);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        
        items.add(UItem.asHeader(getString(R.string.AP_Header_Appearance)));
        items.add(UItem.asButton(iconPackRow, getString(R.string.AP_IconReplacements), getIconPackValueText()));
        items.add(UItem.asButton(oneUISwitchesRow, getString(R.string.NM_SwitchStyle), getSwitchStyleValueText()));
        items.add(SettingsHelper.asSwitchCG(disableDividersRow, getString(R.string.AP_DisableDividers))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.disableDividers)
        );
        items.add(SettingsHelper.asSwitchCG(forceBlurRow, getString(R.string.NM_ForceBlur))
                .setChecked(NimarkoConfig.forceBlur));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.AP_Header)));
        items.add(SettingsHelper.asSwitchCG(centerTitleRow, getString(R.string.AP_CenterTitle))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.centerTitle)
        );
        items.add(SettingsHelper.asSwitchCG(hideSearchBar, getString(R.string.AP_HideSearchBar))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.hideSearchBar)
        );
        items.add(SettingsHelper.asSwitchCG(hideStatusRow, getString(R.string.NM_HideActionBarStatus))
                .setChecked(NimarkoConfig.hideActionBarStatus));
        items.add(SettingsHelper.asSwitchCG(snowflakesRow, getString(R.string.CP_Snowflakes_Header))
                .setChecked(app.nimarkogram.messenger.NimarkoConfig.drawSnowInActionBar)
        );
        items.add(UItem.asButton(customTitleRow, getString(R.string.NM_CustomTitle), getCustomTitleValueText()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_Chat_Section)));
        items.add(SettingsHelper.asSwitchCG(iosStyleComposerRow,
                        getString(R.string.NM_IOSStyleComposer),
                        getString(R.string.NM_IOSStyleComposer_Desc))
                .setChecked(NimarkoConfig.iosStyleComposer));
        items.add(SettingsHelper.asSwitchCG(hideBubbleTailRow,
                        getString(R.string.NM_HideBubbleTail),
                        getString(R.string.NM_HideBubbleTail_Desc))
                .setChecked(NimarkoConfig.hideBubbleTail));
        items.add(SettingsHelper.asSwitchCG(onlineIndicatorRow,
                        getString(R.string.NM_OnlineIndicatorInGroups),
                        getString(R.string.NM_OnlineIndicatorInGroups_Desc))
                .setChecked(NimarkoConfig.onlineIndicatorInGroups));
        items.add(SettingsHelper.asSwitchCG(disableSendHintsRow,
                        getString(R.string.NM_DisableSendHints),
                        getString(R.string.NM_DisableSendHints_Desc))
                .setChecked(NimarkoConfig.disableSendHints));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_Effects_Header)));
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
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_AvatarCorners_Header)));
        if (avatarCornersCell == null) {
            avatarCornersCell = new app.nimarkogram.messenger.preferences.components.AvatarCornersPreviewCell(getContext(), this);
        }
        items.add(UItem.asCustom(avatarCornersCell));
        items.add(SettingsHelper.asSwitchCG(forumAvatarsRow,
                        getString(R.string.NM_ForumAvatarsLikeChats),
                        getString(R.string.NM_ForumAvatarsLikeChats_Desc))
                .setChecked(NimarkoConfig.forumAvatarsLikeChats)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_Stickers_Header)));
        if (stickerSizeCell == null) {
            stickerSizeCell = new app.nimarkogram.messenger.preferences.components.StickerSizeCell(getContext(), this);
        }
        items.add(UItem.asCustom(stickerSizeCell));
        items.add(SettingsHelper.asSwitchCG(hideStickerTimeRow, getString(R.string.CP_TimeOnStick))
                .setChecked(NimarkoConfig.hideStickerTime));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.LocalMiscellaneousCache)));
        items.add(UItem.asButton(foldersRow, R.drawable.msg_folders, getString(R.string.CP_Filters_Header)));
        items.add(UItem.asButton(bottomTabsRow, R.drawable.tabs_reorder, getString(R.string.CP_MainTabs_Header)));
        items.add(UItem.asButton(messagesAndProfilesRow, R.drawable.msg_customize, getString(R.string.CP_ProfileReplyBackground)));
        items.add(UItem.asShadow(null));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == centerTitleRow) {
            
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
        } else if (item.id == disableSendHintsRow) {
            NimarkoConfig.toggleDisableSendHints();
            updateCheckState(view, NimarkoConfig.disableSendHints);
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
