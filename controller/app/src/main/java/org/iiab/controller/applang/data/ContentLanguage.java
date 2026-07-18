package org.iiab.controller.applang.data;

import android.content.res.Resources;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Content-language helper (ADFA-4725). Two jobs:
 *  - normalize Java's legacy ISO-639 codes to the modern ones the Kiwix catalog uses
 *    (iw->he, in->id, ji->yi), and
 *  - derive the device/system UI language as the default content language.
 * Fixes the "auto-detect isn't working" report: install-time consumers used to fall back
 * to a hardcoded "en" and never re-derived from the system locale, and legacy codes never
 * matched the catalog.
 */
public final class ContentLanguage {

    private ContentLanguage() {
    }

    /** Normalize a language code to the modern ISO-639 form; null/empty passes through. */
    public static String normalize(String code) {
        if (code == null || code.isEmpty()) return code;
        String c = code.toLowerCase(Locale.ROOT);
        switch (c) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            default:   return c;
        }
    }

    /** The system UI language as a normalized content-language code (e.g. "es"), or "en". */
    public static String systemDefault() {
        LocaleList locales = Resources.getSystem().getConfiguration().getLocales();
        if (locales.isEmpty()) return "en";
        String lang = locales.get(0).getLanguage();
        return (lang == null || lang.isEmpty()) ? "en" : normalize(lang);
    }
}
