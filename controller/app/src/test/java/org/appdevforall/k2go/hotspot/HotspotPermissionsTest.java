package org.appdevforall.k2go.hotspot;

import static org.junit.Assert.assertArrayEquals;

import android.Manifest;
import android.os.Build;

import org.junit.Test;

/**
 * Unit tests for {@link HotspotPermissions} (K2GO-439): the rule that picks the
 * runtime permission startLocalOnlyHotspot needs per API level.
 */
public class HotspotPermissionsTest {

    @Test
    public void api33AndAboveRequiresNearbyWifiDevices() {
        assertArrayEquals(new String[]{ Manifest.permission.NEARBY_WIFI_DEVICES },
                HotspotPermissions.requiredFor(Build.VERSION_CODES.TIRAMISU));
        assertArrayEquals(new String[]{ Manifest.permission.NEARBY_WIFI_DEVICES },
                HotspotPermissions.requiredFor(35));
    }

    @Test
    public void api32AndBelowRequiresFineLocation() {
        assertArrayEquals(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION },
                HotspotPermissions.requiredFor(Build.VERSION_CODES.S_V2));
        assertArrayEquals(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION },
                HotspotPermissions.requiredFor(24));
    }
}
