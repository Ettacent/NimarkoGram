package com.exteragram.messenger;

public enum MainMenuItem {
    DIVIDER(-1),
    PROFILE(18),
    ARCHIVE(14),
    BOTS(105),
    NEW_GROUP(2),
    CONTACTS(6),
    NEW_CHANNEL(3),
    CALLS(10),
    SAVED(11),
    SETTINGS(8),
    PLUGINS(102),
    BROWSER(101),
    QR(17);

    private final int id;

    MainMenuItem(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public static MainMenuItem getById(int id) {
        for (MainMenuItem item : values()) {
            if (item.getId() == id) {
                return item;
            }
        }
        return null;
    }
}
