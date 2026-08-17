/*
 * Original Copyright github.com/arsLan4k1390, 2022-2026. GPL v2+.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */
package app.nimarkogram.messenger.utils.text;

import android.text.TextUtils;

import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.nimarkogram.messenger.utils.chats.NimarkoMessageHelper;

public class TelegramTranslator extends BaseTranslator {

    private final List<String> targetLanguages = Arrays.asList(
            "sq", "ar", "am", "az", "ga", "et", "or", "eu", "be", "bg", "is", "pl", "bs",
            "fa", "af", "tt", "da", "de", "ru", "fr", "tl", "fi", "fy", "km", "ka", "gu",
            "kk", "ht", "ko", "ha", "nl", "ky", "gl", "ca", "cs", "kn", "co", "hr", "ku",
            "la", "lv", "lo", "lt", "lb", "rw", "ro", "mg", "mt", "mr", "ml", "ms", "mk",
            "mi", "mn", "bn", "my", "hmn", "xh", "zu", "ne", "no", "pa", "pt", "ps", "ny",
            "ja", "sv", "sm", "sr", "st", "si", "eo", "sk", "sl", "sw", "gd", "ceb", "so",
            "tg", "te", "ta", "th", "tr", "tk", "cy", "ug", "ur", "uk", "uz", "es", "iw",
            "el", "haw", "sd", "hu", "sn", "hy", "ig", "it", "yi", "hi", "su", "id", "jw",
            "en", "yo", "vi", "zh-TW", "zh-CN", "zh");

    static TelegramTranslator getInstance() {
        return new TelegramTranslator();
    }

    @Override
    protected Result singleTranslate(Object query, String tl) {
        throw new UnsupportedOperationException("TelegramTranslator only operates in batch mode via multiTranslate()");
    }

    private ArrayList<Result> internalTranslate(ArrayList<Object> query, String tl, String subToken) throws Exception {
        int count = query.size();
        final CountDownLatch waitDetect = new CountDownLatch(count);
        final ArrayList<String> languages = new ArrayList<>(Collections.nCopies(count, null));
        final CountDownLatch waitTranslate = new CountDownLatch(1);
        ArrayList<Result> results = new ArrayList<>();
        final AtomicReference<Exception> exception = new AtomicReference<>();
        for (int i = 0; i < count; i++) {
            final int index = i;
            final Object q = query.get(i);
            LanguageDetector.detectLanguage(stringFromTranslation(q), lng -> {
                if (!Objects.equals(lng, "und")) {
                    languages.set(index, lng);
                }
                waitDetect.countDown();
            }, e -> {
                exception.set(e);
                waitDetect.countDown();
            });
        }
        if (!waitDetect.await(15, TimeUnit.SECONDS)) {
            throw new java.util.concurrent.TimeoutException("Language detection timed out");
        }
        if (exception.get() != null) {
            throw exception.get();
        }
        TLRPC.TL_messages_translateText req = new TLRPC.TL_messages_translateText();
        req.flags |= 2;
        req.to_lang = tl;
        for (int i = 0; i < count; i++) {
            Object q = query.get(i);
            TLRPC.TL_textWithEntities textWithEntities;
            if (q instanceof TLRPC.TL_textWithEntities) {
                textWithEntities = (TLRPC.TL_textWithEntities) q;
            } else {
                textWithEntities = new TLRPC.TL_textWithEntities();
                textWithEntities.text = stringFromTranslation(q);
            }
            req.text.add(textWithEntities);
        }
        int reqId = ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (res instanceof TLRPC.TL_messages_translateResult &&
                    !((TLRPC.TL_messages_translateResult) res).result.isEmpty()) {
                TLRPC.TL_messages_translateResult result = (TLRPC.TL_messages_translateResult) res;
                if (result.result.size() != count) {
                    exception.set(new Exception("Unexpected translation result count"));
                } else {
                    for (int i = 0; i < count; i++) {
                        results.add(new Result(TranslateAlert2.preprocess(req.text.get(i), result.result.get(i)), languages.get(i)));
                    }
                }
            } else if (err != null) {
                exception.set(new Exception(err.text));
            } else {
                exception.set(new Exception("Unknown error"));
            }
            waitTranslate.countDown();
        });
        if (!waitTranslate.await(30, TimeUnit.SECONDS)) {
            ConnectionsManager.getInstance(account).cancelRequest(reqId, true);
            throw new java.util.concurrent.TimeoutException("Translation request timed out");
        }
        if (exception.get() != null) {
            throw exception.get();
        }
        return results;
    }

    @Override
    protected ArrayList<Result> multiTranslate(ArrayList<Object> translations, String tl) throws Exception {
        int count = translations.size();
        ArrayList<ArrayList<Object>> chunks = new ArrayList<>();
        final ArrayList<Result> rawResults = new ArrayList<>();
        final ArrayList<Result> results = new ArrayList<>();
        AtomicReference<Exception> exception = new AtomicReference<>();
        ArrayList<Object> textMessagesQuery = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object q = translations.get(i);
            if (q instanceof TLRPC.TL_textWithEntities || q instanceof CharSequence) {
                textMessagesQuery.add(q);
            } else if (q instanceof AdditionalObjectTranslation) {
                AdditionalObjectTranslation res = (AdditionalObjectTranslation) q;
                Object translationData = res.translation;
                if (translationData instanceof String || translationData instanceof TLRPC.TL_textWithEntities) {
                    textMessagesQuery.add(translationData);
                    if (res.additionalInfo instanceof NimarkoMessageHelper.ReplyMarkupButtonsTexts) {
                        NimarkoMessageHelper.ReplyMarkupButtonsTexts buttonRows = (NimarkoMessageHelper.ReplyMarkupButtonsTexts) res.additionalInfo;
                        for (int x = 0; x < buttonRows.getTexts().size(); x++) {
                            textMessagesQuery.addAll(buttonRows.getTexts().get(x));
                        }
                    }
                } else if (translationData instanceof NimarkoMessageHelper.PollTexts) {
                    textMessagesQuery.addAll(new ArrayList<>(((NimarkoMessageHelper.PollTexts) translationData).getTexts()));
                }
            }
        }
        int maxSize = UserConfig.getInstance(account).isPremium() ? 20 : 1;
        for (int i = 0; i < textMessagesQuery.size(); i += maxSize) {
            chunks.add(new ArrayList<>(textMessagesQuery.subList(i, Math.min(i + maxSize, textMessagesQuery.size()))));
        }
        
        for (ArrayList<Object> chunk : chunks) {
            if (exception.get() != null) break;
            try {
                rawResults.addAll(internalTranslate(chunk, tl, Utilities.generateRandomString()));
            } catch (Exception e) {
                exception.set(e);
            }
        }
        if (exception.get() != null) {
            throw exception.get();
        }
        int cursor = 0;
        for (int i = 0; i < translations.size(); i++) {
            Object q = translations.get(i);
            String sourceLanguage = null;
            if (q instanceof TLRPC.TL_textWithEntities || q instanceof CharSequence) {
                results.add(Objects.requireNonNull(rawResults.get(cursor++)));
            } else if (q instanceof AdditionalObjectTranslation) {
                AdditionalObjectTranslation res = (AdditionalObjectTranslation) q;
                Object translationData = res.translation;
                if (translationData instanceof String || translationData instanceof TLRPC.TL_textWithEntities) {
                    Result mainResult = Objects.requireNonNull(rawResults.get(cursor++));
                    res.translation = mainResult.translation;
                    sourceLanguage = mainResult.sourceLanguage;
                    if (res.additionalInfo instanceof NimarkoMessageHelper.ReplyMarkupButtonsTexts) {
                        NimarkoMessageHelper.ReplyMarkupButtonsTexts buttonRows = (NimarkoMessageHelper.ReplyMarkupButtonsTexts) res.additionalInfo;
                        for (int x = 0; x < buttonRows.getTexts().size(); x++) {
                            for (int y = 0; y < buttonRows.getTexts().get(x).size(); y++) {
                                buttonRows.getTexts().get(x).set(y, stringFromTranslation(Objects.requireNonNull(rawResults.get(cursor++)).translation));
                            }
                        }
                    }
                } else if (translationData instanceof NimarkoMessageHelper.PollTexts) {
                    NimarkoMessageHelper.PollTexts pollTexts = (NimarkoMessageHelper.PollTexts) translationData;
                    ArrayList<Result> totalPollResults = new ArrayList<>();
                    for (int x = 0; x < pollTexts.getTexts().size(); x++) {
                        totalPollResults.add(Objects.requireNonNull(rawResults.get(cursor++)));
                        pollTexts.getTexts().set(x, stringFromTranslation(totalPollResults.get(x).translation));
                    }
                    res.translation = pollTexts;
                    sourceLanguage = getTopLanguage(totalPollResults);
                }
                results.add(new Result(res.translation, res.additionalInfo, sourceLanguage));
            }
        }
        return results;
    }

    @Override
    public String convertLanguageCode(String language, String country) {
        String languageLowerCase = language.toLowerCase();
        String code;
        if (!TextUtils.isEmpty(country)) {
            String countryUpperCase = country.toUpperCase();
            if (targetLanguages.contains(languageLowerCase + "-" + countryUpperCase)) {
                code = languageLowerCase + "-" + countryUpperCase;
            } else if (languageLowerCase.equals("zh")) {
                if (countryUpperCase.equals("DG")) {
                    code = "zh-CN";
                } else if (countryUpperCase.equals("HK")) {
                    code = "zh-TW";
                } else {
                    code = languageLowerCase;
                }
            } else {
                code = languageLowerCase;
            }
        } else {
            code = languageLowerCase;
        }
        return code;
    }

    @Override
    public List<String> getTargetLanguages() {
        return targetLanguages;
    }
}
