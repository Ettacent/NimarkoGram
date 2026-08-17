package com.exteragram.messenger.ai;

import com.exteragram.messenger.ai.data.Role;

import java.util.ArrayList;

public abstract class AiConfig {

    public static volatile boolean showResponseOnly = app.nimarkogram.messenger.ai.AiConfig.showResponseOnly;
     
    public static volatile boolean insertAsQuote = app.nimarkogram.messenger.ai.AiConfig.insertAsQuote;
     
    public static volatile boolean saveHistory = app.nimarkogram.messenger.ai.AiConfig.saveHistory;
     
    public static volatile boolean responseStreaming = app.nimarkogram.messenger.ai.AiConfig.responseStreaming;

    private AiConfig() {
    }

    public static boolean getShowResponseOnly() {
        return app.nimarkogram.messenger.ai.AiConfig.getShowResponseOnly();
    }

    public static void setShowResponseOnly(boolean value) {
        showResponseOnly = value;
        app.nimarkogram.messenger.ai.AiConfig.setShowResponseOnly(value);
    }

    public static boolean getInsertAsQuote() {
        return app.nimarkogram.messenger.ai.AiConfig.getInsertAsQuote();
    }

    public static void setInsertAsQuote(boolean value) {
        insertAsQuote = value;
        app.nimarkogram.messenger.ai.AiConfig.setInsertAsQuote(value);
    }

    public static boolean getSaveHistory() {
        return app.nimarkogram.messenger.ai.AiConfig.getSaveHistory();
    }

    public static void setSaveHistory(boolean value) {
        saveHistory = value;
        app.nimarkogram.messenger.ai.AiConfig.setSaveHistory(value);
    }

    public static boolean getResponseStreaming() {
        return app.nimarkogram.messenger.ai.AiConfig.getResponseStreaming();
    }

    public static void setResponseStreaming(boolean value) {
        responseStreaming = value;
        app.nimarkogram.messenger.ai.AiConfig.setResponseStreaming(value);
    }

    public static String getSelectedRole() {
        return app.nimarkogram.messenger.ai.AiConfig.getSelectedRole();
    }

    public static void setSelectedRole(String name) {
        app.nimarkogram.messenger.ai.AiConfig.setSelectedRole(name);
    }

    public static void setSelectedAiRole(Role role) {
        app.nimarkogram.messenger.ai.AiConfig.setSelectedAiRole(role);
    }

    public static int getSelectedServiceHash() {
        return app.nimarkogram.messenger.ai.AiConfig.getSelectedServiceHash();
    }

    public static void setSelectedServiceHash(int hash) {
        app.nimarkogram.messenger.ai.AiConfig.setSelectedServiceHash(hash);
    }

    public static ArrayList<Role> getRoles() {
        
        return new ArrayList<>();
    }

    public static void saveRoles(ArrayList<Role> roles) {
        
        app.nimarkogram.messenger.ai.AiConfig.saveRoles(new ArrayList<>());
    }

    public static void clearConversationHistory() {
        app.nimarkogram.messenger.ai.AiConfig.clearConversationHistory();
    }

    public static void removeLastFromHistory() {
        app.nimarkogram.messenger.ai.AiConfig.removeLastFromHistory();
    }
}
