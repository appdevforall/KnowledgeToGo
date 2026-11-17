/*
 * ============================================================================
 * Name        : CableRelay.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Receiver side of the Clone cable (K2GO-576): listens on
 *               127.0.0.1 and gives each accepted rsync connection to the
 *               CableMux. The rsync client uses rsync://user@127.0.0.1:port/.
 *               Lifecycle: opened with the cable session, closed with it.
 * ============================================================================
 */
package org.appdevforall.k2go.sync.cable.data;

import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public final class CableRelay implements Closeable {

    private static final String TAG = "CableRelay";
    private final ServerSocket server;

    public CableRelay(CableMux mux) throws IOException {
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));   // rsync dials 127.0.0.1; Android loopback is ::1
        Log.i(TAG, "relay listening on 127.0.0.1:" + server.getLocalPort());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    Log.i(TAG, "rsync connection on the cable relay");
                    mux.carry(s);
                } catch (IOException e) {
                    if (!server.isClosed()) Log.w(TAG, "accept failed: " + e.getMessage());
                }
            }
        }, "cable-relay");
        t.setDaemon(true);
        t.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
