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

    /** Label for an APK that carries native libs for more than one ABI. */
    public static final String UNIVERSAL = "universal";

    /** Label for an APK that carries no native libs at all. */
    public static final String NOARCH = "noarch";

    /**
     * Build the download file name, e.g. {@code K2Go-v0.4.1-beta-arm64-v8a.apk}.
     * Both parts are sanitised so the result is always a safe file name and a
     * safe URL path segment (only [A-Za-z0-9._-]).
     *
     * @param version app version name, e.g. {@code BuildConfig.VERSION_NAME}
     * @param arch    the APK's arch label, e.g. {@code arm64-v8a} or {@code universal}
     */
    public static String fileName(String version, String arch) {
        return BRAND + "-" + sanitize(version) + "-" + sanitize(arch) + ".apk";
    }

    /**
     * Decide the arch label from the ABIs actually packaged in the shared APK
     * (the {@code lib/<abi>/} folders), NOT from the device. This is the whole
     * point of ADFA-4540: the label must describe the file that travels, so a
     * universal build shared from a 64-bit phone is named {@code universal},
     * not {@code arm64-v8a}.
     *
     * @param abis distinct ABI folder names found under {@code lib/} in the APK
     * @return {@code universal} for 2+, the single ABI for exactly one,
     *         {@code noarch} for none
     */
    public static String archLabel(java.util.Collection<String> abis) {
        if (abis == null || abis.isEmpty()) {
            return NOARCH;
        }
        if (abis.size() >= 2) {
            return UNIVERSAL;
        }
        return sanitize(abis.iterator().next());
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
