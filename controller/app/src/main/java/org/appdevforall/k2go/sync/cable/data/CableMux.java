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
        /** The peer sent its pairing data (the QR payload). */
        void onHello(String payload);

        /** The cable or the peer is gone. Called once. */
        void onLinkClosed();
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

    public CableMux(UsbLink link, Opener opener, Listener listener) {
        this.link = link;
        this.opener = opener;
        this.listener = listener;
    }

    public void start() throws IOException {
        send(CableFrame.control(CableFrame.Type.RESET, 0));
        Thread t = new Thread(this::readLoop, "cable-read");
        t.setDaemon(true);
        t.start();
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

    /** Payload bytes moved in both directions, for the speed on the cable row. */
    public long bytesMoved() {
        return bytesIn.get() + bytesOut.get();
    }

    private void readLoop() {
        FrameDecoder decoder = new FrameDecoder();
        byte[] b = new byte[UsbLink.READ_BUFFER];
        try {
            for (int r; !closed && (r = link.read(b)) > 0; ) {
                for (CableFrame f : decoder.feed(b, 0, r)) handle(f);
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
        }
    }

    /** Copies one local connection into DATA frames until it ends. */
    private void pump(int ch, Socket s) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[PAYLOAD];
            try {
                InputStream in = s.getInputStream();
                for (int r; (r = in.read(b)) > 0; ) {
                    byte[] p = new byte[r];
                    System.arraycopy(b, 0, p, 0, r);
                    send(new CableFrame(CableFrame.Type.DATA, ch, p));
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
        listener.onLinkClosed();
    }
}
