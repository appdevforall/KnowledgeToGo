/*
 * ============================================================================
 * Name        : MapsRegionClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4879. App-side client for in-app FQR (Full-Quality Region) maps, talking to
 *               the durable REST job engine on the box via BoxEndpoints.API (nginx :8085 /k2go-api
 *               -> Node :4000 /api; the paths below are the Node routes that base resolves to).
 *               Mirrors RestContentClient (start a job, then POLL its structured status ~1s), but
 *               for the maps contract added in static/dashboard/routes.ts:
 *                 POST /api/maps/estimate {box}              -> { transfer, archive, free, free_after }
 *                 POST /api/maps/download {items:[{name,box}]} -> { id, ... }
 *                 GET  /api/maps/jobs/:id                    -> { phase, percent, detail, error }
 *                 POST /api/maps/jobs/:id/cancel             -> { ok:true }
 *                 POST /api/maps/jobs/:id/{pause,resume}     -> { ok:true } | 409  (ADFA-4894)
 *               phase is one of: queued|downloading|indexing|processing|paused|done|error|canceled.
 *               Only reachable on-device (localhost); the box denies /api to remote clients.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.appdevforall.k2go.config.BoxEndpoints;
import org.appdevforall.k2go.util.AppExecutors;
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
        void onProgress(int percent, long speedBytesPerSec);   // percent may be -1; speed 0 if unknown
        void onDone();
        void onError(String message);
        /** ADFA-4894: the job is paused (partial kept on the box); resume() continues it. Default
         *  no-op so existing callers keep compiling; the overlay overrides it to show the control. */
        default void onPaused(int percent) {}
    }

    private static final String BASE = BoxEndpoints.API + "/maps";
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
                case "paused": {   // ADFA-4894: stopped-but-resumable; report it and keep polling
                    final int pp = percent;
                    deliver(() -> dl.onPaused(pp));
                    break;
                }
                default: {   // queued / downloading / processing: report progress if known
                    final int p = percent;
                    final long sp = j.optLong("speed", 0L);
                    deliver(() -> dl.onProgress(p, sp));
                }
            }
            main.postDelayed(pollTask, POLL_MS);
        } catch (Exception e) {
            if (++pollErrors > MAX_POLL_ERRORS) { fail("lost contact with the maps service"); return; }
            main.postDelayed(pollTask, POLL_MS);
        }
    }

    /** The downloaded regions catalog, read from the box's public /maps/extracts.json. */
    public interface RegionsListener {
        void onRegions(JSONObject regions);   // { "<name>": { ui_bounds:[...], ... }, ... }
        void onError(String message);
    }

    public void listRegions(@NonNull RegionsListener l) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject j = httpJson("GET", BoxEndpoints.BASE + "/maps/extracts.json", null);
                JSONObject regions = j.optJSONObject("regions");
                final JSONObject out = regions != null ? regions : new JSONObject();
                main.post(() -> l.onRegions(out));
            } catch (Exception e) {
                main.post(() -> l.onError("couldn't read the downloaded regions"));
            }
        });
    }

    /** Delete a downloaded region (tile-extract.py delete + update-json, server-side). */
    public interface DeleteListener {
        void onOk();
        void onError(String message);
    }

    public void deleteRegion(@NonNull String name, @NonNull DeleteListener l) {
        AppExecutors.get().io().execute(() -> {
            try {
                httpJson("POST", BASE + "/delete", new JSONObject().put("name", name));
                main.post(l::onOk);
            } catch (Exception e) {
                main.post(() -> l.onError("delete failed"));
            }
        });
    }

    /** Stop polling locally and release the listener WITHOUT canceling the server job. Used when the
     *  UI goes away but the durable download should keep running on the box. */
    public void stopPolling() {
        finished = true;
        teardown();
        dl = null;
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

    /** ADFA-4894: pause the in-flight download (keeps the partial). Polling keeps running, so the
     *  next poll reports 'paused' via onPaused(); resume() picks it back up. */
    public void pause() {
        final String id = jobId;
        if (id == null || id.isEmpty()) return;
        AppExecutors.get().io().execute(() -> {
            try { httpJson("POST", BASE + "/jobs/" + id + "/pause", null); } catch (Exception ignore) { /* best effort */ }
        });
    }

    /** ADFA-4894: resume a paused download; the server continues it via --continue, and the poll
     *  reports progress again via onProgress(). */
    public void resume() {
        final String id = jobId;
        if (id == null || id.isEmpty()) return;
        AppExecutors.get().io().execute(() -> {
            try { httpJson("POST", BASE + "/jobs/" + id + "/resume", null); } catch (Exception ignore) { /* best effort */ }
        });
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
