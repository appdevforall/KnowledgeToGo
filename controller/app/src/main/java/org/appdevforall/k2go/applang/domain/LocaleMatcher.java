package org.appdevforall.k2go.applang.domain;

import java.util.List;
import java.util.Locale;

/**
 * Picks the index of a locale within a list: exact language+country first, then a
 * language-only fallback, else 0. Pure and JVM-testable. Used to keep the Content
 * Language selector pinned to the stored preference (not to {@code Locale.getDefault()},
 * which the app UI language override changes on recreation). See ADFA-4304.
 */
public final class LocaleMatcher {

    private LocaleMatcher() {
    }

    public static int pickIndex(List<Locale> locales, String language, String country) {
        String lang = language == null ? "" : language;
        String ctry = country == null ? "" : country;
        for (int i = 0; i < locales.size(); i++) {
            Locale l = locales.get(i);
            if (l.getLanguage().equals(lang) && l.getCountry().equals(ctry)) {
                return i;
            }
        }
        for (int i = 0; i < locales.size(); i++) {
            if (locales.get(i).getLanguage().equals(lang)) {
                return i;
            }
        }
        return 0;
    }
}
