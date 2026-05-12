/*
 * ============================================================================
 * Name        : ServiceReceiver.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Broadcast receiver for system events
 * ============================================================================
 */

package org.iiab.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

public class ServiceReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            Preferences prefs = new Preferences(context);

            /* Auto-start */
            if (prefs.getEnable()) {
                // Initialize the service automatically on boot
                /*
                Intent i = VpnService.prepare(context);
                if (i != null) {
                    i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                }
                i = new Intent(context, TProxyService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i.setAction(TProxyService.ACTION_CONNECT));
                } else {
                    context.startService(i.setAction(TProxyService.ACTION_CONNECT));
                }
                */
            }
        }
    }
}
