/*
 * ============================================================================
 * Name        : MapsRegionClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4879. App-side client for in-app FQR (Full-Quality Region) maps, talking to
 *               the durable REST job engine on the box (nginx localhost:8085 -> Node :4000, /api).
 *               Mirrors RestContentClient (start a job, then POLL its structured status ~1s), but
 *               for the maps contract added in static/dashboard/routes.ts:
 *                 POST /api/maps/estimate {box}              -> { transfer, archive, free, free_after }
 *                 POST /api/maps/download {items:[{name,box}]} -> { id, ... }
 *                 GET  /api/maps/jobs/:id                    -> { phase, percent, detail, error }
 *                 POST /api/maps/jobs/:id/cancel             -> { ok:true }
 *               phase is one of: queued|downloading|indexing|processing|done|error|canceled.
 *               Only reachable on-device (localhost); the box denies /api to remote clients.
 * ============================================================================
 */
package org.iiab.controller.redesign;

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

public final class MapsRegionClient {

    /** Size estimate (bytes) for the consent step. */
    public interface EstimateListener {
        void onEstimate(long transfer, long archive, long free, long freeAfter);
        void onError(String message);
    }

    /** Region download progress (single durable job). */
    public interface DownloadListener {
        void onProgress(int percent);   // percent may be -1 (indeterminate)
        void onDone();
        void onError(String message);
    }

    private static final String BASE = BoxEndpoints.BASE + "/api/maps";
    private static final long POLL_MS = 1000L;
    private static final int MAX_POLL_ERRORS = 10;

    private final Handler main = new Handler(Looper.getMainLooper());
    private DownloadListener dl;
    private volatile String jobId;
    private volatile boolean finished = false;
    private int pollErrors = 0;

    private final Runnable pollTask = () -> AppExecutors.get().io().execute(this::pollOnce);

    /** Ask the server for the region's download/disk size + free space (drives the consent sheet). */
    public void estimate(@NonNull String box, @NonNull EstimateListener l) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject resp = httpJson("POST", BASE + "/estimate", new JSONObject().put("box", box));
                final long t = resp.optLong("transfer", 0), a = resp.optLong("archive", 0),
                        f = resp.optLong("free", 0), fa = resp.optLong("free_after", 0);
                main.post(() -> l.onEstimate(t, a, f, fa));
            } catch (Exception e) {
                final String m = friendlyError(e.getMessage());
                main.post(() -> l.onError(m));
            }
        });
    }

    /** Start the region download and poll it to completion. */
    public void download(@NonNull String name, @NonNull String box, @NonNull DownloadListener l) {
        this.dl = l;
        this.finished = false;
        this.pollErrors = 0;
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject item = new JSONObject().put("name", name).put("box", box);
                JSONObject body = new JSONObject().put("items", new JSONArray().put(item));
                JSONObject resp = httpJson("POST", BASE + "/download", body);
                String id = resp.optString("id", "");
                if (id.isEmpty()) { fail("the maps service did not start the download"); return; }
                jobId = id;
                main.postDelayed(pollTask, POLL_MS);
            } catch (Exception e) {
                fail("couldn't reach the maps service");
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
            String error = j.isNull("error") ? null : j.optString("error", null);
            switch (phase) {
                case "done":
                    done();
                    return;
                case "error":
                    fail(error != null ? error : "download failed");
                    return;
                case "canceled":
                    fail("canceled");
                    return;
                default: {   // queued / downloading / processing: report progress if known
                    final int p = percent;
                    deliver(() -> dl.onProgress(p));
                }
            }
            main.postDelayed(pollTask, POLL_MS);
        } catch (Exception e) {
            if (++pollErrors > MAX_POLL_ERRORS) { fail("lost contact with the maps service"); return; }
            main.postDelayed(pollTask, POLL_MS);
        }
    }

    /** Best-effort cancel of the in-flight download. */
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
        final DownloadListener l = dl;
        teardown();
        if (l != null) main.post(l::onDone);
    }

    private void fail(String message) {
        if (finished) return;
        finished = true;
        final DownloadListener l = dl;
        teardown();
        if (l != null) main.post(() -> l.onError(message));
    }

    private void teardown() { main.removeCallbacks(pollTask); }

    private void deliver(Runnable r) { if (dl != null) main.post(r); }

    /** Map a raw HTTP error to something a first-run user can act on (overlap is the common one). */
    private static String friendlyError(String raw) {
        if (raw == null) return "size estimate failed";
        if (raw.contains("409") || raw.toLowerCase().contains("overlap")) return "This region overlaps one you already have.";
        return "size estimate failed";
    }

    private static JSONObject httpJson(String method, String urlStr, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(4000);
            // The estimate runs a network dry-run on the server; give it room.
            c.setReadTimeout(urlStr.endsWith("/estimate") ? 65000 : 5000);
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
