/*
 * ============================================================================
 * Name        : CableMux.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Carries local TCP connections over the Clone cable (K2GO-576).
 *               The receiver gives it each rsync connection it accepts on
 *               127.0.0.1; the sender opens a matching connection to its own
 *               rsync daemon. rsync and its arguments do not change.
 *               Lifecycle: the Clone session creates it when the cable links
 *               and closes it on detach, stop or end of transfer. A RESET at
 *               start makes the peer drop channels of an older session.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.util.Log;

import org.appdevforall.k2go.sync.cable.domain.CableFrame;
import org.appdevforall.k2go.sync.cable.domain.FrameDecoder;
import org.appdevforall.k2go.sync.cable.domain.PeerAlive;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CableMux implements Closeable {

    private static final String TAG = "CableMux";
    private static final int PAYLOAD = 32 * 1024;

    public interface Listener {
        /** The first frame from the peer arrived: a K2Go mux runs on the other side. Called once. */
        void onPeer();

        /** The peer sent its pairing data (the QR payload). */
        void onHello(String payload);

        /** The cable or the peer is gone. Called once, with this mux. */
        void onLinkClosed(CableMux mux);

        /** The sender stopped sharing (BYE). */
        void onBye();

        /** The receiver's Wi-Fi verdict: does it reach the sender on Wi-Fi. */
        void onPeerWifi(boolean reaches);

        /** The receiver finished the copy (DONE). */
        void onDone();
    }

    /** Sender side: opens the connection that a new channel goes to. Null on the receiver. */
    public interface Opener {
        Socket open() throws IOException;
    }

    private final UsbLink link;
    private final Opener opener;
    private final Listener listener;
    private final Map<Integer, Socket> channels = new ConcurrentHashMap<>();
    private final AtomicInteger nextChannel = new AtomicInteger(1);
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private volatile boolean closed;
    private volatile long lastRx;         // last bytes from the cable, or the end of local work
    private volatile boolean peerPings;   // the peer runs the keepalive (an older K2Go does not)
    private volatile boolean busy;        // the read thread writes to a local socket
    private static final long PING_MS = 2_000;

    public CableMux(UsbLink link, Opener opener, Listener listener) {
        this.link = link;
        this.opener = opener;
        this.listener = listener;
    }

    public void start() throws IOException {
        send(CableFrame.control(CableFrame.Type.RESET, 0));
        Log.i(TAG, "mux started");
        Thread t = new Thread(this::readLoop, "cable-read");
        t.setDaemon(true);
        t.start();
        Thread k = new Thread(this::keepAlive, "cable-ping");
        k.setDaemon(true);
        k.start();
    }

    public void sendHello(String payload) throws IOException {
        send(new CableFrame(CableFrame.Type.HELLO, 0, payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** Receiver side: carries one accepted local connection over the cable. */
    public void carry(Socket s) throws IOException {
        int ch = nextChannel.getAndIncrement() & 0xFFFF;
        channels.put(ch, s);
        try {
            send(CableFrame.control(CableFrame.Type.OPEN, ch));
        } catch (IOException e) {
            closeChannel(ch, false);
            throw e;
        }
        pump(ch, s);
    }

    /** Receiver: tells the sender whether it reaches the sender on Wi-Fi. Best effort. */
    public void sendWifi(boolean reaches) {
        try {
            send(new CableFrame(CableFrame.Type.WIFI, 0, new byte[]{(byte) (reaches ? 1 : 0)}));
        } catch (IOException ignored) {
            // the next check sends it again
        }
    }

    /** Sender: tells the receiver that sharing stopped. Best effort: the link may be gone. */
    public void sendBye() {
        try {
            send(CableFrame.control(CableFrame.Type.BYE, 0));
        } catch (IOException ignored) {
            // the receiver also sees the link close
        }
    }

    /** Receiver: tells the sender that the copy is complete. Best effort: the link may be gone. */
    public void sendDone() {
        try {
            send(CableFrame.control(CableFrame.Type.DONE, 0));
        } catch (IOException ignored) {
            // the sender then only sees the link close
        }
    }

    public boolean isClosed() {
        return closed;
    }

    /** Payload bytes moved in both directions, for the speed on the cable row. */
    public long bytesMoved() {
        return bytesIn.get() + bytesOut.get();
    }

    private void readLoop() {
        FrameDecoder decoder = new FrameDecoder();
        byte[] b = new byte[UsbLink.MAX_READ];
        boolean peerSeen = false;
        try {
            for (int r; !closed && (r = link.read(b)) > 0; ) {
                lastRx = System.currentTimeMillis();
                for (CableFrame f : decoder.feed(b, 0, r)) {
                    if (!peerSeen) {
                        peerSeen = true;
                        listener.onPeer();
                        // Our start RESET may have hit a peer that was not reading yet: answer once,
                        // so the peer also sees a frame from us. PING: a late RESET would close
                        // channels that are open by then.
                        new Thread(() -> {
                            try {
                                send(CableFrame.control(CableFrame.Type.PING, 0));
                            } catch (IOException ignored) {
                                // the link is closing
                            }
                        }, "cable-ack").start();
                    }
                    // A slow local socket (slow storage) stops our reads: that is not a dead peer.
                    busy = true;
                    try {
                        handle(f);
                    } finally {
                        busy = false;
                        lastRx = System.currentTimeMillis();
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "cable read ended: " + e.getMessage());
        } finally {
            if (decoder.dropped() > 0) Log.i(TAG, "dropped " + decoder.dropped() + " stale bytes");
            close();
        }
    }

    private void handle(CableFrame f) throws IOException {
        switch (f.type) {
            case RESET:
                closeChannels();
                break;
            case HELLO:
                listener.onHello(new String(f.payload, StandardCharsets.UTF_8));
                break;
            case OPEN:
                Log.i(TAG, "OPEN channel " + f.channel);
                if (opener == null) break;
                try {
                    Socket s = opener.open();
                    channels.put(f.channel, s);
                    pump(f.channel, s);
                } catch (IOException e) {
                    Log.w(TAG, "open " + f.channel + " failed: " + e.getMessage());
                    send(CableFrame.control(CableFrame.Type.CLOSE, f.channel));
                }
                break;
            case DATA:
                Socket s = channels.get(f.channel);
                if (s == null) break;
                try {
                    s.getOutputStream().write(f.payload);
                    bytesIn.addAndGet(f.payload.length);
                } catch (IOException e) {
                    closeChannel(f.channel, true);
                }
                break;
            case CLOSE:
                closeChannel(f.channel, false);
                break;
            case BYE:
                Log.i(TAG, "the sender stopped sharing");
                listener.onBye();
                break;
            case WIFI:
                listener.onPeerWifi(f.payload.length > 0 && f.payload[0] == 1);
                break;
            case DONE:
                listener.onDone();
                break;
            case PING:
                peerPings = true;   // the read itself proves the peer is alive
                break;
        }
    }

    /**
     * A peer that dies (app killed, phone off) does not always end our blocked USB read, so the
     * link would look alive forever. Each side sends PING every PING_MS; PeerAlive decides when
     * silence closes the link.
     */
    private void keepAlive() {
        try {
            while (!closed) {
                Thread.sleep(PING_MS);
                if (closed) return;
                if (PeerAlive.isDead(peerPings, busy, System.currentTimeMillis() - lastRx)) {
                    Log.w(TAG, "nothing from the peer for " + PeerAlive.DEAD_MS + " ms: link closed");
                    close();
                    return;
                }
                try {
                    send(CableFrame.control(CableFrame.Type.PING, 0));
                } catch (IOException e) {
                    close();
                    return;
                }
            }
        } catch (InterruptedException ignored) {
            // closing
        }
    }

    /** Copies one local connection into DATA frames until it ends. */
    private void pump(int ch, Socket s) {
        Thread t = new Thread(() -> {
            // Read straight after the header slot: one buffer, no copy per frame.
            byte[] b = new byte[CableFrame.HEADER + PAYLOAD];
            try {
                InputStream in = s.getInputStream();
                for (int r; (r = in.read(b, CableFrame.HEADER, PAYLOAD)) > 0; ) {
                    CableFrame.writeHeader(b, CableFrame.Type.DATA, ch, r);
                    link.write(b, 0, CableFrame.HEADER + r);
                    bytesOut.addAndGet(r);
                }
            } catch (IOException ignored) {
                // the socket or the cable closed; CLOSE below
            }
            closeChannel(ch, true);
        }, "cable-pump-" + ch);
        t.setDaemon(true);
        t.start();
    }

    private void closeChannel(int ch, boolean tellPeer) {
        Socket s = channels.remove(ch);
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
        if (tellPeer && !closed) {
            try {
                send(CableFrame.control(CableFrame.Type.CLOSE, ch));
            } catch (IOException ignored) {
                // the read loop sees the dead link
            }
        }
    }

    private void closeChannels() {
        for (Integer ch : channels.keySet()) closeChannel(ch, false);
    }

    private void send(CableFrame f) throws IOException {
        byte[] b = f.encode();
        link.write(b, 0, b.length);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        closeChannels();
        try {
            link.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
        listener.onLinkClosed(this);
    }
}
