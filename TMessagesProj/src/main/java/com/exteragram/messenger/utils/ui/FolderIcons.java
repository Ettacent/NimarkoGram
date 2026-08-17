package com.exteragram.messenger.utils.ui;

import androidx.core.util.Pair;

import java.util.LinkedHashMap;

public abstract class FolderIcons {

    public static final LinkedHashMap<String, Integer> folderIcons =
            app.nimarkogram.messenger.utils.ui.FolderIcons.folderIcons;

    public static Pair<String, String> getEmoticonFromFlags(int flags) {
        return app.nimarkogram.messenger.utils.ui.FolderIcons.getEmoticonFromFlags(flags);
    }

    public static int getIconWidth() {
        return app.nimarkogram.messenger.utils.ui.FolderIcons.getIconWidth();
    }

    public static int getPadding() {
        return app.nimarkogram.messenger.utils.ui.FolderIcons.getPadding();
    }

    public static int getPaddingTab() {
        return app.nimarkogram.messenger.utils.ui.FolderIcons.getPaddingTab();
    }

    public static int getTabIcon(String emoticon) {
        return app.nimarkogram.messenger.utils.ui.FolderIcons.getTabIcon(emoticon);
    }

    public static int getTotalIconWidth() {
        return app.nimarkogram.messenger.utils.ui.FolderIcons.getTotalIconWidth();
    }
}
