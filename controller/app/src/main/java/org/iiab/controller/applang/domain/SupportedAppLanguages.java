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
