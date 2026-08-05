/*
 * ============================================================================
 * Name        : DashboardClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5026. App-side client for the dash-node REST core's update check:
 *                 GET /k2go-api/system/dashboard/update-check
 *                     -> { installed, available, updateAvailable }
 *               Unlike the version chip (read from disk, offline via DashboardVersion), this asks the
 *               server whether a newer build exists on the mainline remote — so it needs the box up and
 *               online. The server does a git fetch (bounded), hence the longer read timeout here; on
 *               any failure the caller keeps its last-known state (see UpdateStatusCache). Only
 *               reachable on-device (localhost); the box denies the API to remote clients.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Handler;
import android.os.Looper;

import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.util.AppExecutors;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class DashboardClient {
    private DashboardClient() {}

    private static final String URL_UPDATE_CHECK = BoxEndpoints.API + "/system/dashboard/update-check";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface UpdateCb {
        void onResult(String installed, String available, boolean updateAvailable);
        void onErr(String message);
    }

    /** Ask the box whether a newer dash-node build is available. Runs off the main thread; the result
     *  (or an error, when the box is stopped/offline) is posted back to the UI. */
    public static void updateCheck(UpdateCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject o = new JSONObject(httpGet(URL_UPDATE_CHECK));
                final String installed = o.optString("installed", "unknown");
                final String available = o.optString("available", "unknown");
                final boolean updateAvailable = o.optBoolean("updateAvailable", false);
                MAIN.post(() -> cb.onResult(installed, available, updateAvailable));
            } catch (Exception e) {
                MAIN.post(() -> cb.onErr("update check unavailable"));
            }
        });
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(5000);
            // The server may run a `git fetch` before answering (bounded server-side to ~30s: 20s fetch
            // + 10s show). Keep the client read timeout above that so it never fires just as the server
            // is replying — including its own 503.
            c.setReadTimeout(35000);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            String text = readAll(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code + ": " + text);
            return text;
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
