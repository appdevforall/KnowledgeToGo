/*
 * ============================================================================
 * Name        : ShareRelay.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-577. A loopback TCP relay from the box to the sender's rsync daemon. The box's
 *               rsync is a Linux process that Android routes by the default network, which on a
 *               hotspot without internet can be cellular, so it may never reach the sender. The relay
 *               accepts on 127.0.0.1 and opens each outbound socket on the Network whose subnet holds
 *               the sender (WifiNetworkBinder), the same pinning NetworkImportCarrier uses for HTTP.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import org.appdevforall.k2go.sync.transport.WifiNetworkBinder;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forwards each loopback connection to {@code host:port}. Lifecycle: the import service opens one per
 * network run and closes it when the run ends; close() also ends every live connection, so a stopped
 * run never leaves a transfer flowing.
 */
public final class ShareRelay implements Closeable {

    private static final String TAG = "IIAB-ShareRelay";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int BUFFER = 64 * 1024;

    private final Context appContext;
    private final String host;
    private final int port;
    private final ServerSocket server;
    private final Set<Socket> live = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public ShareRelay(Context context, String host, int port) throws IOException {
        this.appContext = context.getApplicationContext();
        this.host = host;
        this.port = port;
        this.server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(this::acceptLoop, "share-relay-accept");
        accept.setDaemon(true);
        accept.start();
    }

    /** The loopback port the box connects to. */
    public int localPort() {
        return server.getLocalPort();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(server);
        for (Socket s : live) {
            closeQuietly(s);
        }
        live.clear();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket inbound;
            try {
                inbound = server.accept();
            } catch (IOException e) {
                return;   // closed
            }
            live.add(inbound);
            Thread t = new Thread(() -> bridge(inbound), "share-relay-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void bridge(Socket inbound) {
        Socket outbound = null;
        try {
            // Resolve the Network per connection, so a Wi-Fi reconnect is picked up on the next pull.
            Network net = WifiNetworkBinder.findHostNetwork(appContext, host);
            outbound = net != null ? net.getSocketFactory().createSocket() : new Socket();
            live.add(outbound);
            outbound.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            Socket out = outbound;
            Thread up = new Thread(() -> pump(inbound, out), "share-relay-up");
            up.setDaemon(true);
            up.start();
            pump(outbound, inbound);
        } catch (IOException e) {
            Log.i(TAG, "relay connection ended: " + e.getMessage());
        } finally {
            closeQuietly(inbound);
            closeQuietly(outbound);
            live.remove(inbound);
            if (outbound != null) {
                live.remove(outbound);
            }
        }
    }

    /** Copy one direction until EOF or an error, then close both ends so the peer pump ends too. */
    private static void pump(Socket from, Socket to) {
        byte[] buf = new byte[BUFFER];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // a closed socket on either side ends the pump
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to recover
        }
    }
}
