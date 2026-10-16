/*
 * ============================================================================
 * Name        : ApAddressResolver.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-471. The ONE producer of the AP IPv4. Gathers candidates from the framework-
 *               authoritative source (ConnectivityManager + LinkProperties, which is NOT subject to
 *               the raw java.net interface-enumeration limits of Android 11+) AND from the java.net
 *               scan, then hands all of them to the single ApAddress decision. Both the Wi-Fi Direct
 *               path and the LocalOnlyHotspot path call this, so there is one producer, not two.
 *               Returns null when the address is not up yet: the caller polls and, on exhaustion,
 *               publishes the unresolved terminal. It never falls back to a 192.168.49.1 guess.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot.data;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import org.appdevforall.k2go.hotspot.domain.ApAddress;
import org.appdevforall.k2go.sync.transport.NetworkInterfaces;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public final class ApAddressResolver {

    private ApAddressResolver() {}

    /**
     * Resolve the AP IPv4 once, or null if it is not up yet. [ifaceHint] is the interface the AP
     * mechanism named (the Wi-Fi Direct group interface), or null for LocalOnlyHotspot.
     */
    public static String resolveOnce(Context ctx, String ifaceHint) {
        List<ApAddress.Candidate> candidates = new ArrayList<>(NetworkInterfaces.candidates());  // one scan
        String wifiIp = NetworkInterfaces.stationWifiIp(candidates);  // excluded so we never serve the join net
        candidates.addAll(linkPropertyCandidates(ctx));              // framework-authoritative source
        return ApAddress.pick(candidates, ifaceHint, wifiIp);
    }

    /** ConnectivityManager/LinkProperties candidates. Best-effort: any failure yields an empty list. */
    private static List<ApAddress.Candidate> linkPropertyCandidates(Context ctx) {
        List<ApAddress.Candidate> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return out;
            Network[] nets = cm.getAllNetworks();
            if (nets == null) return out;
            for (Network n : nets) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                String iface = lp.getInterfaceName();
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    out.add(new ApAddress.Candidate(iface, a.getHostAddress(), true,
                            a.isSiteLocalAddress(), false));
                }
            }
        } catch (Exception ignored) {
            // A locked-down build may deny this: fall back to the java.net candidates already gathered.
        }
        return out;
    }
}
