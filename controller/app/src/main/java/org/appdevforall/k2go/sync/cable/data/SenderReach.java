/*
 * ============================================================================
 * Name        : SenderReach.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Does the sender answer on its rsync port over Wi-Fi (K2GO-587)?
 *               Two phones on one Wi-Fi network set up a direct link (TDLS) by
 *               themselves under heavy traffic. When the sender then starts a
 *               Wi-Fi Direct group, some drivers end their side of that link
 *               without telling the receiver, which keeps sending into the dead
 *               link until Wi-Fi restarts. DirectLinkRepair decides when this
 *               phone drops the link and tries once more, through the router.
 *               https://developer.android.com/reference/android/net/wifi/WifiManager#setTdlsEnabled(java.net.InetAddress,%20boolean)
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.appdevforall.k2go.sync.cable.domain.DirectLinkRepair;
import org.appdevforall.k2go.sync.cable.domain.DirectLinkRepair.Outcome;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class SenderReach {

    private static final String TAG = "SenderReach";

    /** One check: does the sender answer, did this check use the repair, and why not, for the log. */
    public static final class Result {
        public final boolean answers;
        public final boolean repaired;
        public final String why;   // the last connect error, null when the sender answers

        Result(boolean answers, boolean repaired, String why) {
            this.answers = answers;
            this.repaired = repaired;
            this.why = why;
        }
    }

    /** One connect: its outcome and the error text. */
    private static final class Try {
        final Outcome outcome;
        final String why;

        Try(Outcome outcome, String why) {
            this.outcome = outcome;
            this.why = why;
        }
    }

    private SenderReach() {
    }

    /**
     * Connects to {@code host:port}. The caller owns the run of failed checks: {@code triedInThisRun}
     * comes from DirectLinkRepair.triedAfter, or false for a check that starts its own run.
     */
    public static Result connect(Context c, String host, int port, int timeoutMs, boolean triedInThisRun) {
        if (host == null) return new Result(false, false, "no address");
        Try first = attempt(host, port, timeoutMs);
        if (first.outcome == Outcome.ANSWERS) return new Result(true, false, null);
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!DirectLinkRepair.shouldRepair(first.outcome, host.equals(gateway(wm)), triedInThisRun)
                || !dropDirectLink(wm, host)) {
            return new Result(false, false, first.why);
        }
        Try second = attempt(host, port, timeoutMs);
        return new Result(second.outcome == Outcome.ANSWERS, true, second.why);
    }

    /** The Wi-Fi gateway (a router, or the sender on its own hotspot), or null with no DHCP lease. */
    @SuppressWarnings("deprecation") // getDhcpInfo: no permission needed for the gateway
    public static String gateway(WifiManager wm) {
        int le = wm != null ? wm.getDhcpInfo().gateway : 0;
        if (le == 0) return null;
        return (le & 0xFF) + "." + ((le >> 8) & 0xFF) + "." + ((le >> 16) & 0xFF) + "." + ((le >> 24) & 0xFF);
    }

    private static Try attempt(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return new Try(Outcome.ANSWERS, null);
        } catch (Exception e) {
            // No log here: before pairing the receiver checks the router every few seconds.
            // Android reports a refusal as ConnectException "... ECONNREFUSED (Connection refused)".
            String why = String.valueOf(e.getMessage());
            boolean refused = e instanceof ConnectException && why.contains("ECONNREFUSED");
            return new Try(refused ? Outcome.REFUSED : Outcome.NO_ANSWER, why);
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
