package app.nimarkogram.messenger.utils.ui;

import android.graphics.Typeface;

import java.io.File;

import org.telegram.messenger.AndroidUtilities;

public final class FontUtils {

    private FontUtils() {
    }

    public static Typeface getTypeface(String name) {
        if (name == null || name.length() == 0) {
            return Typeface.DEFAULT;
        }
        
        try {
            Typeface tf = AndroidUtilities.getTypeface(name);
            if (tf != null) {
                return tf;
            }
        } catch (Throwable ignored) {
        }
        
        try {
            File file = new File(name);
            if (file.exists() && file.isFile()) {
                Typeface tf = Typeface.createFromFile(file);
                if (tf != null) {
                    return tf;
                }
            }
        } catch (Throwable ignored) {
        }
        
        try {
            Typeface tf = Typeface.create(name, Typeface.NORMAL);
            if (tf != null) {
                return tf;
            }
        } catch (Throwable ignored) {
        }
        return Typeface.DEFAULT;
    }

    public static Typeface getTypefaceFromFile(String path) {
        try {
            if (path == null) {
                return null;
            }
            File file = new File(path);
            if (file.exists() && file.isFile()) {
                return Typeface.createFromFile(file);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
