/*
 * ============================================================================
 * Name        : ServiceReceiver.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Broadcast receiver for system events. Currently a placeholder
 *               for boot auto-start (ADFA-3340). The old VpnService/TProxyService
 *               auto-start was removed with the dead VPN tunnel (ADFA-4552); the
 *               receiver is kept as the seam where the future auto-start will hook.
 * ============================================================================
 */

package org.iiab.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ServiceReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null) {
            return;
        }
        if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            Preferences prefs = new Preferences(context);
            if (prefs.getEnable()) {
                // TODO(ADFA-3340): auto-start K2Go on boot. The previous
                // VpnService/TProxyService implementation was removed (ADFA-4552,
                // dead tunnel); the replacement start path will hook in here.
            }
        }
    }
}
