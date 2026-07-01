package org.iiab.controller.applang.data;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Thin wrapper over AppCompat's per-app locales. Applying a tag recreates the running
 * activities in the chosen language; AppCompat persists the choice (autoStoreLocales) and
 * reapplies it on the next launch. An empty tag clears the override → follow the system.
 * Data layer; no Context needed.
 */
public final class AppLocaleController {

    private AppLocaleController() {
    }

    /** Current override as a BCP-47 tag, or "" when following the system. */
    public static String currentTag() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        return locales.isEmpty() ? "" : locales.toLanguageTags();
    }

    /** Apply an override tag, or "" to clear it (follow the system). */
    public static void apply(String tag) {
        LocaleListCompat locales = (tag == null || tag.isEmpty())
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag);
        AppCompatDelegate.setApplicationLocales(locales);
    }
}
