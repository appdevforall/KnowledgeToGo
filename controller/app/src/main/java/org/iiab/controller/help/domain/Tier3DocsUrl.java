/*
 * ============================================================================
 * Name        : Tier3DocsUrl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Single source of truth for the tier-3 docs route (ADFA-4616).
 * ============================================================================
 */
package org.iiab.controller.help.domain;

/**
 * Builds tier-3 documentation URLs. Tooltip IDs are the source of truth for article
 * paths: {@code /k2go-docs/<tooltipId>} on the local server. Change the route here only.
 */
public final class Tier3DocsUrl {

    /** Base URL of the tier-3 docs module (served by nginx on :8085). */
    public static final String BASE = "http://localhost:8085/k2go-docs/";

    private Tier3DocsUrl() {}

    /** Docs home (module index). */
    public static String home() {
        return BASE;
    }

    /** URL for a specific article keyed by tooltip id; falls back to {@link #home()} when blank. */
    public static String forTooltip(String tooltipId) {
        if (tooltipId == null) {
            return BASE;
        }
        String id = tooltipId.trim();
        if (id.isEmpty()) {
            return BASE;
        }
        return BASE + id;
    }
}
