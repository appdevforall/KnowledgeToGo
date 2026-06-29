/*
 * ============================================================================
 * Name        : NetworkInterfaces.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Shared LAN IP discovery for the Share feature — the single copy
 *               of the Wi-Fi / hotspot interface scan previously duplicated
 *               verbatim in SyncFragment and QrActivity (tech-debt EX3). Uses
 *               only java.net (no android.*), so it is reusable/clonable. The
 *               categorization (wlan0 = Wi-Fi; ap.., swlan.., wlan1, wlan2 = hotspot)
 *               is unchanged. Share-export, S14 step 3.
 * ============================================================================
 */
package org.iiab.controller.sync.transport;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
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

    /** Scans the up interfaces and returns the Wi-Fi (wlan0) and hotspot IPv4 addresses. */
    public static LanIps discover() {
        String wifiIp = null;
        String hotspotIp = null;
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                String name = intf.getName();
                if (!intf.isUp()) continue;

                boolean isStrictWifi = name.equals("wlan0");
                boolean isHotspot = name.startsWith("ap") || name.startsWith("swlan")
                        || name.equals("wlan1") || name.equals("wlan2");

                if (isStrictWifi || isHotspot) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            if (isStrictWifi) wifiIp = addr.getHostAddress();
                            if (isHotspot) hotspotIp = addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new LanIps(wifiIp, hotspotIp);
    }
}
