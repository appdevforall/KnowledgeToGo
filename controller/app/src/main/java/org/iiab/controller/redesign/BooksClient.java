/*
 * ============================================================================
 * Name        : BooksClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4850. App-side client for the dashboard's Books REST endpoints:
 *                 GET  /api/books/search?q=&filter=&limit=  -> catalog rows (offline FTS)
 *                 GET  /api/books/library                   -> Calibre-Web library rows
 *                 POST /api/books/library/:id/remove        -> delete
 *               (The actual download is a durable job driven by BooksDownloadService.)
 *               Search works offline (the catalog is synced on the server); covers + the EPUB
 *               download need internet.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Handler;
import android.os.Looper;

import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.util.AppExecutors;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class BooksClient {
    private BooksClient() {}

    private static final String BASE = BoxEndpoints.BASE + "/api/books";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface ArrayCb { void onOk(JSONArray rows); void onErr(String message); }
    public interface OkCb { void onOk(); void onErr(String message); }

    /** Search the offline Gutenberg catalog. filter: ""|"educational"; q empty => top-by-downloads. */
    public static void search(String q, String filter, int limit, ArrayCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                String url = BASE + "/search?limit=" + limit
                        + "&filter=" + enc(filter == null ? "" : filter)
                        + "&q=" + enc(q == null ? "" : q);
                JSONArray a = new JSONArray(httpGet(url));
                MAIN.post(() -> cb.onOk(a));
            } catch (Exception e) {
                MAIN.post(() -> cb.onErr("couldn't reach the content service"));
            }
        });
    }

    /** The local Calibre-Web library (books already added). */
    public static void library(ArrayCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONArray a = new JSONArray(httpGet(BASE + "/library"));
                MAIN.post(() -> cb.onOk(a));
            } catch (Exception e) {
                MAIN.post(() -> cb.onErr("couldn't reach the content service"));
            }
        });
    }

    /** Remove a book from the Calibre-Web library by its id. */
    public static void remove(int id, OkCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                httpPostEmpty(BASE + "/library/" + id + "/remove");
                MAIN.post(cb::onOk);
            } catch (Exception e) {
                MAIN.post(() -> cb.onErr("remove failed"));
            }
        });
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            String text = readAll(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code + ": " + text);
            return text.isEmpty() ? "[]" : text;
        } finally {
            c.disconnect();
        }
    }

    private static void httpPostEmpty(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) buf.write(chunk, 0, n);
        is.close();
        return buf.toString(StandardCharsets.UTF_8.name());
    }
}
