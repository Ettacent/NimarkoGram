package app.nimarkogram.messenger.ai;

import app.nimarkogram.messenger.ai.data.Role;

import java.util.ArrayList;

public abstract class AiConfig {

    public static volatile boolean showResponseOnly = false;
     
    public static volatile boolean insertAsQuote = true;
     
    public static volatile boolean saveHistory = true;
     
    public static volatile boolean responseStreaming = true;

    protected AiConfig() {
    }

    public static boolean getShowResponseOnly() {
        return showResponseOnly;
    }

    public static void setShowResponseOnly(boolean value) {
        showResponseOnly = value;
    }

    public static boolean getInsertAsQuote() {
        return insertAsQuote;
    }

    public static void setInsertAsQuote(boolean value) {
        insertAsQuote = value;
    }

    public static boolean getSaveHistory() {
        return saveHistory;
    }

    public static void setSaveHistory(boolean value) {
        saveHistory = value;
    }

    public static boolean getResponseStreaming() {
        return responseStreaming;
    }

    public static void setResponseStreaming(boolean value) {
        responseStreaming = value;
    }

    public static String getSelectedRole() {
        return "";
    }

    public static void setSelectedRole(String name) {
        
    }

    public static void setSelectedAiRole(Role role) {
        
    }

    public static int getSelectedServiceHash() {
        return 0;
    }

    public static void setSelectedServiceHash(int hash) {
        
    }

    public static ArrayList<Role> getRoles() {
        return new ArrayList<>();
    }

    public static void saveRoles(ArrayList<Role> roles) {
        
    }

    public static void clearConversationHistory() {
        
    }

    public static void removeLastFromHistory() {
        
    }
}
