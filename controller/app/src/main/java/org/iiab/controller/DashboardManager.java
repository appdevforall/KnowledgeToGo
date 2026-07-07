/*
 * ============================================================================
 * Name        : DashboardManager.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Home dashboard status helper: binds the Wi-Fi and Hotspot tiles
 *               and reflects their OS connectivity state on the LEDs. The legacy
 *               "tunnel"/ESPW toggle was removed with the dead SOCKS-proxy
 *               mechanism (ADFA-4553); content is served from the native local
 *               server, so there is no tunnel state to display.
 * ============================================================================
 */
package org.iiab.controller;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;

import org.iiab.controller.hotspot.LocalHotspotManager;

public class DashboardManager {

    private final Activity activity;

    private final View dashWifi, dashHotspot;
    private final View ledWifi, ledHotspot;

    public DashboardManager(Activity activity, View rootView) {
        this.activity = activity;

        dashWifi = rootView.findViewById(R.id.dash_wifi);
        dashHotspot = rootView.findViewById(R.id.dash_hotspot);
        ledWifi = rootView.findViewById(R.id.led_wifi);
        ledHotspot = rootView.findViewById(R.id.led_hotspot);

        setupListeners();
    }

    private void setupListeners() {
        // Single tap opens Settings directly
        dashWifi.setOnClickListener(v -> activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));

        dashHotspot.setOnClickListener(v -> {
            // ADFA-4520: record that the operator tried the native hotspot, so the
            // Usage tab can later recommend the LocalOnlyHotspot fallback ONLY when
            // this AND "no SIM" AND "hotspot still not up" all hold.
            LocalHotspotManager.get().markNativeHotspotAttempted();
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
                activity.startActivity(intent);
            } catch (Exception e) {
                activity.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            }
        });
    }

    // Updates the LED graphics based on actual OS connectivity states
    public void updateConnectivityLeds(boolean isWifiOn, boolean isHotspotOn) {
        ledWifi.setBackgroundResource(isWifiOn ? R.drawable.led_on_green : R.drawable.led_off);
        ledHotspot.setBackgroundResource(isHotspotOn ? R.drawable.led_on_green : R.drawable.led_off);
    }
}
