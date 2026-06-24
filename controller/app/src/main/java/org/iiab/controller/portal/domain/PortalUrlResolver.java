/*
 * ============================================================================
 * Name        : PortalUrlResolver.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Resolves the portal target URL, falling back to the local home page.
 * ============================================================================
 */
package org.iiab.controller.portal.domain;

/** Resolves the URL the portal should open, with a safe local fallback. */
public final class PortalUrlResolver {

    /** Local IIAB home, used when no target URL is supplied. */
    public static final String DEFAULT_URL = "http://localhost:8085/home";

    private PortalUrlResolver() {}

    /** Returns {@code rawUrl} when usable, otherwise {@link #DEFAULT_URL}. */
    public static String resolve(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return DEFAULT_URL;
        }
        return rawUrl;
    }
}
