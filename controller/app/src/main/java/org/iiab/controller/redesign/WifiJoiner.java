/*
 * ============================================================================
 * Name        : WifiJoiner.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Scan-to-join helper for the Clone Receive flow (ADFA-4781).
 *               Connects the app to a specific Wi-Fi (e.g. the sender's local
 *               hotspot) parsed from a scanned WIFI: QR, using the Wi-Fi Network
 *               Request API (WifiNetworkSpecifier + requestNetwork, API 29+).
 *               The connection is app-scoped and the process is bound to it so
 *               the rsync pull runs over that network. Below API 29 the caller
 *               falls back to Skip (manual join in Wi-Fi settings).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;

import androidx.annotation.RequiresApi;

public final class WifiJoiner {

    public interface Callback {
        void onJoined();
        void onFailed(String reason);
    }

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback callback;

    /** Parse "WIFI:S:<ssid>;T:WPA;P:<pass>;;". Returns {ssid, pass} or null if not a Wi-Fi QR. */
    public static String[] parseWifiQr(String q) {
        if (q == null || !q.startsWith("WIFI:")) return null;
        String body = q.substring(5);
        String ssid = null, pass = "";
        for (String part : body.split(";")) {
            if (part.startsWith("S:")) ssid = part.substring(2);
            else if (part.startsWith("P:")) pass = part.substring(2);
        }
        if (ssid == null || ssid.isEmpty()) return null;
        return new String[]{ssid, pass};
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public void join(Context ctx, String ssid, String pass, Callback ui) {
        Context app = ctx.getApplicationContext();
        cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) { ui.onFailed("Network service unavailable."); return; }

        WifiNetworkSpecifier.Builder sb = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        if (pass != null && !pass.isEmpty()) sb.setWpa2Passphrase(pass);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(sb.build())
                .build();

        final boolean[] done = {false};
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (done[0]) return;
                done[0] = true;
                cm.bindProcessToNetwork(network);
                ui.onJoined();
            }
            @Override public void onUnavailable() {
                if (done[0]) return;
                done[0] = true;
                ui.onFailed("Couldn't join that network. Scan the join code again, or connect in Wi-Fi settings and Skip.");
            }
        };
        try {
            cm.requestNetwork(req, callback, 30000);
        } catch (Exception e) {
            ui.onFailed("Couldn't start the join request.");
        }
    }

    /** Unregister the pending request (does not touch the process binding). */
    public void cancel() {
        if (cm != null && callback != null) {
            try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        }
        callback = null;
    }

    /** Drop the process binding and the app-scoped Wi-Fi request (after the transfer ends). */
    public void release() {
        if (cm != null) {
            try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
            if (callback != null) { try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {} }
        }
        callback = null;
    }
}
