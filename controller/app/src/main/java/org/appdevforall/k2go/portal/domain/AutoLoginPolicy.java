/*
 * ============================================================================
 * Name        : AutoLoginPolicy.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5361. Decides, from the URL alone, whether a portal page opens as the box
 *               admin (Calibre-Web "books" / Kolibri "courses") and under which path prefix the
 *               box fronts it. Single owner of that fact: ADFA-5043 kept it in a private method of
 *               one fragment and carried it as an Intent extra, so the two call sites that did not
 *               pass the extra opened Calibre-Web unauthenticated — which planted a guest session
 *               in the shared WebView cookie jar. Deriving it here means no call site can forget.
 *               Pure (no android.*), so it is JVM-unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.portal.domain;

/**
 * Maps a portal target URL to the service whose admin session the WebView should carry.
 *
 * <p>The service names are the ones the box's credential store uses
 * ({@code /k2go-api/auth/&lt;service&gt;/session}), which are NOT the URL segments: Calibre-Web is
 * served at {@code /books} but is called {@code calibre}. The path prefix is returned separately
 * because the cookie work needs it (see {@link SessionCookies}).
 */
public final class AutoLoginPolicy {

    /** Box credential-store service names. */
    public static final String CALIBRE = "calibre";
    public static final String KOLIBRI = "kolibri";

    private AutoLoginPolicy() {}

    /**
     * The auto-login service for {@code url}, or {@code null} when the page needs no admin
     * session. Only pages served by the local box qualify — the session cookie is an admin
     * credential and must never be minted for, or injected against, an external host.
     */
    public static String serviceFor(String url) {
        String segment = firstSegment(url);
        if (segment == null) return null;
        if (segment.equals("books")) return CALIBRE;
        if (segment.equals("kolibri")) return KOLIBRI;
        return null;
    }

    /**
     * The path prefix the box fronts the service under (e.g. {@code "/books"}), or {@code null}
     * when the URL has no auto-login service. Derived from the same segment as
     * {@link #serviceFor(String)} so the two can never disagree.
     */
    public static String prefixFor(String url) {
        if (serviceFor(url) == null) return null;
        return "/" + firstSegment(url);
    }

    /**
     * First path segment of an internal-host URL, lowercased; {@code null} if the URL is unusable,
     * points at an external host, or has no path segment.
     *
     * <p>Hand-parsed rather than via {@code android.net.Uri} because this layer stays pure.
     */
    private static String firstSegment(String url) {
        if (url == null) return null;
        String s = url.trim();
        if (s.isEmpty()) return null;

        int schemeEnd = s.indexOf("://");
        if (schemeEnd < 0) return null;          // relative URLs never reach the portal
        int authorityStart = schemeEnd + 3;

        int pathStart = indexOfAny(s, authorityStart, '/', '?', '#');
        String authority = pathStart < 0 ? s.substring(authorityStart) : s.substring(authorityStart, pathStart);
        if (!NavigationPolicy.isInternalHost(hostOf(authority))) return null;
        if (pathStart < 0 || s.charAt(pathStart) != '/') return null;   // no path at all

        String path = s.substring(pathStart + 1);
        int cut = indexOfAny(path, 0, '/', '?', '#');
        if (cut >= 0) path = path.substring(0, cut);
        path = path.trim().toLowerCase();
        return path.isEmpty() ? null : path;
    }

    /** Host of an {@code authority} ({@code user@host:port}), without credentials or port. */
    private static String hostOf(String authority) {
        String a = authority;
        int at = a.lastIndexOf('@');
        if (at >= 0) a = a.substring(at + 1);
        int colon = a.lastIndexOf(':');
        if (colon >= 0) a = a.substring(0, colon);
        return a;
    }

    /** First index at or after {@code from} of any of {@code chars}, or -1. */
    private static int indexOfAny(String s, int from, char... chars) {
        for (int i = from; i < s.length(); i++) {
            for (char c : chars) {
                if (s.charAt(i) == c) return i;
            }
        }
        return -1;
    }
}
