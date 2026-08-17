package app.nimarkogram.messenger.utils.text;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranslateAlert2;

import app.nimarkogram.messenger.NimarkoConfig;

public abstract class TranslatorUtils {

    private static final String APP = "app";

    public interface TranslateCallback {
        void onFailed();

        default void onSuccess(TLObject response, TLRPC.TL_error error) {
        }

        default void onSuccess(TLRPC.TL_textWithEntities textWithEntities) {
        }

        default void onReqId(int reqId) {
        }

        default void onSuccess(String text) {
        }
    }

    private static String getStoredTargetLanguage() {
        try {
            String v = NimarkoConfig.translationTarget;
            return TextUtils.isEmpty(v) ? APP : v;
        } catch (Throwable t) {
            return APP;
        }
    }

    public static boolean isTargetLanguageFollowApp() {
        try {
            return APP.equalsIgnoreCase(getStoredTargetLanguage());
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setTargetLanguage(String str) {
        try {
            String normalized = APP.equalsIgnoreCase(str) ? APP : normalizeLanguageCode(str);
            String value = TextUtils.isEmpty(normalized) ? APP : normalized;
            NimarkoConfig.setTranslationTarget(value);
        } catch (Throwable ignored) {
        }
    }

    public static String normalizeLanguageCode(String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        String s = str.trim().toLowerCase(Locale.US).replace('_', '-');
        return "nb".equals(s) ? "no" : s;
    }

    public static String primaryLanguageOf(String str) {
        String normalized = normalizeLanguageCode(str);
        if (TextUtils.isEmpty(normalized)) {
            return null;
        }
        int dash = normalized.indexOf('-');
        return dash >= 0 ? normalized.substring(0, dash) : normalized;
    }

    private static String getResolvedAppLanguageCode() {
        try {
            Locale currentLocale = LocaleController.getInstance().getCurrentLocale();
            if (currentLocale == null) {
                currentLocale = Locale.getDefault();
            }
            String lang = normalizeLanguageCode(currentLocale.getLanguage());
            String country = currentLocale.getCountry();
            if (!TextUtils.isEmpty(lang) && !TextUtils.isEmpty(country)) {
                String withCountry = normalizeLanguageCode(lang + "-" + country);
                if (!TextUtils.isEmpty(withCountry)) {
                    return withCountry;
                }
            }
            if (!TextUtils.isEmpty(lang)) {
                return lang;
            }
        } catch (Throwable ignored) {
        }
        return "en";
    }

    public static String getResolvedTargetLanguageCode(String str) {
        if (APP.equalsIgnoreCase(str)) {
            return getResolvedAppLanguageCode();
        }
        String normalized = normalizeLanguageCode(str);
        return TextUtils.isEmpty(normalized) ? getResolvedAppLanguageCode() : normalized;
    }

    public static String getResolvedTargetLanguageCode() {
        return getResolvedTargetLanguageCode(getStoredTargetLanguage());
    }

    private static List<TranslateController.Language> getLanguages() {
        try {
            return new ArrayList<>(TranslateController.getLanguages());
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static List<TranslateController.Language> getIndexedTargetLanguages() {
        return getLanguages();
    }

    public static String getLanguageDisplayName(String str) {
        try {
            for (TranslateController.Language language : getLanguages()) {
                if (language != null && TextUtils.equals(language.code, str)) {
                    return language.ownDisplayName;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String getLanguageTitleSystem(String str) {
        try {
            if ("none".equals(str)) {
                return LocaleController.getString(R.string.None);
            }
            for (TranslateController.Language language : getLanguages()) {
                if (language != null && TextUtils.equals(language.code, str)) {
                    return language.displayName;
                }
            }
            String normalized = normalizeLanguageCode(str);
            return TextUtils.isEmpty(normalized) ? "" : normalized.toUpperCase(Locale.US);
        } catch (Throwable t) {
            return str == null ? "" : str;
        }
    }

    public static String getTargetLanguageTitle() {
        try {
            return isTargetLanguageFollowApp()
                    ? targetAppTitle()
                    : getLanguageTitleSystem(getResolvedTargetLanguageCode());
        } catch (Throwable t) {
            return "";
        }
    }

    public static CharSequence[] getTargetLanguageTitles() {
        List<TranslateController.Language> indexed = getIndexedTargetLanguages();
        CharSequence[] titles = new CharSequence[indexed.size() + 1];
        titles[0] = targetAppTitle();
        for (int i = 0; i < indexed.size(); i++) {
            TranslateController.Language language = indexed.get(i);
            StringBuilder sb = new StringBuilder();
            if (language != null) {
                sb.append(language.displayName);
                if (language.ownDisplayName != null) {
                    sb.append(" – ").append(language.ownDisplayName);
                }
            }
            titles[i + 1] = sb.toString();
        }
        return titles;
    }

    public static String getTargetLanguageCodeByIndex(int i) {
        if (i == 0) {
            return APP;
        }
        List<TranslateController.Language> indexed = getIndexedTargetLanguages();
        int idx = i - 1;
        if (idx < 0 || idx >= indexed.size()) {
            return null;
        }
        return indexed.get(idx).code;
    }

    public static int getTargetLanguageIndexByCode(String str) {
        if (APP.equalsIgnoreCase(str)) {
            return 0;
        }
        List<TranslateController.Language> indexed = getIndexedTargetLanguages();
        String resolved = getResolvedTargetLanguageCode(str);
        for (int i = 0; i < indexed.size(); i++) {
            if (TextUtils.equals(indexed.get(i).code, resolved)) {
                return i + 1;
            }
        }
        return 0;
    }

    public static String getCurrentTranslatorName() {
        
        return "Telegram";
    }

    private static String targetAppTitle() {
        try {
            return LocaleController.getString(R.string.AutomaticTranslation);
        } catch (Throwable t) {
            return "App language";
        }
    }

    public static boolean isTargetLanguageSupportedForCurrentProvider(String str) {
        if (APP.equalsIgnoreCase(str)) {
            return true;
        }
        
        return !TextUtils.isEmpty(normalizeLanguageCode(str));
    }

    public static boolean isRestrictedLanguage(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        try {
            return TextUtils.equals(primaryLanguageOf(str), primaryLanguageOf(getResolvedTargetLanguageCode()));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void ensureTargetLanguageCompatibleWithProvider() {
        
        try {
            if (!isTargetLanguageSupportedForCurrentProvider(getStoredTargetLanguage())) {
                setTargetLanguage(APP);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void translate(CharSequence charSequence,
                                 String fromLang,
                                 String toLang,
                                 ArrayList<TLRPC.MessageEntity> entities,
                                 final TranslateCallback callback) {
        if (callback == null) {
            return;
        }
        if (TextUtils.isEmpty(charSequence)) {
            AndroidUtilities.runOnUIThread(callback::onFailed);
            return;
        }
        try {
            final int account = UserConfig.selectedAccount;
            final String resolved = getResolvedTargetLanguageCode(toLang);

            TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
            TLRPC.TL_textWithEntities textWithEntities = new TLRPC.TL_textWithEntities();
            textWithEntities.text = charSequence.toString();
            if (entities != null) {
                textWithEntities.entities = new ArrayList<>(entities.size());
                for (TLRPC.MessageEntity entity : entities) {
                    textWithEntities.entities.add(
                            TLObject.deepCopy(entity, TLRPC.MessageEntity::TLdeserialize));
                }
            }
            req.flags |= 2;
            req.text.add(textWithEntities);

            String to = resolved != null ? resolved.trim() : null;
            if ("nb".equals(to)) {
                to = "no";
            }
            req.to_lang = to;

            int reqId = ConnectionsManager.getInstance(account).sendRequest(req, new RequestDelegate() {
                @Override
                public void run(TLObject response, TLRPC.TL_error error) {
                    if (response instanceof TLRPC.TL_messages_translateResult) {
                        TLRPC.TL_messages_translateResult result = (TLRPC.TL_messages_translateResult) response;
                        if (!result.result.isEmpty()
                                && result.result.get(0) != null
                                && !TextUtils.isEmpty(result.result.get(0).text)) {
                            final TLRPC.TL_textWithEntities out = result.result.get(0);
                            final TLRPC.TL_error err = error;
                            AndroidUtilities.runOnUIThread(() -> {
                                callback.onSuccess(result, err);
                                callback.onSuccess(out);
                                callback.onSuccess(out.text);
                            });
                            return;
                        }
                    }
                    AndroidUtilities.runOnUIThread(callback::onFailed);
                }
            });
            callback.onReqId(reqId);
        } catch (Throwable t) {
            AndroidUtilities.runOnUIThread(callback::onFailed);
        }
    }

    public static void translate(final CharSequence charSequence,
                                 String toLang,
                                 final ArrayList<TLRPC.MessageEntity> entities,
                                 final TranslateCallback callback) {
        if (callback == null) {
            return;
        }
        if (TextUtils.isEmpty(charSequence)) {
            return;
        }
        final String resolved = getResolvedTargetLanguageCode(toLang);
        
        translate(charSequence, "auto", resolved, entities, callback);
    }

    public static void translate(CharSequence charSequence, String toLang, TranslateCallback callback) {
        translate(charSequence, toLang, null, callback);
    }
}
