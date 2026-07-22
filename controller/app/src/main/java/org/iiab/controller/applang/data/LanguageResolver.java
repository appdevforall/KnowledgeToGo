package org.iiab.controller.applang.data;

import java.util.Locale;

/**
 * Kiwix-independent language resolver (ADFA-4798). One place that turns a chosen language
 * tag into the code each subsystem expects, with a deterministic fallback chain
 * (specific region &rarr; base language &rarr; {@code "en"}):
 * <ul>
 *   <li>{@link #forUi(String)}    &mdash; BCP-47 tag for the app UI (AppCompat per-app locales).</li>
 *   <li>{@link #forKiwix(String)} &mdash; base content code for the Kiwix catalog ({@code ru-RU -> ru}).</li>
 *   <li>{@link #forHelp(String)}  &mdash; base code for the help/dashboard {@code .po}/{@code help.db}.</li>
 * </ul>
 * The app UI, the Kiwix catalog and the help system historically speak slightly different
 * codes for the same language (e.g. Kiwix {@code "id"} vs help {@code "in"} for Indonesian);
 * those exceptions are owned here, not scattered across consumers. Kiwix is just one
 * consumer — new content sources can add their own {@code forX()} without touching callers.
 * Pure and JVM-testable (except {@link #forKiwix}/{@link #forHelp} on an empty tag, which
 * derive from the system locale via {@link ContentLanguage}).
 */
public final class LanguageResolver {

    private LanguageResolver() {
    }

    /** BCP-47 tag to feed the UI locale layer; {@code ""}/null means follow the system. */
    public static String forUi(String tag) {
        return tag == null ? "" : tag.trim();
    }

    /** Content code for the Kiwix catalog; an empty tag derives from the system locale. */
    public static String forKiwix(String tag) {
        String b = base(tag);
        return b.isEmpty() ? ContentLanguage.systemDefault() : ContentLanguage.normalize(b);
    }

    /** Base code the help/dashboard {@code .po} files are keyed by; empty tag &rarr; system default. */
    public static String forHelp(String tag) {
        String b = base(tag);
        if (b.isEmpty()) b = ContentLanguage.systemDefault();
        // The help .po files use the legacy Indonesian code "in" (not the modern "id").
        return "id".equals(b) ? "in" : b;
    }

    /**
     * The effective content code from the app tag and the content choice. An empty content
     * tag means "same as app language", so the content follows the app tag; otherwise the
     * explicit content tag wins. Both resolve through {@link #forKiwix(String)}.
     */
    public static String contentCode(String appTag, String contentTag) {
        return isEmpty(contentTag) ? forKiwix(appTag) : forKiwix(contentTag);
    }

    /** Base subtag, lower-cased: {@code "ru-RU" -> "ru"}, {@code "zh-CN" -> "zh"}, {@code "" -> ""}. */
    private static String base(String tag) {
        if (isEmpty(tag)) return "";
        return tag.trim().split("-")[0].toLowerCase(Locale.ROOT);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
