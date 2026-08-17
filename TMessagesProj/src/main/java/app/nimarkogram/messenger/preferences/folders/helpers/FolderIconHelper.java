/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.preferences.folders.helpers;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

import java.util.LinkedHashMap;

import app.nimarkogram.messenger.NimarkoConfig;

public class FolderIconHelper {

    public static LinkedHashMap<String, Integer> folderIcons = new LinkedHashMap<>();

    static {
        folderIcons.put("🐱", R.drawable.filter_cat_solar);
        folderIcons.put("📕", R.drawable.filter_book_solar);
        folderIcons.put("💰", R.drawable.filter_money_solar);
        folderIcons.put("🎮", R.drawable.filter_game_solar);
        folderIcons.put("💡", R.drawable.filter_light_solar);
        folderIcons.put("👌", R.drawable.filter_like_solar);
        folderIcons.put("🎵", R.drawable.filter_note_solar);
        folderIcons.put("🎨", R.drawable.filter_palette_solar);
        folderIcons.put("✈", R.drawable.filter_travel_solar);
        folderIcons.put("⚽", R.drawable.filter_sport_solar);
        folderIcons.put("⭐", R.drawable.filter_favorite_solar);
        folderIcons.put("🎓", R.drawable.filter_study_solar);
        folderIcons.put("🛫", R.drawable.filter_airplane);
        folderIcons.put("👤", R.drawable.filter_private_solar);
        folderIcons.put("👥", R.drawable.filter_groups);
        folderIcons.put("💬", R.drawable.filter_all);
        folderIcons.put("✅", R.drawable.filter_unread);
        folderIcons.put("🤖", R.drawable.filter_bots_solar);
        folderIcons.put("👑", R.drawable.filter_crown);
        folderIcons.put("🌹", R.drawable.filter_flower_solar);
        folderIcons.put("🏠", R.drawable.filter_home_solar);
        folderIcons.put("❤", R.drawable.filter_love_solar);
        folderIcons.put("🎭", R.drawable.filter_mask_solar);
        folderIcons.put("🍸", R.drawable.filter_party_solar);
        folderIcons.put("📈", R.drawable.filter_trade_solar);
        folderIcons.put("💼", R.drawable.filter_work_solar);
        folderIcons.put("🔔", R.drawable.filter_unmuted_solar);
        folderIcons.put("📢", R.drawable.filter_channel);
        folderIcons.put("📁", R.drawable.filter_custom_solar);
        folderIcons.put("📋", R.drawable.filter_setup_solar);
    }

    public static String[] getEmoticonData(int newFilterFlags) {
        int flags = newFilterFlags & MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        String newName = "";
        String newEmoticon = "";
        if ((flags & MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS) == MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS) {
            if ((newFilterFlags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) != 0) {
                newName = getString(R.string.FilterNameUnread);
                newEmoticon = "✅";
            } else if ((newFilterFlags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) != 0) {
                newName = getString(R.string.FilterNameNonMuted);
                newEmoticon = "🔔";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_CONTACTS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_CONTACTS;
            if (flags == 0) {
                newName = getString(R.string.FilterContacts);
                newEmoticon = "👤";
            } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) != 0) {
                flags &= ~MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
                if (flags == 0) {
                    newName = getString(R.string.FilterContacts);
                    newEmoticon = "👤";
                }
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
            if (flags == 0) {
                newName = getString(R.string.FilterNonContacts);
                newEmoticon = "👤";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_GROUPS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_GROUPS;
            if (flags == 0) {
                newName = getString(R.string.FilterGroups);
                newEmoticon = "👥";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_BOTS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_BOTS;
            if (flags == 0) {
                newName = getString(R.string.FilterBots);
                newEmoticon = "🤖";
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_CHANNELS) != 0) {
            flags &= ~MessagesController.DIALOG_FILTER_FLAG_CHANNELS;
            if (flags == 0) {
                newName = getString(R.string.FilterChannels);
                newEmoticon = "📢";
            }
        }
        return new String[]{newName, newEmoticon};
    }

    public static int getIconWidth() {
        return AndroidUtilities.dp(28);
    }

    public static int getPadding() {
        
        if (NimarkoConfig.tabMode == NimarkoConfig.TAB_TYPE_MIX) {
            return AndroidUtilities.dp(3);
        }
        return 0;
    }

    public static int getTotalIconWidth() {
        int result = 0;
        if (NimarkoConfig.tabMode != NimarkoConfig.TAB_TYPE_TEXT) {
            result = getIconWidth() + getPadding();
        }
        return result;
    }

    public static int getPaddingTabWidth() {
        return 24;
    }

    public static int getInternalPaddingTab() {
        return (int) 12.5f;
    }

    public static int getTabIcon(String emoji) {
        if (emoji != null) {
            var folderIcon = folderIcons.get(emoji);
            if (folderIcon != null) {
                return folderIcon;
            }
        }
        return R.drawable.filter_custom_solar;
    }

}
