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
 *               Contract (static/dashboard/routes.ts + sockets/jobs.ts), per content <type>
 *               ("kiwix", "books", ...) — ADFA-4893 made this client type-parametric:
 *                 POST /api/<type>/download <body>           -> { id, ... }
 *                 GET  /api/<type>/jobs/:id                  -> { phase, percent, speed(bytes/s),
 *                                                                 detail, error, retryAttempt, retryTotal }
 *                 POST /api/<type>/jobs/:id/{cancel,pause,resume} -> { ok:true }
 *               The poll is interpreted by percent + retry state, not the exact phase name, so kiwix
 *               (downloading/indexing) and books (processing) can share one client.
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
        /** ADFA-4893: 'paused' phase (best-effort). Default no-op so existing implementers compile unchanged. */
        default void onPaused(int percent) {}
        /** ADFA-4893: server is reconnecting (attempt n of total) after a network drop; keeps polling. */
        default void onReconnecting(int attempt, int total) {}
    }

    private final String base;   // ADFA-4893: per-type API base (/api/kiwix, /api/books, ...)
    private static final long POLL_MS = 1000L;
    private static final int MAX_POLL_ERRORS = 10;   // tolerate ~10s of transient network blips
    // ADFA-4893: the START (download) POST is before any server job exists, so the server's own
    // reconnect can't cover a still-warming box; give the kickoff a few quick tries before failing.
    private static final int START_TRIES = 3;
    private static final long START_RETRY_MS = 2000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile String jobId;
    private volatile boolean finished = false;
    private volatile boolean indexing = false;
    private int pollErrors = 0;

    private final Runnable pollTask = () -> AppExecutors.get().io().execute(this::pollOnce);

    /** ZIM/kiwix by default (back-compat with existing callers). */
    public RestContentClient() { this("kiwix"); }

    /** ADFA-4893: shared client for any content type ("kiwix", "books", ...) -> /api/&lt;type&gt;. */
    public RestContentClient(@NonNull String type) { this.base = BoxEndpoints.API + "/" + type; }

    /** Start (download + index) one ZIM and poll to completion. Thin wrapper over {@link #start}. */
    public void addZim(@NonNull String zimFilename, @NonNull Listener l) {
        this.listener = l;
        final JSONObject body;
        try { body = new JSONObject().put("ids", new JSONArray().put(zimFilename)); }
        catch (Exception e) { fail("couldn't build the request"); return; }
        start(body, l);
    }

    /** ADFA-4893: generic start — POST the type-specific body to /download, then poll to completion.
     *  Kiwix passes {ids:[...]}, books {items:[{id,title,url}]}; this client is body-agnostic. */
    public void start(@NonNull JSONObject body, @NonNull Listener l) {
        this.listener = l;
        AppExecutors.get().io().execute(() -> {
            // ADFA-4893: retry ONLY the kickoff POST (connectivity blip / server still warming) a few
            // times. An empty id is a server refusal, not a blip, so it fails without retrying. Once the
            // job exists, the server owns reconnection (visible), not this loop.
            for (int attempt = 1; !finished; attempt++) {
                try {
                    JSONObject resp = httpJson("POST", base + "/download", body);
                    String id = resp.optString("id", "");
                    if (id.isEmpty()) { fail("content service did not start the job"); return; }
                    jobId = id;
                    main.postDelayed(pollTask, POLL_MS);
                    return;
                } catch (Exception e) {
                    if (attempt >= START_TRIES) { fail("couldn't reach the content service"); return; }
                    try { Thread.sleep(START_RETRY_MS); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        });
    }

    private void pollOnce() {
        if (finished) return;
        try {
            JSONObject j = httpJson("GET", base + "/jobs/" + jobId, null);
            pollErrors = 0;
            String phase = j.optString("phase", "");
            int percent = j.optInt("percent", -1);
            long speed = j.optLong("speed", 0L);
            String detail = j.isNull("detail") ? null : j.optString("detail", null);
            String error = j.isNull("error") ? null : j.optString("error", null);
            int retryAttempt = j.optInt("retryAttempt", 0);   // ADFA-4893: reconnect state (0 when not retrying)
            int retryTotal = j.optInt("retryTotal", 0);

            if (detail != null && !detail.isEmpty()) deliver(() -> listener.onLog(detail));

            // Terminal phases first.
            switch (phase) {
                case "done":     done(); return;
                case "error":    fail(error != null ? error : "download failed"); return;
                case "canceled": fail("canceled"); return;
                case "paused": {
                    // ADFA-4893: not terminal — surface it and keep polling so resume/progress is seen.
                    final int pp = percent;
                    deliver(() -> listener.onPaused(pp));
                    break;
                }
                default: {
                    // ADFA-4893: active phase — interpret by percent + retry, NOT by the phase name, so
                    // kiwix ("downloading"/"indexing") and books ("processing" with an item %) both work.
                    if (retryAttempt > 0) {
                        final int a = retryAttempt, t = retryTotal;
                        deliver(() -> listener.onReconnecting(a, t));
                    } else if (percent >= 0) {
                        final int p = percent; final String rate = formatRate(speed);
                        deliver(() -> listener.onProgress(p, rate));
                    } else if ("indexing".equals(phase) || "processing".equals(phase)) {
                        if (!indexing) { indexing = true; deliver(() -> listener.onIndexing()); }
                    }
                    // else queued / no-percent-yet: keep polling.
                    break;
                }
            }
            main.postDelayed(pollTask, POLL_MS);
        } catch (Exception e) {
            if (++pollErrors > MAX_POLL_ERRORS) { fail("lost contact with the content service"); return; }
            main.postDelayed(pollTask, POLL_MS);
        }
    }

    /** Best-effort cancel of the in-flight job. */
    public void cancel() {
        finished = true;   // ADFA-4893: also stops the START retry loop if a cancel lands mid-kickoff
        final String id = jobId;
        if (id != null && !id.isEmpty()) {
            AppExecutors.get().io().execute(() -> {
                try { httpJson("POST", base + "/jobs/" + id + "/cancel", null); } catch (Exception ignore) { /* best effort */ }
            });
        }
        teardown();
    }

    /** ADFA-4893: best-effort pause of the in-flight job. Keeps polling so 'paused' is observed and resume works. */
    public void pause() {
        final String id = jobId;
        if (id != null && !id.isEmpty()) {
            AppExecutors.get().io().execute(() -> {
                try { httpJson("POST", base + "/jobs/" + id + "/pause", null); } catch (Exception ignore) { /* best effort */ }
            });
        }
    }

    /** ADFA-4893: best-effort resume of a paused job; polling continues, so progress flows again. */
    public void resume() {
        final String id = jobId;
        if (id != null && !id.isEmpty()) {
            AppExecutors.get().io().execute(() -> {
                try { httpJson("POST", base + "/jobs/" + id + "/resume", null); } catch (Exception ignore) { /* best effort */ }
            });
        }
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
