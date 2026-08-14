/*
 * ============================================================================
 * Name        : CatalogManifestClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Fetches a catalog manifest with a
 *               conditional GET (ETag / If-None-Match -> 304) and downloads the
 *               catalog body, mirroring the OTA update client's HTTP. Blocking;
 *               call from an IO thread. Never throws.
 * ============================================================================
 */
package org.iiab.controller.catalog.data;

import android.util.Log;

import org.iiab.controller.catalog.domain.CatalogManifest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class CatalogManifestClient {

    private static final String TAG = "K2Go-Catalog";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;

    public enum Status { OK, NOT_MODIFIED, FAILED }

    /** Outcome of a manifest fetch. {@code manifest}/{@code etag} are set only on {@code OK}. */
    public static final class Result {
        public final Status status;
        public final CatalogManifest manifest;
        public final String etag;

        private Result(Status status, CatalogManifest manifest, String etag) {
            this.status = status;
            this.manifest = manifest;
            this.etag = etag;
        }

        static Result ok(CatalogManifest m, String etag) { return new Result(Status.OK, m, etag); }
        static Result notModified() { return new Result(Status.NOT_MODIFIED, null, null); }
        static Result failed() { return new Result(Status.FAILED, null, null); }
    }

    /** GET the manifest; sends {@code If-None-Match} when {@code knownEtag} is set. */
    public Result fetchManifest(String url, String knownEtag) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            if (knownEtag != null && !knownEtag.isEmpty()) {
                conn.setRequestProperty("If-None-Match", knownEtag);
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return Result.notModified();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "manifest fetch HTTP " + code + " for " + url);
                return Result.failed();
            }
            String etag = conn.getHeaderField("ETag");
            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    body.append(line);
                }
            }
            CatalogManifest m = CatalogManifestParser.parse(body.toString());
            return m == null ? Result.failed() : Result.ok(m, etag);
        } catch (Throwable t) {
            Log.w(TAG, "manifest fetch failed: " + t.getMessage());
            return Result.failed();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Download the catalog body to {@code dest} via a temp file swapped in on success, so a
     * partial download never replaces a good overlay. Returns true on success.
     */
    public boolean downloadTo(String url, File dest) {
        HttpURLConnection conn = null;
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return false;
            }
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            return tmp.renameTo(dest);
        } catch (Throwable t) {
            Log.w(TAG, "catalog download failed: " + t.getMessage());
            tmp.delete();
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
