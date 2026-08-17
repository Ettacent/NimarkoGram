package app.nimarkogram.messenger.utils.ui;

import androidx.core.util.Pair;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

import java.util.LinkedHashMap;

public abstract class FolderIcons {

    public static final LinkedHashMap<String, Integer> folderIcons = new LinkedHashMap<>();

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

    public static Pair<String, String> getEmoticonFromFlags(int flags) {
        final int allChats = MessagesController.DIALOG_FILTER_FLAG_ALL_CHATS;
        final int contacts = MessagesController.DIALOG_FILTER_FLAG_CONTACTS;
        final int nonContacts = MessagesController.DIALOG_FILTER_FLAG_NON_CONTACTS;
        final int groups = MessagesController.DIALOG_FILTER_FLAG_GROUPS;
        final int bots = MessagesController.DIALOG_FILTER_FLAG_BOTS;
        final int channels = MessagesController.DIALOG_FILTER_FLAG_CHANNELS;

        String title = "";
        String emoticon = "";

        int typeFlags = flags & allChats;
        if ((typeFlags & allChats) != allChats) {
            
            if ((typeFlags & contacts) != 0) {
                int rest = typeFlags & ~contacts;
                if (rest == 0 || ((rest & nonContacts) != 0 && (rest & ~nonContacts) == 0)) {
                    title = LocaleController.getString(R.string.FilterContacts);
                    emoticon = "👤"; 
                }
            } else if ((typeFlags & nonContacts) != 0) {
                if ((typeFlags & ~nonContacts) == 0) {
                    title = LocaleController.getString(R.string.FilterNonContacts);
                    emoticon = "👤"; 
                }
            } else if ((typeFlags & groups) != 0) {
                if ((typeFlags & ~groups) == 0) {
                    title = LocaleController.getString(R.string.FilterGroups);
                    emoticon = "👥"; 
                }
            } else if ((typeFlags & bots) != 0) {
                if ((typeFlags & ~bots) == 0) {
                    title = LocaleController.getString(R.string.FilterBots);
                    emoticon = "🤖"; 
                }
            } else if ((typeFlags & channels) != 0) {
                if ((typeFlags & ~channels) == 0) {
                    title = LocaleController.getString(R.string.FilterChannels);
                    emoticon = "📢"; 
                }
            }
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_READ) != 0) {
            title = LocaleController.getString(R.string.FilterNameUnread);
            emoticon = "✅"; 
        } else if ((flags & MessagesController.DIALOG_FILTER_FLAG_EXCLUDE_MUTED) != 0) {
            title = LocaleController.getString(R.string.FilterNameNonMuted);
            emoticon = "🔔"; 
        }

        return Pair.create(title, emoticon);
    }

    public static int getIconWidth() {
        return AndroidUtilities.dp(24.0f);
    }

    public static int getPadding() {
        
        return AndroidUtilities.dp(4.0f);
    }

    public static int getPaddingTab() {
        return AndroidUtilities.dp(24.0f);
    }

    public static int getTabIcon(String emoticon) {
        if (emoticon == null) {
            return R.drawable.filter_custom_solar;
        }
        Integer res = folderIcons.get(emoticon);
        return res == null ? R.drawable.filter_custom_solar : res;
    }

    public static int getTotalIconWidth() {
        
        return getIconWidth() + getPadding();
    }
}
