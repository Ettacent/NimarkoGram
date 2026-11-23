/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.messenger;

public final class AndroidContactIdentity {
    public static final String TYPE = BuildConfig.CONTACTS_NAMESPACE;
    private static final String MIME = "vnd.android.cursor.item/vnd." + TYPE + ".android.";
    public static final String PROFILE = MIME + "profile";
    public static final String CALL = MIME + "call";
    public static final String VIDEO_CALL = MIME + "call.video";
    public static final String OPEN_ACCOUNT = TYPE + ".OPEN_ACCOUNT";

    public static boolean ownsMime(String mime) {
        return PROFILE.equals(mime) || CALL.equals(mime) || VIDEO_CALL.equals(mime);
    }

    private AndroidContactIdentity() {}
}
