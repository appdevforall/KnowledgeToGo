/*
 * ============================================================================
 * Name        : NetworkInterfaces.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Shared LAN IP discovery for the Share feature. The java.net half of the single
 *               AP-IP producer (K2GO-471): it gathers interface candidates and the station Wi-Fi
 *               address, then delegates the "which IPv4 is the AP" decision to hotspot/domain/
 *               ApAddress (one tested rule, one place). The ConnectivityManager/LinkProperties half
 *               and the one-producer entry point live in hotspot/data/ApAddressResolver. Uses only
 *               java.net, so it stays reusable/clonable. Share-export, S14 step 3.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.transport;

import org.appdevforall.k2go.hotspot.domain.ApAddress;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NetworkInterfaces {

    private NetworkInterfaces() {
    }

    /** The device's current Wi-Fi and hotspot IPv4 addresses (either may be null). */
    public static final class LanIps {
        public final String wifiIp;
        public final String hotspotIp;

        public LanIps(String wifiIp, String hotspotIp) {
            this.wifiIp = wifiIp;
            this.hotspotIp = hotspotIp;
        }
    }

    /** Every up/down IPv4 interface flattened to ApAddress candidates (the java.net source). */
    public static List<ApAddress.Candidate> candidates() {
        List<ApAddress.Candidate> out = new ArrayList<>();
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                boolean up;
                try { up = intf.isUp(); } catch (Exception e) { up = false; }
                String name = intf.getName();
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address)) continue;
                    out.add(new ApAddress.Candidate(name, addr.getHostAddress(), up,
                            addr.isSiteLocalAddress(), addr.isLoopbackAddress()));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** The station Wi-Fi (wlan0) IPv4 from a candidate list, or null. One source for this rule. */
    public static String stationWifiIp(List<ApAddress.Candidate> cs) {
        if (cs == null) return null;
        for (ApAddress.Candidate c : cs) {
            if ("wlan0".equals(c.ifaceName) && c.up && !c.loopback && c.ipv4 != null) return c.ipv4;
        }
        return null;
    }

    /**
     * Scans the interfaces and returns the station Wi-Fi (wlan0) and the AP IPv4. The AP choice goes
     * through {@link ApAddress}, the single decision. For the full producer (which also consults
     * ConnectivityManager/LinkProperties), use {@code ApAddressResolver.resolveOnce}.
     */
    public static LanIps discover() {
        List<ApAddress.Candidate> cs = candidates();
        String wifiIp = stationWifiIp(cs);
        return new LanIps(wifiIp, ApAddress.pick(cs, null, wifiIp));
    }
}
