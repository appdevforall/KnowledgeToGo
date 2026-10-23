/*
 * ============================================================================
 * Name        : CxCoreDataSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. App-side reader of the content-exchange core (cx-core)
 *               through dash-node's relay (/k2go-api/v1/*). Read side only
 *               (list + storage); the export / import / delete calls are later
 *               slices. Same HttpURLConnection shape as content/RestContentClient.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.data;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
}
