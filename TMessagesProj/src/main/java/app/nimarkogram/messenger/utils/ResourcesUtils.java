package app.nimarkogram.messenger.utils;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.URLSpan;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.URLSpanNoUnderline;

import java.util.Date;
import java.util.Locale;

public final class ResourcesUtils {

    private ResourcesUtils() {}

    public static String getDCGeo(int dcId) {
        switch (dcId) {
            case 1:
            case 3:
                return "USA (Miami)";
            case 2:
            case 4:
                return "NLD (Amsterdam)";
            case 5:
                return "SGP (Singapore)";
            default:
                return "UNK (Unknown)";
        }
    }

    public static String getDCName(int dc) {
        switch (dc) {
            case 1: return "Pluto";
            case 2: return "Venus";
            case 3: return "Aurora";
            case 4: return "Vesta";
            case 5: return "Flora";
            default: return LocaleController.getString(R.string.NumberUnknown);
        }
    }

    public static String createDateAndTime(long date) {
        try {
            long dateAndTime = date * 1000L;
            return String.format(
                    "%1$s | %2$s",
                    LocaleController.getInstance().getFormatterYear().format(new Date(dateAndTime)),
                    LocaleController.getInstance().getFormatterDay().format(new Date(dateAndTime))
            );
        } catch (Exception ignored) {}
        return "LOC_ERR";
    }

    public static String createDateAndTimeForJSON(long date) {
        try {
            long dateAndTime = date * 1000L;
            return String.format(
                    "%1$s | %2$s",
                    LocaleController.getInstance().getFormatterYear().format(new Date(dateAndTime)),
                    LocaleController.getInstance().getFormatterDayWithSeconds().format(new Date(dateAndTime))
            );
        } catch (Exception ignored) {}
        return "LOC_ERR";
    }

    public static String capitalize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        return text.substring(0, 1).toUpperCase(Locale.getDefault()) + text.substring(1);
    }

    public static CharSequence getUrlNoUnderlineText(CharSequence charSequence) {
        Spannable spannable = new SpannableString(charSequence);
        URLSpan[] spans = spannable.getSpans(0, charSequence.length(), URLSpan.class);
        for (URLSpan urlSpan : spans) {
            int start = spannable.getSpanStart(urlSpan);
            int end = spannable.getSpanEnd(urlSpan);
            spannable.removeSpan(urlSpan);
            URLSpan replacement = new URLSpanNoUnderline(urlSpan.getURL()) {};
            spannable.setSpan(replacement, start, end, 0);
        }
        return spannable;
    }
}
