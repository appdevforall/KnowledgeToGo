/*
 * ============================================================================
 * Name        : PdfViewerCatalog.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Loads the pdf.js viewer manifest served by the box (ADFA-4708).
 * ============================================================================
 */
package org.iiab.controller.portal.data;

import org.iiab.controller.config.BoxEndpoints;

import android.util.Log;

import org.iiab.controller.portal.domain.PdfViewerBuild;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads {@code /pdfjs/manifest.json} to learn which pdf.js builds the box serves.
 * The manifest is the single source of truth, so the app carries no version-specific code:
 * whatever builds are listed are the ones the router ({@code PdfViewerRouter}) may choose.
 * Network I/O — call off the main thread. Parsing is separated so it is unit-testable.
 */
public final class PdfViewerCatalog {

    private static final String TAG = "PdfViewerCatalog";
    private static final String MANIFEST_URL = BoxEndpoints.BASE + "/pdfjs/manifest.json";
    private static final int TIMEOUT_MS = 4000;

    private PdfViewerCatalog() {}

    /** Fetches and parses the manifest; returns an empty list on any failure. */
    public static List<PdfViewerBuild> fetch() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(MANIFEST_URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "manifest fetch returned HTTP " + code);
                return Collections.emptyList();
            }
            try (InputStream in = conn.getInputStream()) {
                return parse(readAll(in));
            }
        } catch (Exception e) {
            Log.d(TAG, "manifest unavailable: " + e.getMessage());
            return Collections.emptyList();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Parses a manifest JSON document into builds; skips malformed entries. */
    public static List<PdfViewerBuild> parse(String json) throws JSONException {
        List<PdfViewerBuild> builds = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return builds;
        }
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("builds");
        if (arr == null) {
            return builds;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            String id = o.optString("id", null);
            String viewerPath = o.optString("viewerPath", null);
            int minMajor = o.optInt("minWebViewMajor", -1);
            if (id == null || viewerPath == null || viewerPath.isEmpty() || minMajor < 0) {
                continue;
            }
            builds.add(new PdfViewerBuild(id, viewerPath, minMajor));
        }
        return builds;
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
