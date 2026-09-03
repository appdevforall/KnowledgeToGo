/*
 * ============================================================================
 * Name        : StudioTreeSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Live reads of a channel's topic tree from Kolibri Studio's
 *               public API. Blocking; call from an IO thread (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.data;

import android.util.Log;

import org.appdevforall.k2go.kolibri.domain.ChannelId;
import org.appdevforall.k2go.kolibri.domain.TopicNode;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Reads topic trees straight from Studio, over the internet.
 *
 * <p>The only part of the catalog that is fetched at runtime. Channels come from
 * the bundled asset instead, because Studio's channel endpoint is 97 % base64
 * thumbnails with no way to opt out (ADR-4954 D1). A tree is per-channel and
 * carries no images, so it is cheap enough to fetch when the user opens one.
 *
 * <p>Unlike every other client in the app, this talks to the public internet
 * rather than the box on localhost. Timeouts are correspondingly generous: a
 * large channel's tree is a big JSON document over a connection that, in the
 * deployments this product targets, is usually poor.
 *
 * <p>Blocking by design, so the caller controls threading. Never throws: a
 * failure is a {@code null} return, per the {@code CatalogRepository} contract.
 */
public final class StudioTreeSource implements TreeSource {

    private static final String TAG = "K2Go-Kolibri";

    /** Overridable so a test or a mirror can point elsewhere. */
    private final String baseUrl;

    private static final String DEFAULT_BASE = "https://studio.learningequality.org";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 25000;

    /** Refuse absurd payloads rather than filling the heap on a phone. */
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    public StudioTreeSource() {
        this(DEFAULT_BASE);
    }

    public StudioTreeSource(String baseUrl) {
        String b = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_BASE : baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /**
     * One level of the tree rooted at {@code nodeId}, with its direct children.
     *
     * @return the subtree, or null when the request failed or the body was not
     *         a usable node
     */
    @Override
    public TopicNode fetchTree(String nodeId) {
        // Validate before the id reaches a URL path, not after. Every caller
        // today passes an already-normalised Channel.rootNodeId(), but this
        // method is public and a value with a slash or a query string in it
        // would build a request to somewhere else entirely. Fail closed, the
        // way ModuleName, AdbShellCommand and ArchiveEntry do at their own
        // boundaries.
        String id = ChannelId.normalise(nodeId);
        if (id == null) {
            return null;
        }
        String url = baseUrl + "/api/public/v2/contentnode_tree/" + id;
        try {
            String body = httpGet(url);
            if (body.isEmpty()) {
                return null;
            }
            return StudioCatalogMapper.tree(new JSONObject(body));
        } catch (Exception e) {
            Log.w(TAG, "studio tree " + nodeId + " failed: " + e.getMessage());
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
        // try-with-resources rather than a close() at each exit: a read() that
        // throws part-way used to leak the stream.
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
