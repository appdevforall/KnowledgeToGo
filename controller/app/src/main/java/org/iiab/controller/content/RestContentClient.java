/*
 * ============================================================================
 * Name        : RestContentClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4840 (Phase 2 of ADFA-4832). App-side client of the in-server
 *               durable REST job engine (nginx localhost:8085 -> Node :4000, /api).
 *               Replaces the Phase 1 socket.io LiveContentClient: instead of holding
 *               a long-lived connection whose drop kills the job, it POSTs to start a
 *               job and then POLLs its structured status every ~1s. The job lives in
 *               the dashboard process (durable, resumable), so there is no connection
 *               to lose — the app can drop and re-attach by polling the same jobId.
 *               Still driven by the foreground InstallService so polling continues
 *               across UI/config changes.
 *
 *               Contract (static/dashboard/routes.ts + sockets/jobs.ts):
 *                 POST /api/kiwix/download {ids:[...]}       -> { id, ... }
 *                 GET  /api/kiwix/jobs/:id                   -> { phase, percent,
 *                                                                 speed(bytes/s),
 *                                                                 detail, error }
 *                 POST /api/kiwix/jobs/:id/cancel            -> { ok:true }
 *               phase is one of: queued|downloading|indexing|processing|done|error|canceled.
 * ============================================================================
 */
package org.iiab.controller.content;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

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

public final class RestContentClient {

    /** Same shape as the Phase 1 client so callers swap in with no behavior change.
     *  {@code speed} is a display token WITHOUT the per-second suffix (caller appends it). */
    public interface Listener {
        void onProgress(int percent, String speed); // download phase
        void onIndexing();                           // indexing phase (indeterminate)
        void onLog(String line);                     // free-form detail line (for logs)
        void onDone();                               // success (terminal)
        void onError(String message);                // failure (terminal)
    }

    private static final String BASE = BoxEndpoints.BASE + "/api/kiwix";
    private static final long POLL_MS = 1000L;
    private static final int MAX_POLL_ERRORS = 10;   // tolerate ~10s of transient network blips

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile String jobId;
    private volatile boolean finished = false;
    private volatile boolean indexing = false;
    private int pollErrors = 0;

    private final Runnable pollTask = () -> AppExecutors.get().io().execute(this::pollOnce);

    /** Start (download + index) one ZIM on the running server and poll to completion. */
    public void addZim(@NonNull String zimFilename, @NonNull Listener l) {
        this.listener = l;
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject body = new JSONObject().put("ids", new JSONArray().put(zimFilename));
                JSONObject resp = httpJson("POST", BASE + "/download", body);
                String id = resp.optString("id", "");
                if (id.isEmpty()) { fail("content service did not start the job"); return; }
                jobId = id;
                main.postDelayed(pollTask, POLL_MS);
            } catch (Exception e) {
                fail("couldn't reach the content service");
            }
        });
    }

    private void pollOnce() {
        if (finished) return;
        try {
            JSONObject j = httpJson("GET", BASE + "/jobs/" + jobId, null);
            pollErrors = 0;
            String phase = j.optString("phase", "");
            int percent = j.optInt("percent", -1);
            long speed = j.optLong("speed", 0L);
            String detail = j.isNull("detail") ? null : j.optString("detail", null);
            String error = j.isNull("error") ? null : j.optString("error", null);

            if (detail != null && !detail.isEmpty()) deliver(() -> listener.onLog(detail));

            switch (phase) {
                case "downloading":
                    if (percent >= 0) {
                        final int p = percent; final String rate = formatRate(speed);
                        deliver(() -> listener.onProgress(p, rate));
                    }
                    break;
                case "indexing":
                case "processing":
                    if (!indexing) { indexing = true; deliver(() -> listener.onIndexing()); }
                    break;
                case "done":
                    done();
                    return;
                case "error":
                    fail(error != null ? error : "download failed");
                    return;
                case "canceled":
                    fail("canceled");
                    return;
                default:
                    break; // queued / unknown: keep polling
            }
            main.postDelayed(pollTask, POLL_MS);
        } catch (Exception e) {
            if (++pollErrors > MAX_POLL_ERRORS) { fail("lost contact with the content service"); return; }
            main.postDelayed(pollTask, POLL_MS);
        }
    }

    /** Best-effort cancel of the in-flight job. */
    public void cancel() {
        final String id = jobId;
        if (id != null && !id.isEmpty()) {
            AppExecutors.get().io().execute(() -> {
                try { httpJson("POST", BASE + "/jobs/" + id + "/cancel", null); } catch (Exception ignore) { /* best effort */ }
            });
        }
        teardown();
    }

    private void done() {
        if (finished) return;
        finished = true;
        final Listener l = listener;
        teardown();
        if (l != null) deliver(l::onDone);
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        final Listener l = listener;
        teardown();
        if (l != null) deliver(() -> l.onError(message));
    }

    private void teardown() {
        main.removeCallbacks(pollTask);
    }

    private void deliver(Runnable r) {
        if (listener == null) return;
        main.post(r);
    }

    /** bytes/sec -> a short display token ("3.4 MB"); the caller appends the localized "/s". */
    private static String formatRate(long bps) {
        if (bps <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        double v = bps;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return i == 0
                ? String.format(java.util.Locale.US, "%.0f %s", v, units[i])
                : String.format(java.util.Locale.US, "%.1f %s", v, units[i]);
    }

    private static JSONObject httpJson(String method, String urlStr, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) { os.write(payload); }
            }
            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 400;
            String text = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) throw new Exception("HTTP " + code + ": " + text);
            return new JSONObject(text.isEmpty() ? "{}" : text);
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
