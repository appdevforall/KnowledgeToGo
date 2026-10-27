/*
 * ============================================================================
 * Name        : NetworkImportCarrier.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. The network ImportCarrier: the mirror of SafImportCarrier, but each item is
 *               read over HTTP from the sender's live cx-core gate on the shared hotspot, not from a
 *               SAF drive. readManifest() GETs the invite's manifest URL; openPayload(itemRef) GETs
 *               /v1/share/<id>/fetch/<itemRef>, retrying while the sender stages the item; the token
 *               rides the X-Share-Token header, never the URL. Everything downstream (stage to
 *               /library/.cx-staging, POST /v1/import/item) is unchanged from the disk path, so the
 *               carrier is the only sender-specific dependency of the import service. On a clean finish
 *               the carrier POSTs /complete so the gate releases the session (the receiver-driven close).
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import android.content.Context;
import android.net.Network;
import android.util.Log;

import org.appdevforall.k2go.contentexchange.domain.ImportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ShareTransport;
import org.appdevforall.k2go.contentexchange.domain.ShareUrls;
import org.appdevforall.k2go.sync.transport.WifiNetworkBinder;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * One Share session as an {@link ImportCarrier}, read over the hotspot from the sender's gate. The
 * invite was validated at the scan boundary (ShareInviteValidator), so the host, port, token and
 * manifest path are safe to compose into requests here; {@link ShareUrls} derives the per-item fetch
 * and complete URLs from the one manifest path.
 *
 * <p>Because the sender's hotspot has no internet, the system may route a plain socket over cellular
 * and never reach the peer. This pins only its own sockets to the Network whose subnet contains the
 * sender (via {@link WifiNetworkBinder}), so the receiver's loopback ingest and the rest of the app
 * keep normal networking. When no such Network is found (this device is the AP, or a direct route), it
 * falls back to the default connection.
 */
public final class NetworkImportCarrier implements ImportCarrier {

    private static final String TAG = "IIAB-CxNetCarrier";

    private static final int CONNECT_TIMEOUT_MS = 8000;
    /** Small JSON reads (manifest, complete response). */
    private static final int READ_TIMEOUT_MS = 15000;
    /** Per-read (inactivity) stall on the item stream, NOT a wall clock: a large item streams for
     *  minutes as long as bytes keep flowing; only a genuine stall trips it (mirrors the export fetch). */
    private static final int FETCH_STALL_TIMEOUT_MS = 60000;
    /** A manifest larger than this is treated as an error: the index is a few KB. */
    private static final long MANIFEST_MAX_BYTES = 8L * 1024 * 1024;

    /** Wait between fetch retries while the sender stages the item (Kolibri export tar). */
    private static final long STAGING_RETRY_MS = 1500L;
    /** Shorter wait when another stream holds the item (single-flight on the gate). */
    private static final long BUSY_RETRY_MS = 500L;
    /** Upper bound on retrying one stalled item, so a wedged sender fails the item (retryable) rather
     *  than looping forever. Generous: staging a multi-GB channel is slow but finite. */
    private static final long FETCH_MAX_WAIT_MS = 30L * 60 * 1000;

    /** Lets the carrier abort its retry wait when the import run is cancelled (the service wires it). */
    public interface Cancellation {
        boolean isCancelled();
    }

    private final Context appContext;
    private final String host;
    private final String baseUrl;
    private final String token;
    private final String manifestUrl;
    private final Cancellation cancel;

    public NetworkImportCarrier(Context context, String host, int port, String token,
                                String manifestUrl, Cancellation cancel) {
        this.appContext = context.getApplicationContext();
        this.host = host;
        this.baseUrl = ShareTransport.SCHEME + "://" + host + ":" + port;
        this.token = token;
        this.manifestUrl = manifestUrl;
        this.cancel = cancel != null ? cancel : () -> false;
    }

    @Override
    public String sourceLabel() {
        return host;
    }

    @Override
    public byte[] readManifest() throws IOException {
        HttpURLConnection c = open(ShareUrls.manifest(baseUrl, manifestUrl), "GET", READ_TIMEOUT_MS);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("Share manifest HTTP " + code + ": " + errorText(c));
            }
            try (InputStream in = c.getInputStream()) {
                return readCapped(in, MANIFEST_MAX_BYTES);
            }
        } finally {
            c.disconnect();
        }
    }

    @Override
    public List<DriveFile> listFiles() {
        // Share always carries a manifest, so the no-manifest folder scan never runs over the network.
        return Collections.emptyList();
    }

    @Override
    public InputStream openPayload(String itemRef) throws IOException {
        String url = ShareUrls.fetch(baseUrl, manifestUrl, itemRef);
        long deadline = System.currentTimeMillis() + FETCH_MAX_WAIT_MS;
        while (true) {
            if (cancel.isCancelled()) {
                throw new IOException("cancelled");
            }
            HttpURLConnection c = open(url, "GET", FETCH_STALL_TIMEOUT_MS);
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
            if (code == 200) {
                // The caller streams and closes this; closing disconnects the connection.
                try {
                    return new ConnectionInputStream(c, c.getInputStream());
                } catch (IOException e) {
                    c.disconnect();   // never leak the socket if the body stream cannot be opened
                    throw e;
                }
            }
            String state = stateOf(c);
            c.disconnect();
            if (code == 409 && ("staging".equals(state) || "busy".equals(state))) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("item did not become ready in time");
                }
                sleep("staging".equals(state) ? STAGING_RETRY_MS : BUSY_RETRY_MS);
                continue;   // the sender is staging or the slot is busy: retry the same URL
            }
            throw new IOException("Share fetch HTTP " + code
                    + (state != null ? " (" + state + ")" : ""));
        }
    }

    @Override
    public void onAllDelivered() {
        // Best-effort clean-success close: the gate also self-bounds (safety cap) and the sender closes
        // on its own teardown, so a failure here only delays the sender's release, never the import.
        try {
            HttpURLConnection c = open(ShareUrls.complete(baseUrl, manifestUrl), "POST", READ_TIMEOUT_MS);
            try {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                }
                c.getResponseCode();   // drive the request; the body is not needed
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            Log.i(TAG, "share complete failed (sender will self-bound): " + e.getMessage());
        }
    }

    // ---- HTTP plumbing -------------------------------------------------------------------------

    /** Open a connection to the sender, pinned to the Network that reaches its hotspot when one exists,
     *  with the share token in the header. The network is resolved per request, so a Wi-Fi reconnect to
     *  a new Network object is picked up on the next attempt. */
    private HttpURLConnection open(String urlStr, String method, int readTimeout) throws IOException {
        URL url = new URL(urlStr);
        Network net = WifiNetworkBinder.findHostNetwork(appContext, host);
        URLConnection raw = net != null ? net.openConnection(url) : url.openConnection();
        HttpURLConnection c = (HttpURLConnection) raw;
        c.setUseCaches(false);
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Share-Token", token);   // the only auth; never in the URL
        return c;
    }

    /** The {@code state} field of a 409/500 fetch body ("staging" / "busy" / "error"), or null. */
    private static String stateOf(HttpURLConnection c) {
        String text = errorText(c);
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(text).optString("state", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String errorText(HttpURLConnection c) {
        try (InputStream err = c.getErrorStream()) {
            if (err == null) {
                return "";
            }
            return new String(readCapped(err, 64 * 1024), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] readCapped(InputStream in, long max) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        long total = 0L;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > max) {
                throw new IOException("response exceeds " + max + " bytes");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cancelled");
        }
    }

    /** An item body that disconnects its HTTP connection when the caller closes the stream. */
    private static final class ConnectionInputStream extends FilterInputStream {
        private final HttpURLConnection connection;

        ConnectionInputStream(HttpURLConnection connection, InputStream body) {
            super(body);
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                connection.disconnect();
            }
        }
    }
}
