package com.exteragram.messenger.utils.ui;

import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.SystemFonts;
import android.os.Build;

import org.telegram.messenger.ApplicationLoader;

import java.io.File;

public abstract class FontUtils {

    public static volatile boolean loadSystemEmojiFailed;

    private static volatile Boolean mediumWeightSupported;
    private static volatile Boolean italicSupported;
    private static volatile boolean emojiPathResolved;
    private static volatile File systemEmojiFontPath;
    private static volatile Typeface systemEmojiTypeface;

    public FontUtils() {
    }

    public static Typeface getTypeface(String name) {
        return app.nimarkogram.messenger.utils.ui.FontUtils.getTypeface(name);
    }

    public static Typeface getTypefaceFromFile(String path) {
        return app.nimarkogram.messenger.utils.ui.FontUtils.getTypefaceFromFile(path);
    }

    public static Typeface getSystemTypeface(String family) {
        try {
            Typeface typeface = Typeface.create(family, Typeface.NORMAL);
            return typeface != null ? typeface : Typeface.DEFAULT;
        } catch (Throwable ignored) {
            return Typeface.DEFAULT;
        }
    }

    public static boolean isMediumWeightSupported() {
        Boolean cached = mediumWeightSupported;
        if (cached != null) {
            return cached;
        }
        synchronized (FontUtils.class) {
            if (mediumWeightSupported == null) {
                mediumWeightSupported = canCreateTypeface("sans-serif-medium", Typeface.NORMAL);
            }
            return mediumWeightSupported;
        }
    }

    public static boolean isItalicSupported() {
        Boolean cached = italicSupported;
        if (cached != null) {
            return cached;
        }
        synchronized (FontUtils.class) {
            if (italicSupported == null) {
                italicSupported = canCreateTypeface("sans-serif", Typeface.ITALIC);
            }
            return italicSupported;
        }
    }

    private static boolean canCreateTypeface(String family, int style) {
        try {
            Typeface typeface = Typeface.create(family, style);
            return typeface != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static File getSystemEmojiFontPath() {
        if (emojiPathResolved) {
            return systemEmojiFontPath;
        }
        synchronized (FontUtils.class) {
            if (!emojiPathResolved) {
                systemEmojiFontPath = findSystemEmojiFontPath();
                emojiPathResolved = true;
            }
            return systemEmojiFontPath;
        }
    }

    private static File findSystemEmojiFontPath() {
        
        String[] fallbacks = {
                "/system/fonts/SamsungColorEmoji.ttf",
                "/system/fonts/NotoColorEmoji.ttf",
                "/system/fonts/AndroidEmoji.ttf"
        };
        for (String path : fallbacks) {
            File file = new File(path);
            if (file.isFile()) {
                return file;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                File fallback = null;
                for (Font font : SystemFonts.getAvailableFonts()) {
                    File file = font.getFile();
                    if (file == null) {
                        continue;
                    }
                    String name = file.getName().toLowerCase(java.util.Locale.ROOT);
                    if (name.contains("samsungcoloremoji")) {
                        return file;
                    }
                    if (fallback == null && name.contains("emoji")) {
                        fallback = file;
                    }
                }
                if (fallback != null) {
                    return fallback;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static Typeface getSystemEmojiTypeface() {
        Typeface cached = systemEmojiTypeface;
        if (cached != null || loadSystemEmojiFailed) {
            return cached;
        }
        synchronized (FontUtils.class) {
            if (systemEmojiTypeface == null && !loadSystemEmojiFailed) {
                try {
                    File file = getSystemEmojiFontPath();
                    if (file != null) {
                        systemEmojiTypeface = Typeface.createFromFile(file);
                    }
                } catch (Throwable ignored) {
                }
                loadSystemEmojiFailed = systemEmojiTypeface == null;
            }
            return systemEmojiTypeface;
        }
    }

    public static Typeface getFontFromAssets(String path) {
        try {
            if (path != null && ApplicationLoader.applicationContext != null) {
                return Typeface.createFromAsset(
                        ApplicationLoader.applicationContext.getAssets(), path);
            }
        } catch (Throwable ignored) {
        }
        return getTypeface(path);
    }
}
