package app.nimarkogram.messenger.utils.text;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.text.style.URLSpan;
import android.view.View;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.LaunchActivity;

public abstract class LocaleUtils {
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^]]+?)]\\(" + "AndroidUtilities.WEB_URL" + "\\)");

    public static CharSequence formatWithUsernames(CharSequence charSequence) {
        return formatWithUsernames(charSequence, LaunchActivity.getSafeLastFragment());
    }

    public static CharSequence formatWithUsernames(CharSequence charSequence, BaseFragment baseFragment) {
        return formatWithUsernames(charSequence, baseFragment, null);
    }

    public static CharSequence formatWithUsernames(CharSequence charSequence, BaseFragment baseFragment, Runnable runnable) {
        if (TextUtils.isEmpty(charSequence)) {
            return charSequence;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(charSequence);
        int start = -1;
        for (int i = 0; i < charSequence.length(); i++) {
            if (charSequence.charAt(i) == '@') {
                start = i;
            } else if (start != -1) {
                int end = i + 1;
                boolean isEnd = end == charSequence.length();
                boolean isValidChar = Character.isLetterOrDigit(charSequence.charAt(end - 1)) || charSequence.charAt(end - 1) == '_';
                
                if (isEnd || !isValidChar) {
                    int finalEnd = isEnd && isValidChar ? end : i;
                    
                    if (finalEnd - start > 1) {
                        URLSpan[] existingSpans = builder.getSpans(start, finalEnd, URLSpan.class);
                        if (existingSpans == null || existingSpans.length == 0) {
                            String username = charSequence.subSequence(start, finalEnd).toString();
                            builder.setSpan(new URLSpanNoUnderline(username) {
                                @Override
                                public void onClick(View view) {
                                    if (runnable != null) runnable.run();
                                    if (baseFragment != null && baseFragment.getMessagesController() != null) {
                                        baseFragment.getMessagesController().openByUserName(username.substring(1), baseFragment, 1);
                                    }
                                }
                            }, start, finalEnd, 33);
                        }
                    }
                    start = -1;
                }
            }
        }
        return builder;
    }

    public static CharSequence formatWithHtmlURLs(CharSequence charSequence) {
        if (TextUtils.isEmpty(charSequence)) {
            return charSequence;
        }
        SpannableString spannable = new SpannableString(charSequence);
        URLSpan[] spans = spannable.getSpans(0, charSequence.length(), URLSpan.class);
        SpannableStringBuilder builder = new SpannableStringBuilder(spannable);
        for (URLSpan span : spans) {
            int start = builder.getSpanStart(span);
            int end = builder.getSpanEnd(span);
            String url = span.getURL();
            builder.removeSpan(span);
            builder.setSpan(new URLSpanNoUnderline(url), start, end, 33);
        }
        return builder;
    }

    public static CharSequence formatWithURLs(CharSequence charSequence) {
        if (TextUtils.isEmpty(charSequence)) {
            return charSequence;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(charSequence);
        Matcher matcher = AndroidUtilities.WEB_URL.matcher(charSequence);
        while (matcher.find()) {
            try {
                builder.setSpan(new URLSpanNoUnderline(ensureUrlHasHttps(matcher.group(0))), matcher.start(), matcher.end(), 33);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return builder;
    }

    public static CharSequence fullyFormatText(CharSequence charSequence, BaseFragment baseFragment, Runnable runnable) {
        if (TextUtils.isEmpty(charSequence)) {
            return charSequence;
        }
        CharSequence[] seqRef = {formatWithURLs(charSequence)};
        parseMarkdownLinks(seqRef, runnable);
        CharSequence text = seqRef[0];
        
        CharSequence withUsernames = (baseFragment != null && runnable != null) 
                ? formatWithUsernames(text, baseFragment, runnable) 
                : formatWithUsernames(text);
                
        return AndroidUtilities.replaceTags(withUsernames);
    }

    public static String getAppName() {
        try {
            return ApplicationLoader.applicationContext.getString(R.string.AppName);
        } catch (Exception unused) {
            return "NimarkoGram";
        }
    }

    public static void parseMarkdownLinks(CharSequence[] seqRef, Runnable runnable) {
        if (seqRef == null || seqRef.length == 0 || seqRef[0] == null) return;
        
        Spannable spannable = seqRef[0] instanceof Spannable ? (Spannable) seqRef[0] : new SpannableString(seqRef[0]);
        Matcher matcher = MARKDOWN_LINK_PATTERN.matcher(spannable);
        
        ArrayList<String> sources = new ArrayList<>();
        ArrayList<CharSequence> replacements = new ArrayList<>();
        
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (start >= 0 && end >= 0 && start <= end) {
                SpannableStringBuilder replacement = new SpannableStringBuilder(spannable.subSequence(start, end));
                replacement.setSpan(new URLSpanNoUnderline(matcher.group(2)) {
                    @Override
                    public void onClick(View view) {
                        if (runnable != null) runnable.run();
                        super.onClick(view);
                    }
                }, 0, replacement.length(), 33);
                sources.add(matcher.group(0));
                replacements.add(replacement);
            }
        }
        
        if (!sources.isEmpty()) {
            seqRef[0] = TextUtils.replace(seqRef[0], sources.toArray(new String[0]), replacements.toArray(new CharSequence[0]));
        }
    }

    public static String ensureUrlHasHttps(String str) {
        if (str == null) return null;
        if (!AndroidUtilities.WEB_URL.matcher(str).matches() || str.startsWith("http://") || str.startsWith("https://") || str.contains("://")) {
            return str;
        }
        return "https://" + str;
    }
    
    public static Spannable createCopySpan(BaseFragment baseFragment) {
        SpannableString span = new SpannableString(" ");
        
        Context ctx = baseFragment != null ? baseFragment.getParentActivity() : null;
        if (ctx == null) {
            ctx = ApplicationLoader.applicationContext;
        }
        if (ctx == null) {
            return span;
        }
        Drawable drawable = ContextCompat.getDrawable(ctx, R.drawable.msg_copy);
        if (drawable == null) {
            return span;
        }
        drawable = drawable.mutate();
        Theme.ResourcesProvider rp = baseFragment != null ? baseFragment.getResourceProvider() : null;
        drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_undo_cancelColor, rp), PorterDuff.Mode.SRC_IN));
        drawable.setBounds(0, 0, AndroidUtilities.dp(22.0f), AndroidUtilities.dp(22.0f));
        span.setSpan(new ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, 33);
        return span;
    }

}