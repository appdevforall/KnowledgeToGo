/*
 * ============================================================================
 * Name        : WifiNetworkBinder.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4496. Pins the app's sockets to the local Network that
 *               actually reaches the Share host. The rsync transport talks to a
 *               peer that usually has no internet; when the only network
 *               (Wi-Fi or hotspot) is flagged "no internet", Android may not
 *               give the app a usable default network, so a plain socket has no
 *               route and the connection fails. Binding the *process* to the
 *               matching Network restores the route - and being process-level it
 *               also covers the native rsync child (ProcessBuilder), which a
 *               per-socket bind could not.
 *
 *               It selects the Network whose link subnet contains the host IP,
 *               so it covers both the Wi-Fi (wlan0) and the hotspot path (and is
 *               extensible to any future transport such as VPN). If no local
 *               Network matches (e.g. this device is itself the hotspot AP, where
 *               the route is direct), it does not bind. Bind only for the
 *               duration of a receive and release() when it ends, so the rest of
 *               the app keeps normal networking.
 * ============================================================================
 */
package org.iiab.controller.sync.transport;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;

public final class WifiNetworkBinder {

    private static final String TAG = "IIAB-WifiBinder";

    private WifiNetworkBinder() {
    }

    /** Bind the process to the local Network whose subnet contains hostIp. Returns true if bound. */
    public static boolean bindToHostNetwork(Context ctx, String hostIp) {
        if (ctx == null || hostIp == null) return false;
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        InetAddress host;
        try {
            host = InetAddress.getByName(hostIp);
        } catch (Exception e) {
            return false;
        }
        if (!(host instanceof Inet4Address)) return false;

        Network match = null;
        try {
            for (Network net : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress local = la.getAddress();
                    if (local instanceof Inet4Address && sameSubnet(local, host, la.getPrefixLength())) {
                        match = net;
                        break;
                    }
                }
                if (match != null) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Network enumeration failed", e);
        }

        if (match == null) {
            Log.i(TAG, "No local network matches host " + hostIp + "; not binding (direct/local route)");
            return false;
        }
        boolean ok = cm.bindProcessToNetwork(match);
        Log.i(TAG, "Bound process to " + match + " to reach host " + hostIp + " -> " + ok);
        return ok;
    }

    /** Release any process-level network binding (back to the system default). */
    public static void unbind(Context ctx) {
        if (ctx == null) return;
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        try {
            cm.bindProcessToNetwork(null);
            Log.i(TAG, "Released process network binding");
        } catch (Exception e) {
            Log.w(TAG, "Unbind failed", e);
        }
    }

    /** True if a and b share the first prefixLen bits (IPv4 subnet membership). */
    private static boolean sameSubnet(InetAddress a, InetAddress b, int prefixLen) {
        byte[] aa = a.getAddress();
        byte[] bb = b.getAddress();
        if (aa.length != bb.length) return false;
        int prefix = Math.max(0, Math.min(prefixLen, aa.length * 8));
        int fullBytes = prefix / 8;
        int rem = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (aa[i] != bb[i]) return false;
        }
        if (rem > 0) {
            int mask = 0xFF << (8 - rem);
            if ((aa[fullBytes] & mask) != (bb[fullBytes] & mask)) return false;
        }
        return true;
    }
}
