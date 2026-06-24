/*
 * ============================================================================
 * Name        : NavigationPolicy.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Decides whether a URL stays in the WebView (local IIAB server) or opens externally.
 * ============================================================================
 */
package org.iiab.controller.portal.domain;

/**
 * Pure navigation policy for the portal WebView. Hosts served by the on-device
 * IIAB server are kept inside the WebView; everything else is "external" and
 * should be handed to the system browser.
 */
public final class NavigationPolicy {

    private NavigationPolicy() {}

    /** True if {@code host} is served by the local IIAB server (stay in WebView). */
    public static boolean isInternalHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        return h.equals("box") || h.equals("localhost") || h.equals("127.0.0.1");
    }

    /** Inverse of {@link #isInternalHost(String)} — should open in an external app. */
    public static boolean isExternalHost(String host) {
        return !isInternalHost(host);
    }
}
