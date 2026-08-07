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
    private static final String URL_REBUILD = BoxEndpoints.API + "/system/dashboard/rebuild";
    private static final String URL_REBUILD_STATUS = BoxEndpoints.API + "/system/dashboard/rebuild/status";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface UpdateCb {
        void onResult(String installed, String available, boolean updateAvailable);
        void onErr(String message);
    }

    /** ADFA-5051: result of triggering the live REST rebuild. {@code alreadyRunning} = the box reported
     *  a rebuild already in progress (HTTP 409) — the caller can just start polling status. */
    public interface RebuildStartCb {
        void onStarted(boolean alreadyRunning);
        void onErr(String message);
    }

    /** ADFA-5051: current rebuild state from the box: idle | running | done | error. */
    public interface RebuildStatusCb {
        void onState(String state);
        void onErr(String message);
    }

    /** ADFA-5051: trigger the in-server blue-green rebuild (POST). Fire-and-forget: the box returns 202
     *  at once (or 409 if one is already running); the caller then polls {@link #rebuildStatus}. */
    public static void rebuildStart(RebuildStartCb cb) {
        AppExecutors.get().io().execute(() -> {
            int[] status = {0};
            try {
                httpPost(URL_REBUILD, status);
                MAIN.post(() -> cb.onStarted(false));
            } catch (Exception e) {
                if (status[0] == 409) { MAIN.post(() -> cb.onStarted(true)); return; }
                MAIN.post(() -> cb.onErr("could not start rebuild"));
            }
        });
    }

    /** ADFA-5051: read the rebuild state file the script writes (idle/running/done/error). */
    public static void rebuildStatus(RebuildStatusCb cb) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject o = new JSONObject(httpGet(URL_REBUILD_STATUS));
                final String state = o.optString("state", "idle");
                MAIN.post(() -> cb.onState(state));
            } catch (Exception e) {
                MAIN.post(() -> cb.onErr("status unavailable"));
            }
        });
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

    /** POST with no body; {@code statusOut[0]} receives the HTTP status so callers can map 409. The
     *  trigger returns immediately (202), so a short read timeout is fine. */
    private static void httpPost(String urlStr, int[] statusOut) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");   // bodyless trigger; no doOutput/body needed
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            statusOut[0] = code;
            readAll(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
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
