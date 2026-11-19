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
 *               "Copy done over the cable" (K2GO-584): set by finishCopy() on the
 *               receiver and by the DONE frame on the sender; cleared by stop(),
 *               a new pairing (paired) or a new share (new hello).
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

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.appdevforall.k2go.sync.cable.domain.LinkRule.Cable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

public final class CableSession {

    private static final String TAG = "CableSession";
    private static final String ACTION_PERMISSION = "org.appdevforall.k2go.CABLE_USB_PERMISSION";
    private static final long POLL_MS = 1000;

    public enum Role { SEND, RECEIVE }

    /**
     * What the cable row shows. {@code bytesPerSec} > 0 means the cable carries data. {@code done}:
     * a copy finished while the cable was linked, so the cable can be removed.
     */
    public static final class State {
        public final Cable link;
        public final long bytesPerSec;
        public final boolean done;

        State(Cable link, long bytesPerSec, boolean done) {
            this.link = link;
            this.bytesPerSec = bytesPerSec;
            this.done = done;
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

    private final MutableLiveData<State> state = new MutableLiveData<>(new State(Cable.NONE, 0, false));
    private Context app;
    private Role role;
    private int rsyncPort;
    private volatile String hello;
    private volatile HelloListener helloListener;
    private Thread watcher;
    private volatile CableMux mux;
    private volatile boolean senderStopped;
    private volatile boolean copyDone;   // a copy finished while the cable was linked
    /** Sender: the receiver's own Wi-Fi verdict, from the cable; null when unknown. */
    private final MutableLiveData<Boolean> peerWifi = new MutableLiveData<>(null);
    private volatile String lastHello;        // the pairing data of the copy in progress (QR or cable)
    private volatile Boolean lastWifiReport;  // the verdict last sent over this link
    private volatile String linkedDevice;     // host side: the device name of the linked phone
    private volatile String completedHello;   // the pairing data of the last finished copy
    private volatile CableRelay relay;
    /** The target of the open permission dialog; one dialog per target, no repeat. */
    private volatile String pendingKey;
    /** A target the user refused; asked again only after a replug. */
    private volatile String deniedKey;
    /** The system "open with" flow (manifest filter) may grant first; wait this long for it. */
    private static final long SYSTEM_GRANT_GRACE_MS = 3_000;
    /** A K2Go screen is in front. False while a system USB dialog covers the app: then we do not stack ours. */
    private volatile boolean inFront = true;
    private volatile long frontSince;      // when K2Go came to the front (0: before tracking)
    private volatile long pendingSince;    // when the open dialog was asked for
    private static final long FRONT_SETTLE_MS = 1_500;
    private static final long PENDING_RETRY_MS = 10_000;
    private boolean resumeTracked;
    private volatile String graceKey;
    private volatile long graceSince;
    /** The peer mux answered: only then is the cable "linked". */
    private volatile boolean peerSeen;
    /** The link open() is starting; set and cleared by open(), closed by closeLink(). */
    private UsbLink opening;
    private BroadcastReceiver permissionReceiver;
    private BroadcastReceiver detachReceiver;

    private CableSession() {
    }

    public LiveData<State> state() {
        return state;
    }

    /** Sender: whether the receiver reaches this phone on Wi-Fi (null: no cable or no answer yet). */
    public LiveData<Boolean> peerWifi() {
        return peerWifi;
    }

    /**
     * Receiver: shares its Wi-Fi verdict over the cable, so both phones show the same Wi-Fi state.
     * Only the receiver can tell: the sender reaching the router says nothing about the path.
     */
    public void reportWifi(boolean reaches) {
        CableMux m = mux;
        if (m == null || role != Role.RECEIVE || Boolean.valueOf(reaches).equals(lastWifiReport)) return;
        lastWifiReport = reaches;
        new Thread(() -> m.sendWifi(reaches), "cable-wifi").start();   // USB write: off the UI thread
    }

    /** Receiver: the pairing data that started the current copy, from a QR scan or the cable. */
    public void paired(String payload) {
        lastHello = payload;
        senderStopped = false;
        copyDone = false;
    }

    /**
     * Receiver: the copy from the last pairing data finished. It is not offered again, the sender
     * hears DONE over the cable, and the session stops like stop().
     */
    public synchronized void finishCopy() {
        completedHello = lastHello;
        CableMux m = mux;
        if (m != null) {
            mux = null;
            new Thread(() -> { m.sendDone(); m.close(); }, "cable-done").start();   // USB write: off the UI thread
        }
        stop();
        copyDone = m != null;
        post(Cable.NONE, 0);
    }

    /** A copy finished while the cable was linked, so the cable can be removed. */
    public boolean copyDone() {
        return copyDone;
    }

    /** Receiver: the sender said over the cable that it stopped sharing (cleared by a new link). */
    public boolean senderStopped() {
        return senderStopped;
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
        if (newHello) copyDone = false;   // a new share
        if (roleChanged) closeLink();
        CableMux m = mux;
        if (newHello && m != null && role == Role.SEND) {
            new Thread(() -> sendHello(m), "cable-hello").start();   // a USB write can block: not on the UI thread
        }
        registerDetach();
        trackResumed(ctx);
        if (watcher == null) {
            watcher = new Thread(this::watchLoop, "cable-watch");
            watcher.setDaemon(true);
            watcher.start();
        }
    }

    /** Process-wide and registered once, like this session. */
    private void trackResumed(Context ctx) {
        if (resumeTracked) return;
        resumeTracked = true;
        ((android.app.Application) app).registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(android.app.Activity a) { inFront = true; frontSince = System.currentTimeMillis(); }
                    @Override public void onActivityPaused(android.app.Activity a) { inFront = false; }
                    @Override public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { }
                    @Override public void onActivityStarted(android.app.Activity a) { }
                    @Override public void onActivityStopped(android.app.Activity a) { }
                    @Override public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }
                    @Override public void onActivityDestroyed(android.app.Activity a) { }
                });
    }

    public synchronized void stop() {
        Thread w = watcher;
        watcher = null;
        if (w != null) w.interrupt();
        CableMux m = mux;
        if (m != null && role == Role.SEND) {
            mux = null;
            new Thread(() -> { m.sendBye(); m.close(); }, "cable-bye").start();   // USB write: off the UI thread
        }
        closeLink();
        helloListener = null;
        hello = null;
        senderStopped = false;
        role = null;
        pendingKey = null;
        deniedKey = null;
        graceKey = null;
        unregisterPermission();   // a dialog nobody answered must not leave a receiver behind
        unregisterDetach();
        copyDone = false;
        post(Cable.NONE, 0);
    }

    private void post(Cable link, long bytesPerSec) {
        state.postValue(new State(link, bytesPerSec, copyDone));
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
                deniedKey = null;   // a replug is a new try
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                    UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    String linked = linkedDevice;
                    // Another USB device (hub, drive) going away must not drop the cable.
                    if (d != null && linked != null && !linked.equals(d.getDeviceName())) return;
                }
                closeLink();
                post(Cable.NONE, 0);
            }
        };
        IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(app, detachReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
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
                    post(peerSeen ? Cable.LINKED : Cable.PEER, (b - lastBytes) * 1000 / POLL_MS);
                    lastBytes = b;
                }
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.w(TAG, "cable link attempt failed: " + e.getMessage());
                try {
                    Thread.sleep(POLL_MS);   // a failed attempt waits too: no busy loop on a dying link
                } catch (InterruptedException ie) {
                    return;
                }
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
                if (!um.hasPermission(a)) { askPermission(um, a, "acc", true); return; }
                Log.i(TAG, "opening the accessory side");
                open(UsbLink.accessory(um, a));
                return;
            }
        }
        for (UsbDevice d : um.getDeviceList().values()) {
            if (!UsbLink.mayBePhone(d)) continue;   // hub, drive, keyboard
            boolean aoa = UsbLink.isAccessoryMode(d);
            String key = "dev:" + d.getDeviceName();
            if (!um.hasPermission(d)) { askPermission(um, d, key, aoa); return; }
            if (aoa) {
                Log.i(TAG, "opening the host side");
                open(UsbLink.host(um, d));
                linkedDevice = d.getDeviceName();
            } else {
                Log.i(TAG, "switching " + d.getDeviceName() + " to accessory mode");
                // The other phone re-enumerates in accessory mode; the next poll opens it.
                UsbLink.switchToAccessory(um, d);
                post(Cable.SWITCHING, 0);
            }
            return;
        }
        if (state.getValue() != null && state.getValue().link != Cable.NONE) {
            post(Cable.NONE, 0);
        }
    }

    /**
     * One dialog per target: no repeat while it is open, none after a "no" until a replug.
     * {@code systemMayGrant}: our accessory and the AOA device match the manifest filter, so the
     * system "open with" dialog may grant first; wait for it before showing ours.
     */
    private void askPermission(UsbManager um, Object target, String key, boolean systemMayGrant) {
        if (key.equals(deniedKey)) { post(Cable.DENIED, 0); return; }
        post(Cable.ASK_HERE, 0);
        long now = System.currentTimeMillis();
        // Let the system act on a new device first: it may open its own window (an "open with"
        // dialog for our filters, or Files for an MTP phone) that would hide our dialog.
        if (!key.equals(graceKey)) { graceKey = key; graceSince = now; return; }
        if (now - graceSince < (systemMayGrant ? SYSTEM_GRANT_GRACE_MS : FRONT_SETTLE_MS)) return;
        // Another window is on top: ask once K2Go is in front again, so our dialog is seen.
        if (!inFront || now - frontSince < FRONT_SETTLE_MS) return;
        // Our dialog is open or still coming up (slow phones): K2Go has not left the front since we
        // asked. Ask again only when K2Go came back with no answer (another window buried the
        // dialog), or when no dialog showed at all for PENDING_RETRY_MS.
        if (key.equals(pendingKey) && frontSince <= pendingSince && now - pendingSince < PENDING_RETRY_MS) return;
        Log.i(TAG, "asking USB permission for " + key);
        pendingKey = key;
        pendingSince = now;
        unregisterPermission();   // one receiver at a time
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(app, 0,
                new Intent(ACTION_PERMISSION).setPackage(app.getPackageName()), flags);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                boolean granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                String answered = d != null ? "dev:" + d.getDeviceName() : "acc";
                Log.i(TAG, "USB permission " + (granted ? "granted" : "denied") + " for " + answered);
                if (!answered.equals(key)) return;   // an older dialog for a device that is gone
                pendingKey = null;
                if (!granted) deniedKey = key;
                unregisterPermission();
            }
        };
        permissionReceiver = r;
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        ContextCompat.registerReceiver(app, r, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        if (target instanceof UsbDevice) um.requestPermission((UsbDevice) target, pi);
        else um.requestPermission((UsbAccessory) target, pi);
    }

    /**
     * Runs on the watcher thread. The first USB write blocks while the peer is not reading, so it
     * runs outside the session lock: stop() on the UI thread must never wait for it (ANR).
     */
    private void open(UsbLink link) throws IOException {
        CableMux m;
        Role r;
        synchronized (this) {
            r = role;
            if (Thread.currentThread() != watcher || r == null) {   // stopped meanwhile
                try { link.close(); } catch (IOException ignored) { /* already gone */ }
                return;
            }
            peerSeen = false;
            opening = link;
            post(Cable.PEER, 0);   // the first write can block while the peer is not reading
            m = newMux(link, r);
        }
        // Start first: a link that dies at once (unplugged a moment ago, ENODEV) must not stay
        // as "linked", or the session never tries again.
        try {
            m.start();
        } catch (IOException e) {
            synchronized (this) {
                if (opening == link) opening = null;
                // A stale accessory after a replug: nothing answers on it.
                if (Thread.currentThread() == watcher) post(Cable.NONE, 0);
            }
            try { link.close(); } catch (IOException ignored) { /* already gone */ }
            throw e;
        }
        synchronized (this) {
            boolean ours = opening == link;   // closeLink() takes it while the write blocks
            if (ours) opening = null;
            // Stopped while the write blocked, or the link died already: the next poll tries again.
            if (!ours || Thread.currentThread() != watcher || role != r || m.isClosed()) {
                m.close();
                return;
            }
            if (r == Role.RECEIVE) {
                try {
                    relay = new CableRelay(m);
                } catch (IOException e) {
                    m.close();
                    throw e;
                }
            }
            mux = m;
            senderStopped = false;
            lastWifiReport = null;   // a new link: send the next verdict
        }
        if (r == Role.SEND) sendHello(m);   // a USB write: outside the lock too
        post(peerSeen ? Cable.LINKED : Cable.PEER, 0);
        Log.i(TAG, "cable open as " + r + ", waiting for the other phone");
    }

    private CableMux newMux(UsbLink link, Role r) {
        CableMux.Opener opener = r == Role.SEND
                ? () -> new Socket("127.0.0.1", rsyncPort)
                : null;
        return new CableMux(link, opener, new CableMux.Listener() {
            @Override
            public void onPeer() {
                peerSeen = true;
                post(Cable.LINKED, 0);
                Log.i(TAG, "the other phone answered over the cable");
            }

            @Override
            public void onHello(String payload) {
                HelloListener l = helloListener;
                // A cable plugged again after a finished copy brings the same share: do not offer
                // the same copy again. A new share on the sender has a new password, so it differs.
                if (payload.equals(completedHello)) return;
                senderStopped = false;   // a new pairing is a new share
                if (role == Role.RECEIVE && l != null) l.onHello(payload);
            }

            @Override
            public void onBye() {
                senderStopped = true;
            }

            @Override
            public void onPeerWifi(boolean reaches) {
                peerWifi.postValue(reaches);
            }

            @Override
            public void onDone() {
                copyDone = true;
                post(Cable.LINKED, 0);
            }

            @Override
            public void onLinkClosed(CableMux closed) {
                linkClosed(closed);
            }
        });
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

    private synchronized void linkClosed(CableMux closed) {
        if (mux != null && mux != closed) return;   // an older link: the current one stays
        peerWifi.postValue(null);
        closeRelay();
        mux = null;
        post(Cable.NONE, 0);
        Log.i(TAG, "cable link closed");
    }

    private synchronized void closeLink() {
        linkedDevice = null;
        UsbLink o = opening;   // open() is blocked in its first write on it
        opening = null;
        if (o != null) {
            try { o.close(); } catch (IOException ignored) { /* already gone */ }
        }
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
