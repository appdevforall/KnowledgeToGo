/*
 * ============================================================================
 * Name        : UsbPresence.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Is a USB data cable plugged in that can go to the other phone
 *               (K2GO-584)? Reads the system's sticky USB_STATE broadcast and
 *               the attached devices: no permission and no dialog. A charger
 *               without data does not count. As the host, only a device that
 *               can be a phone counts (UsbLink.mayBePhone), not a hub, drive or
 *               keyboard. As the device, a computer and a phone look the same.
 *               Lifecycle: start() registers the receiver, stop() removes it;
 *               the Clone screen owns both.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

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
    private Intent lastState;   // the last USB_STATE: an attach or detach re-reads the devices with it

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
        IntentFilter f = new IntentFilter(ACTION_USB_STATE);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        // Sticky: the call returns the last state at once.
        update(ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_EXPORTED));
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
        if (i != null && ACTION_USB_STATE.equals(i.getAction())) lastState = i;
        Intent s = lastState;
        if (s == null) return;
        boolean p = s.getBooleanExtra(EXTRA_CONNECTED, false)
                || (s.getBooleanExtra(EXTRA_HOST_CONNECTED, false) && phoneAttached());
        present.postValue(p);
    }

    private boolean phoneAttached() {
        UsbManager um = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (um == null) return false;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (UsbLink.mayBePhone(d)) return true;
        }
        return false;
    }
}
