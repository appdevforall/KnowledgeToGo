/*
 * ============================================================================
 * Name        : ApkShareName.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule: the coloquial file name the shared APK travels
 *               under when a peer downloads it over the Share tab. Stamps the
 *               brand, the build version and the arch so the receiver can tell
 *               exactly which build they were handed offline, instead of the old
 *               "-Latest" (true but ambiguous). Pure, no android.*; the single
 *               source of truth for the download name so the HTTP header and the
 *               QR URL cannot drift apart. ADFA-4540.
 * ============================================================================
 */
package org.iiab.controller.sync.domain;

public final class ApkShareName {

    private ApkShareName() {
    }

    /** Coloquial brand used for the shared APK file name. */
    public static final String BRAND = "K2Go";

    private static final String FALLBACK = "unknown";

    /**
     * Build the download file name, e.g. {@code K2Go-v0.4.1-beta-arm64-v8a.apk}.
     * Both parts are sanitised so the result is always a safe file name and a
     * safe URL path segment (only [A-Za-z0-9._-]).
     *
     * @param version app version name, e.g. {@code BuildConfig.VERSION_NAME}
     * @param arch    running ABI, e.g. {@code arm64-v8a}
     */
    public static String fileName(String version, String arch) {
        return BRAND + "-" + sanitize(version) + "-" + sanitize(arch) + ".apk";
    }

    private static String sanitize(String s) {
        if (s == null) {
            return FALLBACK;
        }
        String t = s.trim()
                .replaceAll("[^A-Za-z0-9._-]", "-") // drop anything not URL/file safe
                .replaceAll("-{2,}", "-")            // collapse runs
                .replaceAll("^[-.]+|[-.]+$", "");    // trim leading/trailing separators
        return t.isEmpty() ? FALLBACK : t;
    }
}
