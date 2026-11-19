/*
 * ============================================================================
 * Name        : UsbPresence.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Is a USB data cable plugged in (K2GO-584)? Reads the system's
 *               sticky USB_STATE broadcast: no permission and no dialog. A
 *               charger without data does not count.
 *               Lifecycle: start() registers the receiver, stop() removes it;
 *               the Clone screen owns both.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

public final class UsbPresence {

    // Hidden UsbManager constants; stable since Android 4 (USB_HOST_CONNECTED since 11).
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String EXTRA_CONNECTED = "connected";
    private static final String EXTRA_HOST_CONNECTED = "host_connected";

    private final Context app;
    private final MutableLiveData<Boolean> present = new MutableLiveData<>(false);
    private BroadcastReceiver receiver;

    public UsbPresence(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public LiveData<Boolean> present() {
        return present;
    }

    public void start() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                update(i);
            }
        };
        // Sticky: the call returns the last state at once.
        update(ContextCompat.registerReceiver(app, receiver, new IntentFilter(ACTION_USB_STATE),
                ContextCompat.RECEIVER_EXPORTED));
    }

    public void stop() {
        if (receiver == null) return;
        try {
            app.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
        receiver = null;
    }

    private void update(Intent i) {
        if (i == null) return;
        boolean p = i.getBooleanExtra(EXTRA_CONNECTED, false) || i.getBooleanExtra(EXTRA_HOST_CONNECTED, false);
        present.postValue(p);
    }
}
