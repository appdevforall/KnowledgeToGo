/*
 * ============================================================================
 * Name        : SenderReach.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Does the sender answer on its rsync port over Wi-Fi (K2GO-584)?
 *               Two phones on one Wi-Fi network set up a direct link (TDLS) by
 *               themselves under heavy traffic. When the sender then starts a
 *               Wi-Fi Direct group, some drivers end their side of that link
 *               without telling the receiver, which keeps sending into the dead
 *               link until Wi-Fi restarts. So a failed connect drops the link
 *               on this phone and tries once more, through the router.
 *               https://developer.android.com/reference/android/net/wifi/WifiManager#setTdlsEnabled(java.net.InetAddress,%20boolean)
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class SenderReach {

    private static final String TAG = "SenderReach";

    private SenderReach() {
    }

    /**
     * The paired sender at {@code host:port} accepts a connection, after one direct-link drop if
     * needed. Only for the sender's own address: a router has no direct link to drop.
     */
    public static boolean connect(Context c, String host, int port, int timeoutMs) {
        if (host == null) return false;
        String why = failure(host, port, timeoutMs);
        if (why == null) return true;
        Log.i(TAG, host + ":" + port + " does not answer (" + why + ")");   // refused vs timeout
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return dropDirectLink(wm, host) && failure(host, port, timeoutMs) == null;
    }

    /** A plain connect, with no repair. */
    public static boolean answers(String host, int port, int timeoutMs) {
        return host != null && failure(host, port, timeoutMs) == null;
    }

    /** Null when the connect works, else the reason. */
    private static String failure(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** False on a phone without TDLS: then there is no link to drop. */
    private static boolean dropDirectLink(WifiManager wm, String host) {
        if (wm == null || !wm.isTdlsSupported()) return false;
        try {
            wm.setTdlsEnabled(InetAddress.getByName(host), false);   // a literal address: no DNS
            Log.i(TAG, "dropped the direct Wi-Fi link to " + host);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "could not drop the direct Wi-Fi link: " + e.getMessage());
            return false;
        }
    }
}
