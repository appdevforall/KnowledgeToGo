/*
 * ============================================================================
 * Name        : SessionCookies.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5361. Builds the cookie directives that install a freshly minted service
 *               session in the WebView: first EXPIRE the service's cookies on every path they can
 *               live at, then set the new ones. ADFA-5043 only ever set them at "path=/", which
 *               appends rather than replaces: a cookie of the same name at a deeper path (the box
 *               fronts Calibre-Web under /books) is a different cookie, is never overwritten, and
 *               wins in the Cookie header (RFC 6265 5.4 orders longer paths first; the service
 *               reads the first one). That is what turned one guest page load into a permanent
 *               guest session. Pure string work (no android.*), so it is JVM-unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.portal.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Directive builders for the WebView cookie jar. Both take the {@code Cookie}-style header the box
 * returns ({@code "name=value; name2=value2"}) — the names to reconcile come from that response, so
 * this class holds no hardcoded knowledge of Calibre-Web's or Kolibri's cookie names.
 */
public final class SessionCookies {

    private SessionCookies() {}

    /**
     * Directives that delete the cookies named in {@code cookieHeader} from every path the service
     * can have set them at, so the fresh ones cannot be shadowed by a stale copy.
     *
     * @param prefix the service's path prefix (e.g. {@code "/books"}), or {@code null} for root only
     */
    public static List<String> clearDirectives(String cookieHeader, String prefix) {
        List<String> out = new ArrayList<>();
        for (String name : names(cookieHeader)) {
            for (String path : clearPaths(prefix)) {
                out.add(name + "=; Path=" + path + "; Max-Age=0");
            }
        }
        return out;
    }

    /**
     * Directives that install {@code cookieHeader} host-wide, so every request under the service
     * prefix carries it. Unchanged from ADFA-5043 — the fix is the clear pass above, not the set.
     */
    public static List<String> setDirectives(String cookieHeader) {
        List<String> out = new ArrayList<>();
        for (String pair : pairs(cookieHeader)) {
            out.add(pair + "; path=/");
        }
        return out;
    }

    /** Cookie names in a {@code Cookie} header, in order, without duplicates. */
    static List<String> names(String cookieHeader) {
        Set<String> out = new LinkedHashSet<>();
        for (String pair : pairs(cookieHeader)) {
            out.add(pair.substring(0, pair.indexOf('=')).trim());
        }
        return new ArrayList<>(out);
    }

    /**
     * Every path a copy of the service's cookies can live at: the root the box-side login sets
     * (it talks to the service directly, with no prefix) and the prefix the WebView reaches it
     * through, which the service may set itself. {@code "/books"} and {@code "/books/"} are
     * distinct cookie paths, so both are cleared.
     */
    static List<String> clearPaths(String prefix) {
        List<String> out = new ArrayList<>();
        out.add("/");
        if (prefix == null) return out;
        String p = prefix.trim();
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty() || p.equals("/")) return out;
        out.add(p);
        out.add(p + "/");
        return out;
    }

    /** Well-formed {@code name=value} pairs of a {@code Cookie} header, trimmed. */
    private static List<String> pairs(String cookieHeader) {
        List<String> out = new ArrayList<>();
        if (cookieHeader == null) return out;
        for (String raw : cookieHeader.split(";")) {
            String pair = raw.trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;   // no '=' at all, or an empty name: not a usable cookie
            out.add(pair);
        }
        return out;
    }
}
