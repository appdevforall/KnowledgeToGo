/*
 * ============================================================================
 * Name        : WifiDirectHotspotManager.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-127. Brings up a Wi-Fi Direct group-owner AP with a FIXED, caller-supplied
 *               name and passphrase (Android 10+ / API 29, where WifiP2pConfig.Builder can set
 *               them). A legacy Wi-Fi client joins it by name + password, and the AP always sits
 *               at 192.168.49.1 (fixed by the P2P spec's embedded DHCP). This is the preferred
 *               on-phone AP: unlike LocalOnlyHotspot, the credentials are stable across restarts,
 *               so a device that joined before rejoins by itself (K2GO-127).
 *
 *               Lifecycle: a single RunState (IDLE -> STARTING -> UP, and STOPPED on teardown)
 *               is the one owner of "is this group starting / up / gone". Every async callback
 *               (createGroup, the address poll, the group watcher) checks it first, so a stop()
 *               that nulls the channel mid-start turns pending callbacks into no-ops instead of
 *               NPEs. A watchdog guarantees exactly one terminal (onUp or onFailed) even if a
 *               framework callback never fires, so the caller's start guard can never stick.
 *
 *               A Wi-Fi Direct autonomous group OUTLIVES this process (it is not torn down when
 *               the app is killed, unlike a LocalOnlyHotspot reservation). So start() removes any
 *               lingering group before creating ours, and stop()/failure remove the group and
 *               close the channel. A broadcast receiver reports the group dying through onLost.
 *
 *               This class owns NO user-facing state: it reports through Callbacks, and the single
 *               public hotspot State is published by the caller (LocalHotspotManager), so there is
 *               one source of truth for the UI. Blueprint: LocalStore .../server/Hotspot.kt.
 *
 *               Permissions: CHANGE_WIFI_STATE plus NEARBY_WIFI_DEVICES (API 33+) or
 *               ACCESS_FINE_LOCATION (<= 32), all already declared; the caller grants them at
 *               runtime (HotspotPermissions) before calling start().
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;

/** Preferred on-phone AP: a fixed-credential Wi-Fi Direct group. See the file header. API 29+. */
@RequiresApi(29)
public final class WifiDirectHotspotManager {

    private static final String TAG = "IIAB-WifiDirect";
    private static final int ADDRESS_POLLS = 20;          // x500ms: wait for the group to get its IPv4
    private static final long START_TIMEOUT_MS = 20_000;  // overall start watchdog, guarantees a terminal

    /** Results flow back here; all callbacks run on the main thread, exactly one terminal per start(). */
    public interface Callbacks {
        /** The group is up and reachable at [ipv4] with the given credentials. */
        void onUp(String ssid, String passphrase, String ipv4);
        /** The group would not start (no capability, denied, invalid creds, or no address): fall back. */
        void onFailed(String reason);
        /** A running group died on its own (e.g. Wi-Fi switched off). */
        void onLost(String reason);
    }

    // The one owner of this manager's liveness; every async callback checks it before touching mgr/channel.
    private enum RunState { IDLE, STARTING, UP, STOPPED }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    private RunState runState = RunState.IDLE;
    private WifiP2pManager mgr;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver watcher;
    private Callbacks cb;
    private Runnable startWatchdog;

    public WifiDirectHotspotManager(Context ctx) { this.app = ctx.getApplicationContext(); }

    /**
     * Starts the group with the fixed [ssid]/[passphrase] on [band]
     * (WifiP2pConfig.GROUP_OWNER_BAND_2GHZ / _5GHZ / _AUTO). Reports through [callbacks] exactly once.
     */
    @SuppressLint("MissingPermission")
    public void start(String ssid, String passphrase, int band, Callbacks callbacks) {
        this.cb = callbacks;
        runState = RunState.STARTING;

        mgr = (WifiP2pManager) app.getSystemService(Context.WIFI_P2P_SERVICE);
        if (mgr == null) { fail("Wi-Fi Direct is not available on this phone"); return; }
        channel = mgr.initialize(app, Looper.getMainLooper(), null);
        if (channel == null) { fail("Wi-Fi Direct could not be initialized (Wi-Fi off?)"); return; }

        final WifiP2pConfig config;
        try {
            config = new WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(passphrase)
                    .setGroupOperatingBand(band)
                    .build();
        } catch (IllegalArgumentException e) {
            // Invalid name/passphrase (e.g. an edited value out of WPA2 bounds): fail over, do not crash.
            fail("invalid hotspot name or password: " + e.getMessage());
            return;
        }

        // Guarantee a terminal even if a framework callback never fires, so the caller never sticks.
        startWatchdog = () -> { if (runState == RunState.STARTING) fail("timed out starting the group"); };
        main.postDelayed(startWatchdog, START_TIMEOUT_MS);

        // A GO from an earlier run can linger past our death: clear it first, then create ours.
        safeRemoveGroup(() -> createGroup(config));
    }

    /** Tears down the group and stops watching. Safe to call at any time, including mid-start. */
    public void stop() { stop(null); }

    /**
     * Tears down the group and stops watching, then runs [onComplete] on the main thread. Safe to call
     * at any time, including mid-start. The channel is closed only AFTER removeGroup's callback fires:
     * closing it first aborts the pending async removeGroup, so the autonomous group would outlive the
     * process (K2GO-470). [onComplete] lets a terminal shutdown wait until the group is really gone.
     */
    @SuppressLint("MissingPermission")
    public void stop(Runnable onComplete) {
        runState = RunState.STOPPED;
        cancelWatchdog();
        unwatch();
        safeRemoveGroup(() -> {
            closeChannel();
            if (onComplete != null) onComplete.run();
        });
    }

    // ------------------------------------------------------------------ internals

    @SuppressLint("MissingPermission")
    private void createGroup(WifiP2pConfig config) {
        if (runState == RunState.STOPPED || mgr == null || channel == null) return;   // stopped mid-start
        try {
            mgr.createGroup(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { pollForAddress(ADDRESS_POLLS); }
                @Override public void onFailure(int reason) { fail(reasonText(reason)); }
            });
        } catch (SecurityException e) {
            fail("permission denied: " + e.getMessage());
        }
    }

    // The group reports its interface and address a beat after onSuccess; poll briefly.
    @SuppressLint("MissingPermission")
    private void pollForAddress(final int triesLeft) {
        if (runState == RunState.STOPPED || mgr == null || channel == null) return;   // stopped mid-start
        try {
            mgr.requestGroupInfo(channel, group -> {
                if (runState == RunState.STOPPED) return;
                String ip = (group == null) ? null : ipv4Of(group.getInterface());
                if (group != null && ip != null) {
                    runState = RunState.UP;
                    cancelWatchdog();
                    watchGroup();
                    Log.i(TAG, "group up: " + group.getNetworkName() + " at " + ip);
                    if (cb != null) cb.onUp(group.getNetworkName(), group.getPassphrase(), ip);
                } else if (triesLeft > 0) {
                    main.postDelayed(() -> pollForAddress(triesLeft - 1), 500);
                } else {
                    // Remove the group FIRST, then fail: the fallback must not start a LocalOnlyHotspot
                    // while this group still holds the Wi-Fi AP mode (ERROR_INCOMPATIBLE_MODE).
                    safeRemoveGroup(() -> fail("the group started but never got an address"));
                }
            });
        } catch (SecurityException e) {
            fail("permission denied: " + e.getMessage());
        }
    }

    /** Central failure: tear down (close the channel) and report exactly once. */
    private void fail(String reason) {
        if (runState == RunState.STOPPED) return;   // already terminal
        runState = RunState.STOPPED;
        cancelWatchdog();
        unwatch();
        closeChannel();
        Log.w(TAG, "Wi-Fi Direct start failed: " + reason);
        if (cb != null) cb.onFailed(reason);
    }

    /**
     * Notices the group disappearing while we use it. P2P turning off (as when Wi-Fi is switched
     * off) ends it at once; a connection change (also fired when clients join or leave) prompts a
     * recheck, and it only counts as lost if the group is still gone a second later, so a momentary
     * gap cannot stop the hotspot.
     */
    private void watchGroup() {
        unwatch();
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (runState != RunState.UP) return;
                boolean p2pOff = WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(i.getAction())
                        && i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                            == WifiP2pManager.WIFI_P2P_STATE_DISABLED;
                if (p2pOff) lost("Wi-Fi was switched off, which stopped the Wi-Fi Direct group");
                else recheck(false);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        // Only the system sends these, so the receiver may be exported (required on API 33+).
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else app.registerReceiver(r, f);
        watcher = r;
    }

    @SuppressLint("MissingPermission")
    private void recheck(final boolean confirming) {
        if (runState != RunState.UP || mgr == null || channel == null) return;
        try {
            mgr.requestGroupInfo(channel, group -> {
                if (group != null || runState != RunState.UP) return;
                if (confirming) lost("the Wi-Fi Direct group stopped");
                else main.postDelayed(() -> recheck(true), 1000);
            });
        } catch (SecurityException ignored) { }
    }

    private void lost(String why) {
        if (runState != RunState.UP) return;
        runState = RunState.STOPPED;
        unwatch();
        closeChannel();
        if (cb != null) cb.onLost(why);
    }

    private void cancelWatchdog() {
        if (startWatchdog != null) { main.removeCallbacks(startWatchdog); startWatchdog = null; }
    }

    private void unwatch() {
        BroadcastReceiver r = watcher;
        if (r == null) return;
        watcher = null;
        try { app.unregisterReceiver(r); } catch (IllegalArgumentException ignored) { }
    }

    @SuppressLint("MissingPermission")
    private void safeRemoveGroup(Runnable then) {
        if (mgr == null || channel == null) { if (then != null) then.run(); return; }
        try {
            mgr.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { if (then != null) then.run(); }
                @Override public void onFailure(int reason) { if (then != null) then.run(); }
            });
        } catch (SecurityException e) {
            Log.w(TAG, "removeGroup denied: " + e.getMessage());
            if (then != null) then.run();
        }
    }

    private void closeChannel() {
        if (channel != null) {
            try { channel.close(); } catch (Exception ignored) { }   // Channel.close is API 27; class is 29+
        }
        channel = null;
        mgr = null;
    }

    private static String ipv4Of(String ifaceName) {
        if (ifaceName == null) return null;
        try {
            NetworkInterface ni = NetworkInterface.getByName(ifaceName);
            if (ni == null) return null;
            for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "this phone does not support Wi-Fi Direct";
            case WifiP2pManager.BUSY: return "Wi-Fi is busy or switched off";
            case WifiP2pManager.ERROR: return "Android reported an error (Wi-Fi may be off)";
            default: return "reason " + reason;
        }
    }
}
