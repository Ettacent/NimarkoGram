package app.nimarkogram.messenger.updater;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;


public final class LocalizedChangelog {
    private final Map<String, String> translations;
    private final String legacy;

    public LocalizedChangelog(String legacy, Map<String, String> translations) {
        this.legacy = legacy == null ? "" : legacy;
        Map<String, String> copy = new LinkedHashMap<>();
        if (translations != null) {
            for (Map.Entry<String, String> entry : translations.entrySet()) {
                String key = normalize(entry.getKey());
                String value = entry.getValue();
                if (!key.isEmpty() && value != null && !value.trim().isEmpty()) {
                    copy.put(key, value);
                }
            }
        }
        this.translations = Collections.unmodifiableMap(copy);
    }

    public Map<String, String> getTranslations() {
        return translations;
    }

    public String resolve(String... languageCodes) {
        for (String code : languageCodes) {
            String key = normalize(code);
            String text = translations.get(key);
            if (text != null) return text;

            if (key.startsWith("zh-")) {
                String script = key.contains("-hant") || key.equals("zh-tw") || key.equals("zh-hk") || key.equals("zh-mo")
                        ? "zh-hant" : key.contains("-hans") || key.equals("zh-cn") || key.equals("zh-sg") ? "zh-hans" : null;
                text = translations.get(script);
                if (text != null) return text;
            }
            while (key.contains("-")) {
                key = key.substring(0, key.lastIndexOf('-'));
                text = translations.get(key);
                if (text != null) return text;
            }
        }
        String english = translations.get("en");
        return english != null ? english : legacy;
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
