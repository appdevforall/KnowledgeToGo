/*
 * ============================================================================
 * Name        : CableSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The Clone cable for one Clone screen (K2GO-576). It runs only
 *               while Clone watches it with a role (Send or Receive): it never
 *               starts anything by itself. It finds the other phone, asks for
 *               the USB permission, opens the AOA link and the mux, and on the
 *               receiver the 127.0.0.1 relay. The sender sends its pairing data
 *               (the QR payload) as soon as the link is up.
 *               Lifecycle: Clone calls watch() and stop(); a detach or a dead
 *               link closes the mux and the session watches again. Nothing is
 *               persisted. Process-wide, like CloneSendSession.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public final class CableSession {

    private static final String TAG = "CableSession";
    private static final String ACTION_PERMISSION = "org.appdevforall.k2go.CABLE_USB_PERMISSION";
    private static final long POLL_MS = 1000;

    public enum Role { SEND, RECEIVE }

    public enum Link { NONE, WAITING, LINKED }

    /** What the cable row shows. {@code bytesPerSec} > 0 means the cable carries data. */
    public static final class State {
        public final Link link;
        public final long bytesPerSec;

        State(Link link, long bytesPerSec) {
            this.link = link;
            this.bytesPerSec = bytesPerSec;
        }
    }

    public interface HelloListener {
        /** Receiver: the sender's pairing data arrived over the cable (same text as the QR). */
        void onHello(String payload);
    }

    private static final CableSession INSTANCE = new CableSession();

    public static CableSession get() {
        return INSTANCE;
    }

    private final MutableLiveData<State> state = new MutableLiveData<>(new State(Link.NONE, 0));
    private Context app;
    private Role role;
    private int rsyncPort;
    private volatile String hello;
    private volatile HelloListener helloListener;
    private Thread watcher;
    private volatile CableMux mux;
    private volatile CableRelay relay;
    private boolean permissionAsked;
    private BroadcastReceiver permissionReceiver;
    private BroadcastReceiver detachReceiver;

    private CableSession() {
    }

    public LiveData<State> state() {
        return state;
    }

    /** Receiver: the local port the rsync client uses for the cable path, or -1 with no link. */
    public int relayPort() {
        CableRelay r = relay;
        return r != null ? r.port() : -1;
    }

    /**
     * Starts or updates the watch. Sender: {@code helloPayload} is the QR payload, sent when the
     * link is up (null while the daemon is not ready). Receiver: {@code listener} gets it.
     */
    public synchronized void watch(Context ctx, Role role, int rsyncPort, String helloPayload,
                                   HelloListener listener) {
        this.app = ctx.getApplicationContext();
        this.rsyncPort = rsyncPort;
        this.helloListener = listener;
        boolean roleChanged = this.role != role;
        this.role = role;
        boolean newHello = helloPayload != null && !helloPayload.equals(hello);
        hello = helloPayload;
        if (roleChanged) closeLink();
        CableMux m = mux;
        if (newHello && m != null && role == Role.SEND) {
            new Thread(() -> sendHello(m), "cable-hello").start();   // a USB write can block: not on the UI thread
        }
        registerDetach();
        if (watcher == null) {
            watcher = new Thread(this::watchLoop, "cable-watch");
            watcher.setDaemon(true);
            watcher.start();
        }
    }

    public synchronized void stop() {
        Thread w = watcher;
        watcher = null;
        if (w != null) w.interrupt();
        closeLink();
        helloListener = null;
        hello = null;
        role = null;
        permissionAsked = false;
        unregisterPermission();   // a dialog nobody answered must not leave a receiver behind
        unregisterDetach();
        state.postValue(new State(Link.NONE, 0));
    }

    /**
     * A blocked accessory read does not always return when the cable comes out, so the link
     * would look alive forever. Android's detach broadcasts close it.
     */
    private void registerDetach() {
        if (detachReceiver != null) return;
        detachReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Log.i(TAG, "USB detached: " + i.getAction());
                closeLink();
                state.postValue(new State(Link.NONE, 0));
            }
        };
        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(detachReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(detachReceiver, f);
    }

    private synchronized void unregisterDetach() {
        BroadcastReceiver r = detachReceiver;
        detachReceiver = null;
        if (r == null || app == null) return;
        try {
            app.unregisterReceiver(r);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
    }

    private synchronized void unregisterPermission() {
        BroadcastReceiver r = permissionReceiver;
        permissionReceiver = null;
        if (r == null || app == null) return;
        try {
            app.unregisterReceiver(r);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
    }

    private void watchLoop() {
        long lastBytes = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                CableMux m = mux;
                if (m == null) {
                    tryLink();
                    lastBytes = 0;
                } else {
                    long b = m.bytesMoved();
                    state.postValue(new State(Link.LINKED, (b - lastBytes) * 1000 / POLL_MS));
                    lastBytes = b;
                }
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.w(TAG, "cable link attempt failed: " + e.getMessage());
            }
        }
    }

    /** One attempt: accessory side first, then host side. */
    private void tryLink() throws IOException, InterruptedException {
        UsbManager um = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (um == null) return;

        UsbAccessory[] accessories = um.getAccessoryList();
        if (accessories != null) {
            for (UsbAccessory a : accessories) {
                if (!UsbLink.isOurAccessory(a)) continue;
                if (!um.hasPermission(a)) { askPermission(um, a); return; }
                Log.i(TAG, "opening the accessory side");
                open(UsbLink.accessory(um, a));
                return;
            }
        }
        for (UsbDevice d : um.getDeviceList().values()) {
            if (!um.hasPermission(d)) { askPermission(um, d); return; }
            if (UsbLink.isAccessoryMode(d)) {
                Log.i(TAG, "opening the host side");
                open(UsbLink.host(um, d));
            } else {
                Log.i(TAG, "switching " + d.getDeviceName() + " to accessory mode");
                // The other phone re-enumerates in accessory mode; the next poll opens it.
                UsbLink.switchToAccessory(um, d);
                permissionAsked = false;
                state.postValue(new State(Link.WAITING, 0));
            }
            return;
        }
        if (state.getValue() != null && state.getValue().link != Link.NONE) {
            state.postValue(new State(Link.NONE, 0));
        }
    }

    private void askPermission(UsbManager um, Object target) {
        state.postValue(new State(Link.WAITING, 0));
        if (permissionAsked) return;
        Log.i(TAG, "asking USB permission for " + (target instanceof UsbDevice ? "device" : "accessory"));
        permissionAsked = true;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(app, 0,
                new Intent(ACTION_PERMISSION).setPackage(app.getPackageName()), flags);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                permissionAsked = false; // the next poll opens the link, or asks again after a "no"
                unregisterPermission();
            }
        };
        permissionReceiver = r;
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else app.registerReceiver(r, f);
        if (target instanceof UsbDevice) um.requestPermission((UsbDevice) target, pi);
        else um.requestPermission((UsbAccessory) target, pi);
    }

    private synchronized void open(UsbLink link) throws IOException {
        CableMux.Opener opener = role == Role.SEND
                ? () -> new Socket("127.0.0.1", rsyncPort)
                : null;
        CableMux m = new CableMux(link, opener, new CableMux.Listener() {
            @Override
            public void onHello(String payload) {
                HelloListener l = helloListener;
                if (role == Role.RECEIVE && l != null) l.onHello(payload);
            }

            @Override
            public void onLinkClosed() {
                linkClosed();
            }
        });
        // Start first: a link that dies at once (unplugged a moment ago, ENODEV) must not stay
        // as "linked", or the session never tries again.
        try {
            m.start();
            if (role == Role.RECEIVE) relay = new CableRelay(m);
        } catch (IOException e) {
            closeRelay();
            try { link.close(); } catch (IOException ignored) { /* already gone */ }
            throw e;
        }
        mux = m;
        if (role == Role.SEND) sendHello(m);
        state.postValue(new State(Link.LINKED, 0));
        Log.i(TAG, "cable linked as " + role);
    }

    private void sendHello(CableMux m) {
        String h = hello;
        if (h == null) return;
        try {
            m.sendHello(h);
        } catch (IOException e) {
            Log.w(TAG, "hello failed: " + e.getMessage());
        }
    }

    private synchronized void linkClosed() {
        closeRelay();
        mux = null;
        state.postValue(new State(Link.NONE, 0));
        Log.i(TAG, "cable link closed");
    }

    private synchronized void closeLink() {
        CableMux m = mux;
        mux = null;
        if (m != null) m.close();
        closeRelay();
    }

    private void closeRelay() {
        CableRelay r = relay;
        relay = null;
        if (r == null) return;
        try {
            r.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
