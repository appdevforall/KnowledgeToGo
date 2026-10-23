/*
 * ============================================================================
 * Name        : CxCoreDataSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. App-side client of the content-exchange core (cx-core)
 *               through dash-node's relay (/k2go-api/v1/*). Read side (list + storage)
 *               plus the export calls (plan + streaming item). Same HttpURLConnection
 *               shape as content/RestContentClient. The one place this feature touches
 *               HttpURLConnection.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The one place in this feature that touches {@link HttpURLConnection}. It issues
 * GETs to the cx-core read endpoints and returns the parsed JSON object; mapping
 * to domain entities lives in {@link ContentRepositoryImpl}.
 *
 * <p>Must be called off the main thread.
 */
public final class CxCoreDataSource {

    /** cx-core API root behind dash-node's relay: {@code http://localhost:8085/k2go-api/v1}. */
    private static final String V1 = BoxEndpoints.API + "/v1";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** Delete blocks on the box's own delete (Kolibri deletechannel, Kiwix reindex), which can run
     *  well past the read-side timeout, so it waits longer than a plain read. */
    private static final int DELETE_READ_TIMEOUT_MS = 120000;

    /** {@code GET /v1/apps} -> {@code {"apps": [...]}}. */
    public JSONObject getApps() throws Exception {
        return getJson(V1 + "/apps");
    }

    /** {@code GET /v1/content} -> {@code {"items": [...]}} (all apps). */
    public JSONObject getContent() throws Exception {
        return getJson(V1 + "/content");
    }

    /** {@code GET /v1/storage} -> the storage summary. May 404 until the core adds it. */
    public JSONObject getStorage() throws Exception {
        return getJson(V1 + "/storage");
    }

    /** {@code POST /v1/export/plan} with {@code {items:[{app,localRef}]}} -> the plan JSON. */
    public JSONObject postExportPlan(JSONObject body) throws Exception {
        return postJson(V1 + "/export/plan", body);
    }

    /**
     * {@code DELETE /v1/content/{app}/{localRef}} -> {@code {ok, freedBytes}} on success or
     * {@code {ok:false, reason}} on an adapter failure. Path segments are percent-encoded (the core
     * decodes them). A 4xx/5xx (e.g. 404 for an item already gone) throws, like the other calls.
     */
    public JSONObject deleteContent(String app, String localRef) throws Exception {
        return deleteJson(V1 + "/content/" + encodeSegment(app) + "/" + encodeSegment(localRef));
    }

    /**
     * {@code GET /v1/export/item/{app}/{localRef}} -> the item's streaming archive. The core sends a
     * chunked response with no Content-Length; the caller streams the body and closes the returned
     * handle to release the connection. Path segments are percent-encoded (the core decodes them).
     */
    public Stream openExportItem(String app, String localRef) throws Exception {
        String url = V1 + "/export/item/" + encodeSegment(app) + "/" + encodeSegment(localRef);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/octet-stream");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) {
                String text = readAll(c.getErrorStream());
                c.disconnect();
                throw new Exception("HTTP " + code + ": " + text);
            }
            String filename = parseContentDispositionFilename(c.getHeaderField("Content-Disposition"));
            return new Stream(c, c.getInputStream(), filename);
        } catch (Exception e) {
            c.disconnect();
            throw e;
        }
    }

    private static JSONObject postJson(String urlStr, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) {
                throw new Exception("HTTP " + code + ": " + text);
            }
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject deleteJson(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(DELETE_READ_TIMEOUT_MS);
            c.setRequestMethod("DELETE");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) {
                throw new Exception("HTTP " + code + ": " + text);
            }
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject getJson(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) {
                throw new Exception("HTTP " + code + ": " + text);
            }
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        is.close();
        return buf.toString(StandardCharsets.UTF_8.name());
    }

    /** Percent-encode one path segment (keep it a single segment; the core calls unquote). */
    private static String encodeSegment(String s) throws Exception {
        if (s == null) {
            return "";
        }
        // URLEncoder is form encoding: a space becomes "+", which a path reads literally, so fix it.
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    /** Pull the {@code filename="..."} from a Content-Disposition header, or "" when absent. */
    private static String parseContentDispositionFilename(String header) {
        if (header == null) {
            return "";
        }
        int at = header.toLowerCase(java.util.Locale.US).indexOf("filename=");
        if (at < 0) {
            return "";
        }
        String value = header.substring(at + "filename=".length()).trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            return end > 0 ? value.substring(1, end) : value.substring(1);
        }
        int semi = value.indexOf(';');
        return semi >= 0 ? value.substring(0, semi).trim() : value;
    }

    /** A live streaming response: the body plus the server-suggested name. Close to release the socket. */
    public static final class Stream implements Closeable {

        private final HttpURLConnection connection;
        private final InputStream body;
        private final String filename;

        Stream(HttpURLConnection connection, InputStream body, String filename) {
            this.connection = connection;
            this.body = body;
            this.filename = filename;
        }

        public InputStream body() {
            return body;
        }

        public String filename() {
            return filename;
        }

        @Override
        public void close() {
            try {
                if (body != null) {
                    body.close();
                }
            } catch (Exception ignored) {
                // closing anyway
            }
            connection.disconnect();
        }
    }
}
