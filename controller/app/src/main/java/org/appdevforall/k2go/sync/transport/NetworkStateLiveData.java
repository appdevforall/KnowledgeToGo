/*
 * ============================================================================
 * Name        : NetworkStateLiveData.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : App-scoped LiveData that emits whenever the device's default
 *               network changes (ADFA-5064). The Connect and Clone tabs build
 *               their QR from NetworkInterfaces.discover(), which is only re-read
 *               inside render(); render() previously ran on hotspot-state changes
 *               and taps but never on a radio-driven change (Wi-Fi turned on/off
 *               from outside the app), so a QR drawn with no network stayed blank
 *               after the user connected. Observing this LiveData with the view
 *               lifecycle gives both tabs the missing reactive trigger: on a
 *               network change they just call render() again, which re-reads the
 *               IP and redraws (or falls back to the "join Wi-Fi" empty state).
 *
 *               Wraps ConnectivityManager.registerDefaultNetworkCallback (API 24+,
 *               matches minSdk) and needs only ACCESS_NETWORK_STATE (already held).
 *               The callback is registered in onActive() and released in
 *               onInactive(), so it only listens while a tab is actually observing.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.transport;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;

/**
 * Emits a monotonic token on each default-network change (available / lost /
 * link-properties). Observers ignore the value; they use it purely as a "re-read
 * the network and redraw" signal.
 */
public final class NetworkStateLiveData extends LiveData<Long> {

    private static NetworkStateLiveData instance;

    /** App-scoped singleton so Connect and Clone share one registration. */
    public static synchronized NetworkStateLiveData get(Context ctx) {
        if (instance == null) {
            instance = new NetworkStateLiveData(ctx.getApplicationContext());
        }
        return instance;
    }

    // Coalesce the burst of callbacks Wi-Fi association fires (associate -> DHCP ->
    // link-properties) into a single redraw a beat after things settle.
    private static final long DEBOUNCE_MS = 250L;

    private final ConnectivityManager cm;
    private final Handler main = new Handler(Looper.getMainLooper());
    // Skip stragglers: a callback in flight on a binder thread can post here just after
    // onInactive() unregistered, so only emit while a tab is actually observing.
    private final Runnable emit = () -> { if (hasActiveObservers()) setValue(System.nanoTime()); };
    private ConnectivityManager.NetworkCallback callback;

    private NetworkStateLiveData(Context appCtx) {
        cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private void schedule() {
        main.removeCallbacks(emit);
        main.postDelayed(emit, DEBOUNCE_MS);
    }

    @Override
    protected void onActive() {
        if (cm == null || callback != null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network n) { schedule(); }
            @Override public void onLost(@NonNull Network n) { schedule(); }
            // The IP appears here (DHCP lease), which is exactly when discover() starts
            // returning a wifiIp — the signal that lets a blank QR finally draw.
            @Override public void onLinkPropertiesChanged(@NonNull Network n, @NonNull LinkProperties lp) { schedule(); }
        };
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException e) {
            callback = null;   // extremely rare; leave the tab on its existing triggers
        }
    }

    @Override
    protected void onInactive() {
        main.removeCallbacks(emit);
        if (cm != null && callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
            }
            callback = null;
        }
    }
}
