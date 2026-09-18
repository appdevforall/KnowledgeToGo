/*
 * ============================================================================
 * Name        : HelpEntry.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-410. Resolve the bundled manual page for a (topic, language) pair.
 * ============================================================================
 */
package org.appdevforall.k2go.help.domain;

/**
 * Resolves the bundled manual page for a (topic, language) pair: the two axes of the in-app help
 * content. Returns a path relative to {@code assets/help/} (e.g. {@code "app-install.html"}); the
 * viewer prefixes the WebViewAssetLoader base.
 *
 * <p>The manual ships English-only today, so {@code languageTag} is accepted but not yet used. When
 * localized manuals are added under {@code assets/help/<lang>/}, map the tag to that subdirectory
 * HERE, in one place: the {@link HelpTopic} callers and the viewer do not change. This is the
 * app-local (assets) path; the box-served tier-3 route lives in {@link Tier3DocsUrl}.
 */
public final class HelpEntry {

    private HelpEntry() {}

    /**
     * The {@code assets/help/}-relative page for a topic.
     *
     * @param topic       the content axis; null resolves to {@link HelpTopic#HOME}
     * @param languageTag the language axis (e.g. from the app locale); reserved for future
     *                    localized manuals, ignored today
     */
    public static String assetPath(HelpTopic topic, String languageTag) {
        HelpTopic t = (topic != null) ? topic : HelpTopic.HOME;
        // TODO(K2GO-410 follow-up): when a localized manual exists under assets/help/<lang>/ for
        // languageTag, return that subdir + t.fileName(). Single point of change for the language axis.
        return t.fileName();
    }
}
