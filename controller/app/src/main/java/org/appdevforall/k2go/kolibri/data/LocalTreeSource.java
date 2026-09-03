/*
 * ============================================================================
 * Name        : LocalTreeSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Reads a channel's topic tree from the box on localhost, when the
 *               channel's metadata has been imported there. Offline path for
 *               topic browsing (ADFA-5094).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.data;

import android.util.Log;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.kolibri.domain.ChannelId;
import org.appdevforall.k2go.kolibri.domain.TopicNode;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Reads topic trees from the box's Kolibri REST core, not the internet.
 *
 * <p>The offline counterpart to {@link StudioTreeSource}. Once a channel has been
 * metadata-imported on the box (its tree DB present, the content not necessarily
 * downloaded — ADFA-5094 PR3), the box can answer the same shape Studio does, so
 * the picker browses the whole tree with no connectivity. Until that import
 * exists the endpoint returns 404 and this source returns {@code null}, which is
 * the signal for {@link FallbackTreeSource} to try Studio instead.
 *
 * <p>The response is deliberately the <em>same</em> JSON as Studio's
 * {@code contentnode_tree}, so {@link StudioCatalogMapper#tree(JSONObject)}
 * parses both with no branching. The box owns that translation (PR3); the device
 * side stays a thin read.
 *
 * <p>Talks to localhost, so timeouts are tight — a slow box is a bug, not the
 * poor long-haul link {@link StudioTreeSource} plans for. Blocking by design;
 * never throws, per the {@link TreeSource} contract.
 */
public final class LocalTreeSource implements TreeSource {

    private static final String TAG = "K2Go-Kolibri";

    // Endpoint under the box API base: /k2go-api/kolibri/subtree/<nodeId>. Deliberately NOT
    // /kolibri/tree/:channelId — that one is the web wizard's granular tree (counts, no bytes).
    // This one returns Studio-shaped JSON with byte sizes, so StudioCatalogMapper parses it (ADFA-5094).
    private static final String TREE_PATH = "/kolibri/subtree/";

    /** Box API base ({@code …:8085/k2go-api}); overridable so a test can point at a mock. */
    private static final String DEFAULT_BASE = BoxEndpoints.API;

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 10000;

    /** Refuse absurd payloads rather than filling the heap on a phone. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    /** The box API base, without a trailing slash. */
    private final String baseUrl;

    public LocalTreeSource() {
        this(DEFAULT_BASE);
    }

    /**
     * @param baseUrl the box API base (e.g. {@code http://localhost:8085/k2go-api}); a
     *                trailing slash is stripped so no URL this builds carries a double slash.
     *                Exists so a test can point at a {@code MockWebServer}, mirroring
     *                {@link StudioTreeSource}.
     */
    public LocalTreeSource(String baseUrl) {
        String b = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_BASE : baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    @Override
    public TopicNode fetchTree(String nodeId) {
        // Validate before the id reaches a URL path, exactly as StudioTreeSource
        // does: a value with a slash or a query string would address something
        // else entirely on the box. Fail closed.
        String id = ChannelId.normalise(nodeId);
        if (id == null) {
            return null;
        }
        try {
            String body = httpGet(baseUrl + TREE_PATH + id);
            if (body.isEmpty()) {
                return null;
            }
            return StudioCatalogMapper.tree(new JSONObject(body));
        } catch (Exception e) {
            // A miss here is ordinary — the channel may simply not be imported
            // yet — so this is debug, not a warning: the fallback covers it.
            Log.d(TAG, "local tree " + nodeId + " unavailable: " + e.getMessage());
            return null;
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            InputStream is = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
            String text = readAll(is);
            if (code < 200 || code >= 400) {
                throw new Exception("HTTP " + code);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        try (InputStream in = is) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new Exception("response over " + (MAX_BYTES / (1024 * 1024)) + " MB");
                }
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.UTF_8.name());
        }
    }
}
