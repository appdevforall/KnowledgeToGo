/*
 * ============================================================================
 * Name        : HostScope.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-580. Is a host on this device or the local network, or on the internet?
 *               Local means loopback, a private or link-local IP literal, or a *.localhost /
 *               *.local name: the box, a hotspot, a router with no uplink. Everything else is the
 *               internet. Pure JVM; no DNS lookup (a name is never resolved to decide).
 * ============================================================================
 */
package org.appdevforall.k2go.networkpolicy.domain;

import java.util.Locale;

public final class HostScope {

    private HostScope() {}

    /** True for a host the request cannot leave the local network for (or no host at all). */
    public static boolean isLocal(String host) {
        if (host == null || host.isEmpty()) return true;   // data:, blob:, about: and the like
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")) return true;
        if (h.indexOf(':') >= 0) return isLocalV6(h);
        String[] p = h.split("\\.", -1);
        if (p.length != 4) return false;   // a domain name: the internet
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            try { o[i] = Integer.parseInt(p[i]); } catch (NumberFormatException e) { return false; }
            if (o[i] < 0 || o[i] > 255) return false;
        }
        return o[0] == 127 || o[0] == 10
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 169 && o[1] == 254);
    }

    /** ::1, link-local fe80::/10, unique-local fc00::/7. */
    private static boolean isLocalV6(String h) {
        return h.equals("::1") || h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea")
                || h.startsWith("feb") || h.startsWith("fc") || h.startsWith("fd");
    }
}
