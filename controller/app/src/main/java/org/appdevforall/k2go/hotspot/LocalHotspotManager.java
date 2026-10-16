/*
 * ============================================================================
 * Name        : LocalHotspotManager.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Owns a Wi-Fi LocalOnlyHotspot reservation (ADFA-4520). Unlike the
 *               system "Mobile Hotspot" toggle, LocalOnlyHotspot never goes through
 *               the carrier tethering-entitlement check, so it works with no SIM and
 *               no data plan. The reservation is process-bound: it stays up while this
 *               app process is alive and is torn down on close() or process death.
 *               API 26+ only; callers must gate on Build.VERSION and hold CHANGE_WIFI_STATE
 *               plus the hotspot permission from HotspotPermissions (NEARBY_WIFI_DEVICES on
 *               API 33+, or ACCESS_FINE_LOCATION with Location services on 32 and below).
 *
 *               V1 scope (ADFA-4520): manual opt-in from Advanced settings, plus a
 *               contextual recommendation surfaced only when BOTH conditions hold
 *               (no SIM AND the native hotspot was tried and did not come up).
 *               Keeping the reservation alive across app backgrounding via the
 *               foreground WatchdogService is a deliberate follow-up.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.appdevforall.k2go.sync.transport.NetworkInterfaces;

/**
 * App-scoped singleton that starts/stops a LocalOnlyHotspot and publishes its state.
 * Not an Android component; the reservation lives as long as the process does.
 */
public final class LocalHotspotManager {

    private static final String TAG = "IIAB-LocalHotspot";

    public enum Phase { OFF, STARTING, ON, FAILED }

    /** Immutable snapshot of the hotspot state for the UI to render. */
    public static final class State {
        public final Phase phase;
        public final String ssid;       // non-null only when phase == ON
        public final String passphrase; // non-null only when phase == ON
        public final int failureReason; // valid only when phase == FAILED
        // K2GO-375: the AP interface's reachable IPv4, owned here (the reservation's owner) so both
        // Connect and Clone read one source instead of each re-deriving it. Non-null only once the AP
        // interface has been assigned its address: ON is first emitted with this null (the reservation
        // reports started before the interface has an IP), then re-emitted with the resolved value.
        public final String hotspotIp;

        State(Phase phase, String ssid, String passphrase, int failureReason, String hotspotIp) {
            this.phase = phase;
            this.ssid = ssid;
            this.passphrase = passphrase;
            this.failureReason = failureReason;
            this.hotspotIp = hotspotIp;
        }

        static State off() { return new State(Phase.OFF, null, null, 0, null); }
        static State starting() { return new State(Phase.STARTING, null, null, 0, null); }
        static State on(String ssid, String pass, String ip) { return new State(Phase.ON, ssid, pass, 0, ip); }
        static State failed(int reason) { return new State(Phase.FAILED, null, null, reason, null); }
    }

    private static final LocalHotspotManager INSTANCE = new LocalHotspotManager();

    public static LocalHotspotManager get() { return INSTANCE; }

    private final MutableLiveData<State> state = new MutableLiveData<>(State.off());

    // K2GO-375: the AP interface gets its IPv4 a beat after onStarted fires, and that assignment is not
    // a default-network change (the AP is never the default network), so no ConnectivityManager callback
    // announces it. Poll for it on the main looper and re-emit State once it lands. Bounded so it can
    // never spin forever; generation invalidates a poll the moment a newer start()/stop() supersedes it.
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private static final long AP_IP_POLL_MS = 1000L;
    private static final int AP_IP_MAX_POLLS = 120;   // ~2 min safety cap (mirrors CloneFragment's budget)
    private int generation = 0;

    // Object type kept generic so this file compiles on API < 26; cast at use sites.
    private Object reservation; // WifiManager.LocalOnlyHotspotReservation

    // K2GO-127: the preferred fixed-credential Wi-Fi Direct path. Non-null while a WFD group is
    // attempting or up; the LocalOnlyHotspot is the fallback below it. At most one is active.
    private Object wifiDirect;  // WifiDirectHotspotManager (Object so this file compiles on API < 29)
    private org.appdevforall.k2go.hotspot.data.HotspotCredentialsStore credsStore;

    // K2GO-127: synchronous re-entry guard. ensureHotspot() calls start() on setMode AND on every
    // render; the STARTING LiveData state is posted asynchronously, so two rapid calls both passed
    // the phase check and raced (Wi-Fi Direct + LocalOnlyHotspot started at once). This flag blocks
    // re-entry immediately and is cleared the moment any terminal state (ON/OFF/FAILED) is published.
    private volatile boolean starting;

    // Reactive half of the AND trigger: set when the user opens the native hotspot
    // settings, so the UI can later check "tried native + still no hotspot + no SIM".
    private volatile boolean nativeHotspotAttempted = false;

    private LocalHotspotManager() {}

    public LiveData<State> state() { return state; }

    // K2GO-127: every state change flows through here so the synchronous re-entry guard clears on any
    // terminal state. STARTING keeps the guard set (a start is still in flight, e.g. WFD -> LOH fallback).
    private void publish(State s) {
        if (s.phase != Phase.STARTING) starting = false;
        state.postValue(s);
    }

    /** True when the underlying API is available (Android 8.0+). */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * Whether this phone can hold a FIXED hotspot name/password/band (Wi-Fi Direct, API 29+). Below
     * this the hotspot falls back to LocalOnlyHotspot with random credentials, so a custom name,
     * password or band does not apply. One owner of this fact for both start()'s path selection and
     * the settings UI.
     */
    public static boolean supportsFixedCredentials(Context ctx) {
        return Build.VERSION.SDK_INT >= 29
                && ctx.getApplicationContext().getPackageManager()
                        .hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT);
    }

    public void markNativeHotspotAttempted() { nativeHotspotAttempted = true; }
    public boolean wasNativeHotspotAttempted() { return nativeHotspotAttempted; }
    public void clearNativeHotspotAttempted() { nativeHotspotAttempted = false; }

    public boolean isOn() {
        State s = state.getValue();
        return s != null && s.phase == Phase.ON;
    }

    /**
     * Starts a LocalOnlyHotspot. Caller must have already granted CHANGE_WIFI_STATE and the
     * hotspot permission (see HotspotPermissions); on API 32 and below that also needs Location
     * services on. Otherwise onFailed fires.
     */
    // NewApi is suppressed because every API-26 reference below is protected by the
    // explicit Build.VERSION.SDK_INT guard (lint cannot see through isSupported(), and
    // @RequiresApi does not cover the anonymous callback class). MissingPermission is
    // suppressed because CHANGE_WIFI_STATE and the hotspot permission (HotspotPermissions) are
    // requested at runtime.
    @SuppressLint({"MissingPermission", "NewApi"})
    public void start(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            publish(State.failed(0));
            return;
        }
        if (starting) return;   // synchronous guard: the STARTING LiveData state is posted async
        State cur = state.getValue();
        if (cur != null && cur.phase == Phase.ON) {
            return; // already up
        }
        starting = true;
        final Context app = context.getApplicationContext();
        generation++;   // K2GO-375: a fresh attempt invalidates any poll left over from a prior reservation
        publish(State.starting());
        // K2GO-127: prefer a fixed-credential Wi-Fi Direct group (API 29+, if the phone reports
        // support). Any failure or incapability falls back to the proven LocalOnlyHotspot.
        if (supportsFixedCredentials(app)) {
            startWifiDirect(app);
            return;
        }
        startLocalOnly(app);
    }

    @androidx.annotation.RequiresApi(29)
    private void startWifiDirect(final Context app) {
        if (credsStore == null) {
            credsStore = new org.appdevforall.k2go.hotspot.data.HotspotCredentialsStore(app);
        }
        final int gen = generation;
        final org.appdevforall.k2go.hotspot.data.HotspotCredentialsStore store = credsStore;
        // Resolve the stored credentials off the main thread (the first SharedPreferences read hits
        // disk), then launch Wi-Fi Direct back on the main thread where the P2P callbacks must run.
        org.appdevforall.k2go.util.AppExecutors.get().io().execute(() -> {
            final org.appdevforall.k2go.hotspot.data.HotspotCredentialsStore.Creds c = store.resolve();
            final org.appdevforall.k2go.hotspot.domain.HotspotBand band = store.band();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (gen != generation) return;   // superseded by a newer start/stop while resolving
                launchWifiDirect(app, c, band, gen);
            });
        });
    }

    @androidx.annotation.RequiresApi(29)
    private static int p2pBand(org.appdevforall.k2go.hotspot.domain.HotspotBand band) {
        switch (band) {
            case FIVE_GHZ:
                return android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_5GHZ;
            case AUTO:
                return android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_AUTO;
            default:
                return android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_2GHZ;
        }
    }

    @androidx.annotation.RequiresApi(29)
    private void launchWifiDirect(final Context app,
            final org.appdevforall.k2go.hotspot.data.HotspotCredentialsStore.Creds c,
            final org.appdevforall.k2go.hotspot.domain.HotspotBand band, final int gen) {
        final WifiDirectHotspotManager m = new WifiDirectHotspotManager(app);
        wifiDirect = m;
        m.start(c.name, c.passphrase, p2pBand(band),
                new WifiDirectHotspotManager.Callbacks() {
                    @Override public void onUp(String ssid, String pass, String ip) {
                        if (gen != generation) { m.stop(); return; }   // superseded by a newer start/stop
                        Log.d(TAG, "Wi-Fi Direct up, ssid=" + ssid);
                        publish(State.on(ssid, pass, ip));
                    }
                    @Override public void onFailed(String reason) {
                        Log.w(TAG, "Wi-Fi Direct unavailable, falling back to LocalOnlyHotspot: " + reason);
                        if (gen != generation) return;
                        wifiDirect = null;
                        startLocalOnly(app);   // the proven fallback (random credentials)
                    }
                    @Override public void onLost(String reason) {
                        Log.w(TAG, "Wi-Fi Direct group lost: " + reason);
                        if (gen != generation) return;
                        wifiDirect = null;
                        publish(State.off());
                    }
                });
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    private void startLocalOnly(Context context) {
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            publish(State.failed(WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC));
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    reservation = res;
                    String ssid = null, pass = null;
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                && res.getSoftApConfiguration() != null) {
                            android.net.wifi.SoftApConfiguration c = res.getSoftApConfiguration();
                            ssid = c.getSsid();
                            pass = c.getPassphrase();
                        } else if (res.getWifiConfiguration() != null) {
                            android.net.wifi.WifiConfiguration wc = res.getWifiConfiguration();
                            ssid = wc.SSID;
                            pass = wc.preSharedKey;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Could not read hotspot config: " + t.getMessage());
                    }
                    ssid = unquote(ssid);
                    pass = unquote(pass);
                    Log.d(TAG, "LocalOnlyHotspot started, ssid=" + ssid);
                    // ON is emitted with no IP yet; the poll re-emits once the AP interface has one.
                    publish(State.on(ssid, pass, null));
                    beginHotspotIpPoll(ssid, pass);
                }

                @Override
                public void onStopped() {
                    Log.d(TAG, "LocalOnlyHotspot stopped");
                    reservation = null;
                    generation++;   // K2GO-375: stop any in-flight AP-IP poll
                    publish(State.off());
                }

                @Override
                public void onFailed(int reason) {
                    Log.w(TAG, "LocalOnlyHotspot failed, reason=" + reason);
                    reservation = null;
                    generation++;   // K2GO-375: stop any in-flight AP-IP poll
                    publish(State.failed(reason));
                }
            }, handler);
        } catch (SecurityException se) {
            Log.w(TAG, "startLocalOnlyHotspot denied: " + se.getMessage());
            publish(State.failed(WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC));
        } catch (IllegalStateException ise) {
            // e.g. Wi-Fi off, or a hotspot already active.
            Log.w(TAG, "startLocalOnlyHotspot illegal state: " + ise.getMessage());
            publish(State.failed(WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE));
        }
    }

    /** Tears down the reservation, if any. */
    @SuppressLint("NewApi")
    public void stop() {
        // K2GO-127: tear down a Wi-Fi Direct group if that path is active (it outlives the process,
        // so an explicit removeGroup matters). WifiDirectHotspotManager.stop() is safe when idle.
        Object wd = wifiDirect;
        wifiDirect = null;
        if (Build.VERSION.SDK_INT >= 29 && wd instanceof WifiDirectHotspotManager) {
            try { ((WifiDirectHotspotManager) wd).stop(); }
            catch (Throwable t) { Log.w(TAG, "Error stopping Wi-Fi Direct: " + t.getMessage()); }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && reservation instanceof WifiManager.LocalOnlyHotspotReservation) {
                ((WifiManager.LocalOnlyHotspotReservation) reservation).close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error closing reservation: " + t.getMessage());
        } finally {
            reservation = null;
            generation++;   // K2GO-375: stop any in-flight AP-IP poll
            publish(State.off());
        }
    }

    /**
     * K2GO-375: resolve the AP interface's IPv4 and re-emit State with it. Runs on the main looper,
     * re-scheduling itself once a second until the address appears or the safety cap is hit. Bails the
     * instant its generation is superseded (a newer start/stop) or the phase is no longer ON, so it never
     * outlives the reservation it was started for.
     */
    private void beginHotspotIpPoll(final String ssid, final String pass) {
        final int gen = generation;
        pollHandler.post(new Runnable() {
            private int tries = 0;
            @Override public void run() {
                if (gen != generation) return;                      // superseded by a newer start/stop
                State cur = state.getValue();
                if (cur == null || cur.phase != Phase.ON) return;   // no longer ON
                if (cur.hotspotIp != null) return;                  // already resolved
                String ip = NetworkInterfaces.discover().hotspotIp;
                if (ip != null) {
                    publish(State.on(ssid, pass, ip));              // resolved: re-emit through the one path
                    return;
                }
                if (++tries >= AP_IP_MAX_POLLS) return;             // safety cap; never spin forever
                pollHandler.postDelayed(this, AP_IP_POLL_MS);
            }
        });
    }

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
