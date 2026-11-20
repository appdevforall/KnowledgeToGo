/*
 * ============================================================================
 * Name        : HotspotMode.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-577. The one rule for a screen's Hotspot / Wi-Fi choice, shared by Connect,
 *               Clone and Content Exchange Share. Hotspot takes this screen's hold on the shared
 *               hotspot (LocalHotspotManager); Wi-Fi gives it back, so a Wi-Fi Direct group left up
 *               cannot slow the radio (K2GO-561). The hotspot permission is asked at most once per
 *               choice. Each screen still decides when it lets go on exit (release()).
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot;

import android.content.Context;

import org.appdevforall.k2go.hotspot.domain.HotspotHolders;

/** A screen's Hotspot / Wi-Fi choice and its hold on the shared hotspot. */
public final class HotspotMode {

    public enum Mode { HOTSPOT, WIFI }

    /** What the rule needs from the hotspot; a seam so the rule runs on a plain JVM in tests. */
    public interface Port {
        boolean supported();
        boolean permitted();
        boolean isOn();
        void acquire();
        void release();
        boolean releaseDropsClients();
    }

    private final Port port;
    private Mode mode;
    /** The permission was asked for the current choice: a re-render must not ask again. Some OEMs
     *  deny synchronously, so a re-ask from the result's re-render recursed until overflow (ADFA-5146). */
    private boolean asked;

    public HotspotMode(Port port, Mode initial) {
        this.port = port;
        this.mode = initial;
    }

    /** The rule on the shared LocalHotspotManager for [holder]. */
    public static HotspotMode of(Context context, HotspotHolders.Holder holder, Mode initial) {
        final Context app = context.getApplicationContext();
        final LocalHotspotManager hs = LocalHotspotManager.get();
        return new HotspotMode(new Port() {
            @Override public boolean supported() { return LocalHotspotManager.isSupported(); }
            @Override public boolean permitted() { return HotspotPermissions.granted(app); }
            @Override public boolean isOn() { return hs.isOn(); }
            @Override public void acquire() { hs.acquire(app, holder); }
            @Override public void release() { hs.release(holder); }
            @Override public boolean releaseDropsClients() { return hs.releaseDropsClients(holder); }
        }, initial);
    }

    /**
     * The first choice of a sharing screen: the Wi-Fi network the phone is already on, else the
     * hotspot. A Wi-Fi Direct group started for nothing can break a direct Wi-Fi link (TDLS)
     * between the two phones on the same network (K2GO-587).
     */
    public static Mode initialFor(boolean onWifiNetwork) {
        return onWifiNetwork ? Mode.WIFI : Mode.HOTSPOT;
    }

    public Mode mode() {
        return mode;
    }

    public boolean isHotspot() {
        return mode == Mode.HOTSPOT;
    }

    /**
     * The user picked [m]: a fresh attempt, so the permission may be asked once more. Returns true
     * when the caller must launch the hotspot permission request.
     */
    public boolean choose(Mode m) {
        mode = m;
        asked = false;
        return ensure();
    }

    /**
     * Apply the current choice. Hotspot holds the hotspot (also when another holder already started
     * it, so their release cannot drop it), or returns true once when the permission is missing;
     * Wi-Fi releases this screen's hold.
     */
    public boolean ensure() {
        if (mode == Mode.WIFI) {
            port.release();
            return false;
        }
        if (!port.supported()) {
            return false;
        }
        if (port.permitted()) {
            port.acquire();
            return false;
        }
        if (!port.isOn() && !asked) {
            asked = true;
            return true;
        }
        return false;
    }

    /**
     * True when a switch to [target] turns off a hotspot that someone may be joined to: this screen
     * is its last holder. The screen asks the user first.
     */
    public boolean switchDropsClients(Mode target) {
        return mode == Mode.HOTSPOT && target == Mode.WIFI && port.releaseDropsClients();
    }

    /** Re-apply on a return to the screen or after the permission answer; never asks again. */
    public void resume() {
        if (mode == Mode.WIFI || port.permitted()) {
            ensure();
        }
    }

    /** This screen no longer needs the hotspot (it left, or its transfer ended). */
    public void release() {
        port.release();
    }
}
