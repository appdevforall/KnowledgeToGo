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
 *               API 26+ only; callers must gate on Build.VERSION and hold
 *               CHANGE_WIFI_STATE + ACCESS_FINE_LOCATION (with Location services on).
 *
 *               V1 scope (ADFA-4520): manual opt-in from Advanced settings, plus a
 *               contextual recommendation surfaced only when BOTH conditions hold
 *               (no SIM AND the native hotspot was tried and did not come up).
 *               Keeping the reservation alive across app backgrounding via the
 *               foreground WatchdogService is a deliberate follow-up.
 * ============================================================================
 */
package org.iiab.controller.hotspot;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

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

        State(Phase phase, String ssid, String passphrase, int failureReason) {
            this.phase = phase;
            this.ssid = ssid;
            this.passphrase = passphrase;
            this.failureReason = failureReason;
        }

        static State off() { return new State(Phase.OFF, null, null, 0); }
        static State starting() { return new State(Phase.STARTING, null, null, 0); }
        static State on(String ssid, String pass) { return new State(Phase.ON, ssid, pass, 0); }
        static State failed(int reason) { return new State(Phase.FAILED, null, null, reason); }
    }

    private static final LocalHotspotManager INSTANCE = new LocalHotspotManager();

    public static LocalHotspotManager get() { return INSTANCE; }

    private final MutableLiveData<State> state = new MutableLiveData<>(State.off());

    // Object type kept generic so this file compiles on API < 26; cast at use sites.
    private Object reservation; // WifiManager.LocalOnlyHotspotReservation

    // Reactive half of the AND trigger: set when the user opens the native hotspot
    // settings, so the UI can later check "tried native + still no hotspot + no SIM".
    private volatile boolean nativeHotspotAttempted = false;

    private LocalHotspotManager() {}

    public LiveData<State> state() { return state; }

    /** True when the underlying API is available (Android 8.0+). */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public void markNativeHotspotAttempted() { nativeHotspotAttempted = true; }
    public boolean wasNativeHotspotAttempted() { return nativeHotspotAttempted; }
    public void clearNativeHotspotAttempted() { nativeHotspotAttempted = false; }

    public boolean isOn() {
        State s = state.getValue();
        return s != null && s.phase == Phase.ON;
    }

    /**
     * Starts a LocalOnlyHotspot. Caller must have already granted CHANGE_WIFI_STATE +
     * ACCESS_FINE_LOCATION and enabled Location services; otherwise onFailed fires.
     */
    // NewApi is suppressed because every API-26 reference below is protected by the
    // explicit Build.VERSION.SDK_INT guard (lint cannot see through isSupported(), and
    // @RequiresApi does not cover the anonymous callback class). MissingPermission is
    // suppressed because CHANGE_WIFI_STATE + ACCESS_FINE_LOCATION are requested at runtime.
    @SuppressLint({"MissingPermission", "NewApi"})
    public void start(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            state.postValue(State.failed(0));
            return;
        }
        State cur = state.getValue();
        if (cur != null && (cur.phase == Phase.ON || cur.phase == Phase.STARTING)) {
            return; // already running / starting
        }
        WifiManager wifi = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) {
            state.postValue(State.failed(WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC));
            return;
        }
        state.postValue(State.starting());
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
                    state.postValue(State.on(ssid, pass));
                }

                @Override
                public void onStopped() {
                    Log.d(TAG, "LocalOnlyHotspot stopped");
                    reservation = null;
                    state.postValue(State.off());
                }

                @Override
                public void onFailed(int reason) {
                    Log.w(TAG, "LocalOnlyHotspot failed, reason=" + reason);
                    reservation = null;
                    state.postValue(State.failed(reason));
                }
            }, handler);
        } catch (SecurityException se) {
            Log.w(TAG, "startLocalOnlyHotspot denied: " + se.getMessage());
            state.postValue(State.failed(WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC));
        } catch (IllegalStateException ise) {
            // e.g. Wi-Fi off, or a hotspot already active.
            Log.w(TAG, "startLocalOnlyHotspot illegal state: " + ise.getMessage());
            state.postValue(State.failed(WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE));
        }
    }

    /** Tears down the reservation, if any. */
    @SuppressLint("NewApi")
    public void stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && reservation instanceof WifiManager.LocalOnlyHotspotReservation) {
                ((WifiManager.LocalOnlyHotspotReservation) reservation).close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error closing reservation: " + t.getMessage());
        } finally {
            reservation = null;
            state.postValue(State.off());
        }
    }

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
