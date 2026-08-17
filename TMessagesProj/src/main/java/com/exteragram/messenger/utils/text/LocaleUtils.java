package com.exteragram.messenger.utils.text;

import android.graphics.Color;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.FilterCreateActivity;

import app.nimarkogram.messenger.utils.ui.ColorRectSpan;

public abstract class LocaleUtils {

    private static final Pattern HEX_PATTERN =
            Pattern.compile("(?<![a-zA-Z0-9])#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})(?![a-zA-Z0-9])");

    public static CharSequence formatWithUsernames(CharSequence charSequence) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.formatWithUsernames(charSequence);
    }

    public static CharSequence formatWithUsernames(CharSequence charSequence, BaseFragment baseFragment) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.formatWithUsernames(charSequence, baseFragment);
    }

    public static CharSequence formatWithUsernames(CharSequence charSequence, BaseFragment baseFragment, Runnable runnable) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.formatWithUsernames(charSequence, baseFragment, runnable);
    }

    public static CharSequence formatWithHtmlURLs(CharSequence charSequence) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.formatWithHtmlURLs(charSequence);
    }

    public static CharSequence formatWithURLs(CharSequence charSequence) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.formatWithURLs(charSequence);
    }

    public static CharSequence fullyFormatText(CharSequence charSequence, BaseFragment baseFragment, Runnable runnable) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.fullyFormatText(charSequence, baseFragment, runnable);
    }

    public static CharSequence fullyFormatText(CharSequence charSequence) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.fullyFormatText(charSequence, null, null);
    }

    public static String getAppName() {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.getAppName();
    }

    public static void parseMarkdownLinks(CharSequence[] charSequenceArr, Runnable runnable) {
        app.nimarkogram.messenger.utils.text.LocaleUtils.parseMarkdownLinks(charSequenceArr, runnable);
    }

    public static void parseMarkdownLinks(CharSequence[] charSequenceArr) {
        app.nimarkogram.messenger.utils.text.LocaleUtils.parseMarkdownLinks(charSequenceArr, null);
    }

    public static String ensureUrlHasHttps(String str) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.ensureUrlHasHttps(str);
    }

    public static Spannable createCopySpan(BaseFragment baseFragment) {
        return app.nimarkogram.messenger.utils.text.LocaleUtils.createCopySpan(baseFragment);
    }

    public static String capitalize(String str) {
        if (str == null) {
            return null;
        }
        char[] chars = str.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (i == 0) {
                chars[i] = Character.toUpperCase(chars[i]);
            } else if (Character.isLetter(chars[i])) {
                chars[i] = Character.toLowerCase(chars[i]);
            }
        }
        return new String(chars);
    }

    public static CharSequence fromHtml(String str) {
        try {
            return new SpannableString(Html.fromHtml(str, Html.FROM_HTML_MODE_LEGACY));
        } catch (Exception e) {
            FileLog.e(e);
            return str == null ? "" : str;
        }
    }

    public static CharSequence applyNewSpan(CharSequence charSequence) {
        try {
            if (charSequence == null) {
                charSequence = "";
            }
            SpannableStringBuilder builder = new SpannableStringBuilder(charSequence);
            builder.append("  d");
            FilterCreateActivity.NewSpan newSpan = new FilterCreateActivity.NewSpan(10.0f);
            newSpan.setText("NEW");
            newSpan.setTypeface(AndroidUtilities.getTypeface("fonts/num.otf"));
            newSpan.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            builder.setSpan(newSpan, builder.length() - 1, builder.length(), 33);
            return builder;
        } catch (Exception e) {
            FileLog.e(e);
            return charSequence == null ? "" : charSequence;
        }
    }

    public static CharSequence insertHexColorsPreview(CharSequence charSequence) {
        try {
            if (!TextUtils.isEmpty(charSequence) && containsHash(charSequence)) {
                Spannable spannable = charSequence instanceof Spannable
                        ? (Spannable) charSequence
                        : new SpannableString(charSequence);
                for (ColorRectSpan span : spannable.getSpans(0, spannable.length(), ColorRectSpan.class)) {
                    spannable.removeSpan(span);
                }
                Matcher matcher = HEX_PATTERN.matcher(spannable);
                while (matcher.find()) {
                    int end = matcher.end();
                    int start = end - 1;
                    if (!hasConflictingHexPreviewSpan(spannable, start, end)) {
                        try {
                            spannable.setSpan(new ColorRectSpan(Color.parseColor(matcher.group())), start, end, 33);
                        } catch (IllegalArgumentException unused) {
                            FileLog.e("Invalid HEX color: " + matcher.group());
                        }
                    }
                }
                return spannable;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return charSequence;
    }

    public static String normalizeResourceLanguage(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        String lower = str.toLowerCase(Locale.US);
        if ("he".equals(lower)) {
            return "iw";
        }
        if ("no".equals(lower)) {
            return "nb";
        }
        return lower;
    }

    public static String normalizeResourceRegion(String str, String str2) {
        if (TextUtils.isEmpty(str2)) {
            return null;
        }
        String lang = normalizeResourceLanguage(str);
        String upper = str2.toUpperCase(Locale.US);
        if ("zh".equals(lang)) {
            if ("HANS".equals(upper) || "CN".equals(upper) || "SG".equals(upper)) {
                return "CN";
            }
            return "TW";
        }
        return upper;
    }

    public static String getActionBarTitle() {
        return getActionBarTitle(UserConfig.selectedAccount);
    }

    public static String getActionBarTitle(int account) {
        try {
            return LocaleController.getString(R.string.AppName);
        } catch (Exception e) {
            FileLog.e(e);
            return getAppName();
        }
    }

    public static boolean canUseLocalPremiumEmojis() {
        return canUseLocalPremiumEmojis(UserConfig.selectedAccount);
    }

    public static boolean canUseLocalPremiumEmojis(int account) {
        try {
            return !UserConfig.getInstance(account).isPremium();
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean containsHash(CharSequence charSequence) {
        for (int i = 0; i < charSequence.length(); i++) {
            if (charSequence.charAt(i) == '#') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasConflictingHexPreviewSpan(Spannable spannable, int start, int end) {
        for (Object span : spannable.getSpans(start, end, Object.class)) {
            if (!(span instanceof ColorRectSpan)) {
                int spanStart = spannable.getSpanStart(span);
                int spanEnd = spannable.getSpanEnd(span);
                if (spanStart < end && spanEnd > start && (span instanceof android.text.style.ReplacementSpan)) {
                    return true;
                }
            }
        }
        return false;
    }
}
