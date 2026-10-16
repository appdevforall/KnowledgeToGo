/*
 * ============================================================================
 * Name        : ApAddress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-471. Single source of the "which IPv4 serves the dashboard on the local AP"
 *               decision. Pure (no android.*), so every candidate source (Wi-Fi Direct group
 *               interface, LocalOnlyHotspot scan, ConnectivityManager LinkProperties) funnels its
 *               findings through one rule with one set of tests. Device-specific leaf cases (K2GO-465)
 *               are added here as new candidates and test rows, never as a second resolver.
 *               Returns null when nothing qualifies: the caller then publishes the unresolved
 *               terminal, it never falls back to a 192.168.49.1 guess (known wrong, K2GO-375).
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.domain;

import java.util.List;

public final class ApAddress {

    private ApAddress() {}

    /** One interface flattened to the few facts the decision needs (so this stays android-free). */
    public static final class Candidate {
        public final String ifaceName;   // may be null
        public final String ipv4;        // dotted quad; may be null
        public final boolean up;
        public final boolean siteLocal;  // 10.x / 172.16-31.x / 192.168.x
        public final boolean loopback;

        public Candidate(String ifaceName, String ipv4, boolean up, boolean siteLocal, boolean loopback) {
            this.ifaceName = ifaceName;
            this.ipv4 = ipv4;
            this.up = up;
            this.siteLocal = siteLocal;
            this.loopback = loopback;
        }
    }

    /**
     * Pick the AP's IPv4 from [candidates]. [hint] is the interface the AP mechanism named (the
     * Wi-Fi Direct group interface, or null for LocalOnlyHotspot); [wifiIp] is this device's station
     * Wi-Fi address, excluded so the join network is never served as the AP. Returns null when no
     * candidate qualifies.
     */
    public static String pick(List<Candidate> candidates, String hint, String wifiIp) {
        if (candidates == null) return null;
        // Hinted call (Wi-Fi Direct): bind STRICTLY to the named group interface and never fall through
        // to the generic passes. Trust its address even if not site-local (reads the REAL assigned IP,
        // not the removed 192.168.49.1 guess; covers odd OEM subnets, K2GO-375). Return null until that
        // interface has its IPv4, so the caller keeps polling its own group instead of grabbing a
        // lingering ap0 / second-p2p IP (K2GO-471 review).
        if (hint != null) {
            for (Candidate c : candidates) {
                if (usable(c) && hint.equals(c.ifaceName) && notWifi(c, wifiIp)) return c.ipv4;
            }
            return null;
        }
        // No hint (LocalOnlyHotspot). Pass 1: a known hotspot interface name (ap.., swlan.., wlan1/2).
        for (Candidate c : candidates) {
            if (usable(c) && isHotspotName(c.ifaceName) && notWifi(c, wifiIp)) return c.ipv4;
        }
        // Pass 2: any up, site-local IPv4 that is not the station Wi-Fi / VPN and not the Wi-Fi address.
        for (Candidate c : candidates) {
            if (usable(c) && c.siteLocal && !isStation(c.ifaceName) && notWifi(c, wifiIp)) return c.ipv4;
        }
        return null;
    }

    private static boolean usable(Candidate c) {
        return c != null && c.up && !c.loopback && c.ipv4 != null && !c.ipv4.isEmpty();
    }

    private static boolean notWifi(Candidate c, String wifiIp) {
        return wifiIp == null || !wifiIp.equals(c.ipv4);
    }

    // Known LocalOnlyHotspot / group-owner interface names across OEMs. The Wi-Fi Direct p2p.. interface
    // is intentionally NOT here: it is matched only via an explicit hint (the WFD path), so a lingering
    // p2p group never wins a no-hint LocalOnlyHotspot resolution (K2GO-471 review).
    private static boolean isHotspotName(String n) {
        return n != null && (n.startsWith("ap") || n.startsWith("swlan")
                || n.equals("wlan1") || n.equals("wlan2"));
    }

    /** The station Wi-Fi and VPN/point-to-point interfaces: never the AP the other phone joins. */
    private static boolean isStation(String n) {
        return n != null && (n.equals("wlan0") || n.startsWith("tun") || n.startsWith("ppp"));
    }
}
