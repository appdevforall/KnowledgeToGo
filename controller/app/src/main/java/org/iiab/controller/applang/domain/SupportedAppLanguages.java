package org.iiab.controller.applang.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The fixed set of UI languages the app can switch to: "system default" first (empty tag),
 * then every locale the app ships translations for, ordered A–Z by the language's English
 * name so the picker is one flat, searchable list (not grouped by script). Each entry is
 * labelled {@code "<endonym> (<English name>)"} — e.g. {@code "Español (Spanish)"} — so a
 * user can find their language under its Roman-alphabet name regardless of its own script.
 * Pure and JVM-testable. Region-qualified tags (e.g. {@code ru-RU}) match the corresponding
 * {@code values-*} resource folders. See ADFA-4537.
 */
public final class SupportedAppLanguages {

    /** {tag, endonym (native name), English name}. Order here is irrelevant — sorted below. */
    private static final String[][] LANGUAGES = {
        {"en", "English", "English"},
        {"de", "Deutsch", "German"},
        {"it", "Italiano", "Italian"},
        {"ar", "العربية", "Arabic"},
        {"ja", "日本語", "Japanese"},
        {"zh-CN", "简体中文", "Chinese (Simplified)"},
        {"ko", "한국어", "Korean"},
        {"nl", "Nederlands", "Dutch"},
        {"tr", "Türkçe", "Turkish"},
        {"vi", "Tiếng Việt", "Vietnamese"},
        {"pl", "Polski", "Polish"},
        {"cs", "Čeština", "Czech"},
        {"id", "Bahasa Indonesia", "Indonesian"},
        {"fa", "فارسی", "Persian"},
        {"uk", "Українська", "Ukrainian"},
        {"ro", "Română", "Romanian"},
        {"el", "Ελληνικά", "Greek"},
        {"sk", "Slovenčina", "Slovak"},
        {"bg", "Български", "Bulgarian"},
        {"sr", "Српски", "Serbian"},
        {"lt", "Lietuvių", "Lithuanian"},
        {"no", "Norsk", "Norwegian"},
        {"hu", "Magyar", "Hungarian"},
        {"az", "Azərbaycan", "Azerbaijani"},
        {"bn", "বাংলা", "Bengali"},
        {"gu", "ગુજરાતી", "Gujarati"},
        {"ta", "தமிழ்", "Tamil"},
        {"sw", "Kiswahili", "Swahili"},
        {"yo", "Yorùbá", "Yoruba"},
        {"es", "Español", "Spanish"},
        {"fr", "Français", "French"},
        {"hi", "हिन्दी", "Hindi"},
        {"pt", "Português", "Portuguese"},
        {"ru-RU", "Русский", "Russian"},
    };

    private SupportedAppLanguages() {
    }

    /**
     * @param systemDefaultLabel localized label for the "follow the phone" option
     * @return an immutable list: system default at index 0, then every language sorted
     *         A–Z by its English name.
     */
    public static List<AppLanguage> all(String systemDefaultLabel) {
        String[][] langs = LANGUAGES.clone();
        Arrays.sort(langs, (a, b) -> a[2].compareToIgnoreCase(b[2]));
        List<AppLanguage> list = new ArrayList<>();
        list.add(new AppLanguage("", systemDefaultLabel));
        for (String[] l : langs) {
            String endonym = l[1];
            String english = l[2];
            String label = endonym.equals(english) ? endonym : endonym + " (" + english + ")";
            list.add(new AppLanguage(l[0], label));
        }
        return Collections.unmodifiableList(list);
    }

    /** Index of the entry matching {@code tag}, or 0 (system default) if none matches. */
    public static int indexOfTag(List<AppLanguage> list, String tag) {
        String t = tag == null ? "" : tag;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).tag().equals(t)) {
                return i;
            }
        }
        return 0;
    }
}
