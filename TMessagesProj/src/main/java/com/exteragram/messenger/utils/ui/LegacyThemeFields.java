package com.exteragram.messenger.utils.ui;
public abstract class LegacyThemeFields {
    public static TextPaint chat_timePaint;
    protected static android.text.TextPaint createChatTimePaint(int flags) {
        return chat_timePaint = new TextPaint(flags);
    }
}
