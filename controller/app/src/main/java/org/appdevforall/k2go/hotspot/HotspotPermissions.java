/*
 * ============================================================================
 * Name        : HotspotPermissions.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Single source for the runtime permission a LocalOnlyHotspot needs
 *               on this OS (K2GO-439). From API 33 (TIRAMISU)
 *               WifiManager.startLocalOnlyHotspot requires NEARBY_WIFI_DEVICES;
 *               API 32 and below need ACCESS_FINE_LOCATION. The Connect and Clone
 *               screens read this instead of each hardcoding the location gate.
 *               See the startLocalOnlyHotspot javadoc.
 * ============================================================================
 */
package org.appdevforall.k2go.hotspot;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/** Stateless permission rule for the LocalOnlyHotspot, shared by the Connect and Clone screens. */
public final class HotspotPermissions {

    private HotspotPermissions() {}

    /** The permission(s) startLocalOnlyHotspot requires on this device's API level. */
    public static String[] required() {
        return requiredFor(Build.VERSION.SDK_INT);
    }

    /**
     * The rule as a pure function of the API level. Package-private for unit testing.
     * Keyed off the device API: startLocalOnlyHotspot bases its requirement on the app
     * targetSdk, and keying off the device API matches only because the app targets >= 33.
     */
    static String[] requiredFor(int sdkInt) {
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{ Manifest.permission.NEARBY_WIFI_DEVICES };
        }
        return new String[]{ Manifest.permission.ACCESS_FINE_LOCATION };
    }

    /** True when every required permission is granted. */
    public static boolean granted(Context ctx) {
        for (String p : required()) {
            if (ContextCompat.checkSelfPermission(ctx, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
