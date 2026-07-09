package org.iiab.controller.applang.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The fixed set of UI languages the app can switch to: "system default" first (empty tag),
 * then every locale the app ships translations for, labelled with its endonym. Pure and
 * JVM-testable. Region-qualified tags (e.g. {@code ru-RU}) match the corresponding
 * {@code values-*} resource folders.
 */
public final class SupportedAppLanguages {

    private SupportedAppLanguages() {
    }

    /**
     * @param systemDefaultLabel localized label for the "follow the phone" option
     * @return an immutable, ordered list (system default at index 0)
     */
    public static List<AppLanguage> all(String systemDefaultLabel) {
        List<AppLanguage> list = new ArrayList<>();
        list.add(new AppLanguage("", systemDefaultLabel));
        list.add(new AppLanguage("en", "English"));
        list.add(new AppLanguage("de", "Deutsch"));
        list.add(new AppLanguage("it", "Italiano"));
        list.add(new AppLanguage("ar", "العربية"));
        list.add(new AppLanguage("ja", "日本語"));
        list.add(new AppLanguage("zh-CN", "简体中文"));
        list.add(new AppLanguage("ko", "한국어"));
        list.add(new AppLanguage("nl", "Nederlands"));
        list.add(new AppLanguage("tr", "Türkçe"));
        list.add(new AppLanguage("vi", "Tiếng Việt"));
        list.add(new AppLanguage("pl", "Polski"));
        list.add(new AppLanguage("cs", "Čeština"));
        list.add(new AppLanguage("id", "Bahasa Indonesia"));
        list.add(new AppLanguage("fa", "فارسی"));
        list.add(new AppLanguage("uk", "Українська"));
        list.add(new AppLanguage("ro", "Română"));
        list.add(new AppLanguage("el", "Ελληνικά"));
        list.add(new AppLanguage("sk", "Slovenčina"));
        list.add(new AppLanguage("bg", "Български"));
        list.add(new AppLanguage("sr", "Српски"));
        list.add(new AppLanguage("lt", "Lietuvių"));
        list.add(new AppLanguage("no", "Norsk"));
        list.add(new AppLanguage("hu", "Magyar"));
        list.add(new AppLanguage("az", "Azərbaycan"));
        list.add(new AppLanguage("bn", "বাংলা"));
        list.add(new AppLanguage("es", "Español"));
        list.add(new AppLanguage("fr", "Français"));
        list.add(new AppLanguage("hi", "हिन्दी"));
        list.add(new AppLanguage("pt", "Português"));
        list.add(new AppLanguage("ru-RU", "Русский"));
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
