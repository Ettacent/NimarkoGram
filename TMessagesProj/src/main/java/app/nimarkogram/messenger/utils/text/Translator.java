/*
 * Original Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */
package app.nimarkogram.messenger.utils.text;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;

import androidx.core.text.HtmlCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.utils.ResourcesUtils;

public class Translator {

    public static BaseTranslator getCurrentTranslator() {
        return TelegramTranslator.getInstance();
    }

    public static void showTranslationTargetSelector(Context context, boolean isKeyboard, Runnable callback, Theme.ResourcesProvider resourcesProvider) {
        BaseTranslator translator = Translator.getCurrentTranslator();
        ArrayList<String> targetLanguages = new ArrayList<>(translator.getTargetLanguages());
        ArrayList<CharSequence> names = new ArrayList<>();
        for (String language : targetLanguages) {
            Locale locale = Locale.forLanguageTag(language);
            if (!TextUtils.isEmpty(locale.getScript())) {
                names.add(HtmlCompat.fromHtml(String.format("%s - %s", ResourcesUtils.capitalize(locale.getDisplayScript()), ResourcesUtils.capitalize(locale.getDisplayScript(locale))), HtmlCompat.FROM_HTML_MODE_LEGACY).toString());
            } else {
                names.add(String.format("%s - %s", ResourcesUtils.capitalize(locale.getDisplayName()), ResourcesUtils.capitalize(locale.getDisplayName(locale))));
            }
        }
        AndroidUtilities.selectionSort(names, targetLanguages);

        targetLanguages.add(0, "app");
        names.add(0, getString(R.string.Default));

        String currentTarget = isKeyboard ? NimarkoConfig.translationKeyboardTarget : NimarkoConfig.translationTarget;
        PopupHelper.show(names, getString(R.string.TranslationTarget), targetLanguages.indexOf(currentTarget), context, i -> {
            if (isKeyboard) {
                NimarkoConfig.setTranslationKeyboardTarget(targetLanguages.get(i));
            } else {
                NimarkoConfig.setTranslationTarget(targetLanguages.get(i));
            }
            callback.run();
        });
    }

    public static String translate(String query, boolean isKeyboard, TranslateCallBack translateCallBack) {
        return translate(query, isKeyboard, org.telegram.messenger.UserConfig.selectedAccount, translateCallBack);
    }

    public static String translate(Object query, boolean isKeyboard, int account, TranslateCallBack translateCallBack) {
        return translate(new ArrayList<>(Collections.singletonList(query)), isKeyboard, account, singleTranslateCallback(translateCallBack));
    }

    private static MultiTranslateCallBack singleTranslateCallback(TranslateCallBack callBack) {
        return (e, result) -> {
            if (result != null && !result.isEmpty()) {
                callBack.onSuccess(e, result.get(0));
            } else {
                callBack.onSuccess(e, null);
            }
        };
    }

    public static String translate(ArrayList<Object> translations, boolean isKeyboard, MultiTranslateCallBack translateCallBack) {
        return translate(translations, isKeyboard, org.telegram.messenger.UserConfig.selectedAccount, translateCallBack);
    }

    public static String translate(ArrayList<Object> translations, boolean isKeyboard, int account, MultiTranslateCallBack translateCallBack) {
        BaseTranslator translator = getCurrentTranslator();
        String language = isKeyboard ? translator.getCurrentTargetKeyboardLanguage() : translator.getCurrentTargetLanguage();
        String token = Utilities.generateRandomString();
        if (!translator.supportLanguage(language)) {
            translateCallBack.onSuccess(new UnsupportedTargetLanguageException(), null);
        } else {
            translator.startTask(translations, language, translateCallBack, token, account);
        }
        return token;
    }

    public interface MultiTranslateCallBack {
        void onSuccess(Exception e, ArrayList<BaseTranslator.Result> result);
    }

    public interface TranslateCallBack {
        void onSuccess(Exception e, BaseTranslator.Result result);
    }

    private static class UnsupportedTargetLanguageException extends IllegalArgumentException {
    }
}
