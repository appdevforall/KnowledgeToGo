/*
 * ============================================================================
 * Name        : CxCoreDataSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427 / K2GO-507. App-side client of the content-exchange core (cx-core)
 *               through dash-node's relay (/k2go-api/v1/*). Read side (list + storage)
 *               plus the export long operation (prepare / status / fetch / abort) and the
 *               import calls. Same HttpURLConnection shape as content/RestContentClient. The
 *               one place this feature touches HttpURLConnection.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
    /** K2GO-507: the export fetch read timeout is a per-read (inactivity) gap, NOT a total wall
     *  clock. {@link HttpURLConnection#setReadTimeout} fires only when a single read blocks this
     *  long with no bytes, and it resets on every read, so a multi-GB staged tar can stream for
     *  minutes as long as bytes keep flowing; only a genuine stall trips it. The op is already
     *  staged when we fetch, so the response head returns fast. */
    private static final int FETCH_STALL_TIMEOUT_MS = 60000;

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

    /** {@code POST /v1/import/validate} with {@code {items:[<entry>]}} -> {@code {results:[...]}}. */
    public JSONObject postImportValidate(JSONObject body) throws Exception {
        return postJson(V1 + "/import/validate", body);
    }

    /** {@code POST /v1/import/item} with {@code {app, entry, payloadPath}} -> the ingest result. */
    public JSONObject postImportItem(JSONObject body) throws Exception {
        return postJson(V1 + "/import/item", body);
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
     * {@code POST /v1/export/prepare} with {@code {app, localRef}} -> {@code {opId, state, totalBytes}}.
     * Starts (or finds nothing to stage for) the export operation; returns fast. K2GO-507.
     */
    public JSONObject postExportPrepare(String app, String localRef) throws Exception {
        JSONObject body = new JSONObject().put("app", app).put("localRef", localRef);
        return postJson(V1 + "/export/prepare", body);
    }

    /**
     * {@code GET /v1/export/status/{opId}} -> {@code {state, stagedBytes, totalBytes, error?}}. A 404
     * means the op was swept; it surfaces as {@link CxHttpException} with {@code code() == 404} so the
     * caller can re-prepare rather than keep polling a dead id. K2GO-507.
     */
    public JSONObject getExportStatus(String opId) throws Exception {
        return getJson(V1 + "/export/status/" + encodeSegment(opId));
    }

    /**
     * {@code GET /v1/export/fetch/{opId}} -> the staged archive as a chunked stream. The op is already
     * staged, so the response head returns fast; the body uses a per-read STALL timeout (see
     * {@link #FETCH_STALL_TIMEOUT_MS}), not a wall clock. The caller streams the body and closes the
     * returned handle to release the connection (the core clears the op on close). K2GO-507.
     */
    public Stream openExportFetch(String opId) throws Exception {
        String url = V1 + "/export/fetch/" + encodeSegment(opId);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(FETCH_STALL_TIMEOUT_MS);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/octet-stream");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) {
                String text = readAll(c.getErrorStream());
                c.disconnect();
                throw new CxHttpException(code, text);
            }
            String filename = parseContentDispositionFilename(c.getHeaderField("Content-Disposition"));
            return new Stream(c, c.getInputStream(), filename);
        } catch (Exception e) {
            c.disconnect();
            throw e;
        }
    }

    /** {@code DELETE /v1/export/{opId}} -> {@code {ok}}. Client abort of an in-flight op. K2GO-507. */
    public JSONObject deleteExportOp(String opId) throws Exception {
        return deleteJson(V1 + "/export/" + encodeSegment(opId));
    }

    // ---- share gate (K2GO-376) -----------------------------------------------------------------
    // These hit the sender's OWN cx-core over the loopback relay. The receiver reaches the same
    // session over the hotspot (a separate off-loopback exposure); the app never copies bytes. The
    // token minted here is the session's only auth and travels in the X-Share-Token header.

    /**
     * {@code POST /v1/share} with {@code {items:[{app,localRef}]}} -> {@code {sessionId, token,
     * manifestUrl}}. Opens a scoped share session on the local cx-core and returns the token the
     * sender puts in the QR; no token header (this call mints it). K2GO-376.
     */
    public ShareCreated postShareCreate(List<ItemRef> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (ItemRef ref : items) {
            arr.put(new JSONObject().put("app", ref.app()).put("localRef", ref.localRef()));
        }
        JSONObject body = new JSONObject().put("items", arr);
        JSONObject res = postJson(V1 + "/share", body, null);
        return new ShareCreated(
                res.optString("sessionId", ""),
                res.optString("token", ""),
                res.optString("manifestUrl", ""));
    }

    /**
     * {@code POST /v1/share/{sessionId}/complete} (X-Share-Token) -> {@code {ok}}. The clean-success
     * close; the receiver drives it. A 404 means already closed (harmless). K2GO-376.
     */
    public JSONObject postShareComplete(String sessionId, String token) throws Exception {
        return postJson(V1 + "/share/" + encodeSegment(sessionId) + "/complete", new JSONObject(), token);
    }

    /**
     * {@code DELETE /v1/share/{sessionId}} (X-Share-Token) -> {@code {ok}}. Cancel / abort, by host
     * or receiver; the sender's foreground service calls it on teardown (the app-side closer). A 404
     * means already closed, so a double close is harmless. K2GO-376.
     */
    public JSONObject deleteShare(String sessionId, String token) throws Exception {
        return deleteJson(V1 + "/share/" + encodeSegment(sessionId), token);
    }

    /** The result of opening a share session: the id, the auth token, and the manifest path. */
    public static final class ShareCreated {
        public final String sessionId;
        public final String token;
        public final String manifestUrl;

        ShareCreated(String sessionId, String token, String manifestUrl) {
            this.sessionId = sessionId;
            this.token = token;
            this.manifestUrl = manifestUrl;
        }
    }

    private static JSONObject postJson(String urlStr, JSONObject body) throws Exception {
        return postJson(urlStr, body, null);
    }

    /** POST with an optional {@code X-Share-Token} header (K2GO-376: the token is the gate's only
     *  auth, so it rides a header, never the URL query, to stay out of relay / nginx access logs). */
    private static JSONObject postJson(String urlStr, JSONObject body, String shareToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            if (shareToken != null) {
                c.setRequestProperty("X-Share-Token", shareToken);
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) {
                throw new CxHttpException(code, text);
            }
            return new JSONObject(text.isEmpty() ? "{}" : text);
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject deleteJson(String urlStr) throws Exception {
        return deleteJson(urlStr, null);
    }

    /** DELETE with an optional {@code X-Share-Token} header (K2GO-376; see {@link #postJson}). */
    private static JSONObject deleteJson(String urlStr, String shareToken) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(DELETE_READ_TIMEOUT_MS);
            c.setRequestMethod("DELETE");
            c.setRequestProperty("Accept", "application/json");
            if (shareToken != null) {
                c.setRequestProperty("X-Share-Token", shareToken);
            }
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) {
                throw new CxHttpException(code, text);
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
                throw new CxHttpException(code, text);
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

    /**
     * A non-2xx/3xx HTTP response, carrying the status code so a caller can branch on it: the export
     * status poll treats {@code code() == 404} as "operation swept" and re-prepares. Extends
     * {@link Exception}, so callers that only {@code catch (Exception)} keep working. K2GO-507.
     */
    public static final class CxHttpException extends Exception {
        private final int code;

        CxHttpException(int code, String message) {
            super("HTTP " + code + ": " + message);
            this.code = code;
        }

        public int code() {
            return code;
        }
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
