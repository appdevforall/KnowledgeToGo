/*
 * ============================================================================
 * Name        : KiwixClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5004. App-side client for the dashboard's Kiwix ZIM-management REST endpoints:
 *                 GET  /api/kiwix/library        -> installed ZIMs [{ name, bytes, mtime }, ...]
 *                 POST /api/kiwix/delete {name}  -> { ok } (unlink + rebuild the Kiwix index)
 *               Mirrors BooksClient/MapsRegionClient: short, stateless calls off the main thread with
 *               results posted back to the UI. Only reachable on-device (localhost); the box denies
 *               /api to remote clients. The ZIM download itself stays a durable job (KiwixDownload).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Handler;
import android.os.Looper;

import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.util.AppExecutors;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class KiwixClient {
    private KiwixClient() {}

    private static final String BASE = BoxEndpoints.API + "/kiwix";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface ArrayCb { void onOk(JSONArray rows); void onErr(String message); }
    public interface OkCb { void onOk(); void onErr(String message); }

    /** The ZIMs currently installed on the box (rows: { name, bytes, mtime }), newest first. */
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

    /** Delete one installed ZIM by its file name (e.g. "wikipedia_en_all_maxi_2024-01.zim").
     *  The server removes the file and rebuilds the Kiwix index; this returns when that completes. */
    public static void delete(String name, OkCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                httpPostJson(BASE + "/delete", new JSONObject().put("name", name));
                MAIN.post(cb::onOk);
            } catch (Exception e) {
                MAIN.post(() -> cb.onErr("delete failed"));
            }
        });
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

    private static void httpPostJson(String urlStr, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(5000);
            // The delete rebuilds the Kiwix index server-side; give it room.
            c.setReadTimeout(60000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Accept", "application/json");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) {
                String text = readAll(c.getErrorStream());
                throw new Exception("HTTP " + code + ": " + text);
            }
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
