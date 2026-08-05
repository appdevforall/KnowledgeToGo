/*
 * ============================================================================
 * Name        : KolibriRestClient.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. App-side client of the Kolibri seeding endpoints on
 *               the in-server REST core (nginx localhost:8085 -> Node :4000,
 *               /k2go-api/kolibri). Same shape as RestContentClient: POST to
 *               start, then poll ~1s. The job is durable in the dashboard
 *               process, so a dropped client can re-attach by polling the id.
 *
 *               Contract (static/dashboard/routes.ts, ADFA-4949):
 *                 GET  /k2go-api/kolibri/ready          -> { ready, blockers[] }
 *                 POST /k2go-api/kolibri/download        -> { id, ... }
 *                        body { items:[{channelId,nodeIds,allThumbnails}] }
 *                 GET  /k2go-api/kolibri/jobs/:id        -> { phase, percent,
 *                                                            speed, detail, error }
 *                 POST /k2go-api/kolibri/jobs/:id/cancel -> { ok:true }
 *               phase: queued|downloading|indexing|processing|done|error|canceled.
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.kolibri.domain.ChannelSelection;
import org.iiab.controller.kolibri.domain.SeedPlan;
import org.iiab.controller.util.AppExecutors;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class KolibriRestClient {

    /** Mirrors {@code RestContentClient.Listener} so the foreground service that
     *  drives it can be a structural copy of the ZIM one.
     *  {@code speed} is a display token WITHOUT the per-second suffix. */
    public interface Listener {
        void onProgress(int percent, String speed);   // downloading phase
        void onIndexing();                            // indexing/processing (indeterminate)
        void onLog(String line);                      // free-form detail line
        void onDone();                                // success (terminal)
        void onError(String message);                 // failure (terminal)
    }

    /** Result of the readiness gate. */
    public interface ReadyListener {
        void onReady();
        /** @param reason the first blocker reported by the box, already human-readable */
        void onNotReady(String reason);
    }

    private static final String BASE = BoxEndpoints.API + "/kolibri";
    private static final long POLL_MS = 1000L;
    private static final int MAX_POLL_ERRORS = 10;    // ~10s of transient blips

    /** The gate can be slow on a cold box: it logs into Kolibri to answer. */
    private static final int READY_TIMEOUT_MS = 15000;
    private static final int CALL_TIMEOUT_MS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private volatile String jobId;
    private volatile boolean finished = false;
    private volatile boolean indexing = false;
    private int pollErrors = 0;

    private final Runnable pollTask = () -> AppExecutors.get().io().execute(this::pollOnce);

    /**
     * Asks the box whether seeding can start at all.
     *
     * <p>Worth calling before {@link #seed}: the gate reports in one shot whether
     * Kolibri is up, whether the stored credentials still authenticate, whether the
     * user can manage content and whether the content origin resolves. Without it
     * the first failure surfaces as a job error several seconds in, with the cause
     * buried.
     *
     * <p>Always answers HTTP 200 — it reports state rather than performing an
     * operation — so a non-200 here means the REST core itself is unreachable.
     */
    public void checkReady(@NonNull ReadyListener l) {
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject j = httpJson("GET", BASE + "/ready", null, READY_TIMEOUT_MS);
                if (j.optBoolean("ready", false)) {
                    main.post(l::onReady);
                    return;
                }
                final String reason = firstBlocker(j);
                main.post(() -> l.onNotReady(reason));
            } catch (Exception e) {
                main.post(() -> l.onNotReady("couldn't reach the content service"));
            }
        });
    }

    /** First entry of the {@code blockers} array, or a generic fallback. */
    private static String firstBlocker(JSONObject readyResponse) {
        JSONArray blockers = readyResponse.optJSONArray("blockers");
        if (blockers != null && blockers.length() > 0) {
            String first = blockers.optString(0, "");
            if (!first.isEmpty()) {
                return first;
            }
        }
        return "Kolibri is not ready yet";
    }

    /**
     * Starts seeding everything in {@code plan} as one job and polls to completion.
     *
     * <p>The plan is taken rather than a bare list because it guarantees one entry
     * per channel: the dashboard runner walks them sequentially, and two imports of
     * the same channel would only contend on the same SQLite file.
     *
     * <p>The channel <em>name</em> is deliberately not sent. The box resolves it
     * from its own cached proxy of Studio, so the app does not have to carry it and
     * cannot send a stale one.
     */
    public void seed(@NonNull SeedPlan plan, @NonNull Listener l) {
        this.listener = l;
        resetRunState();
        if (plan.isEmpty()) {
            // Nothing queued is a success, not an error: the caller drained an
            // empty wishlist. Routed through done() so the double-callback guard is
            // armed rather than left open.
            done();
            return;
        }
        AppExecutors.get().io().execute(() -> {
            try {
                JSONObject body = new JSONObject().put("items", itemsOf(plan));
                JSONObject resp = httpJson("POST", BASE + "/download", body, CALL_TIMEOUT_MS);
                String id = resp.optString("id", "");
                if (id.isEmpty()) {
                    fail("content service did not start the job");
                    return;
                }
                jobId = id;
                main.postDelayed(pollTask, POLL_MS);
            } catch (Exception e) {
                fail("couldn't reach the content service");
            }
        });
    }

    /**
     * Re-attaches to a job started earlier, e.g. after the service was restarted.
     *
     * <p>Resets the run state first, and that ordering matters: {@code finished}
     * short-circuits both {@link #pollOnce()} and {@link #fail(String)}, so on an
     * instance that already completed a seed this method would otherwise go
     * completely silent — no polling, no error, no callback.
     */
    public void attach(@NonNull String existingJobId, @NonNull Listener l) {
        this.listener = l;
        resetRunState();

        String id = existingJobId.trim();
        if (id.isEmpty()) {
            // Fail with the real cause. Left to the poller, an empty id would 404
            // ten times and surface as "lost contact with the content service",
            // pointing at the network instead of at the missing id.
            fail("no job to re-attach to");
            return;
        }
        this.jobId = id;
        main.postDelayed(pollTask, POLL_MS);
    }

    /** Clears the per-run flags so the instance can start or re-attach again. */
    private void resetRunState() {
        finished = false;
        indexing = false;
        pollErrors = 0;
    }

    /** The id of the in-flight job, or null. Persist it to survive a restart. */
    public String jobId() {
        return jobId;
    }

    /** Translates the domain selections into the runner's item shape. */
    private static JSONArray itemsOf(SeedPlan plan) throws Exception {
        JSONArray items = new JSONArray();
        for (ChannelSelection s : plan.selections()) {
            JSONObject item = new JSONObject().put("channelId", s.channelId());
            List<String> nodes = s.nodeIds();
            if (!nodes.isEmpty()) {
                // Only send node_ids when narrowing. An empty array would mean
                // "zero nodes" to Kolibri, which finishes clean and downloads
                // nothing — the trap ChannelSelection exists to prevent.
                JSONArray arr = new JSONArray();
                for (String n : nodes) {
                    arr.put(n);
                }
                item.put("nodeIds", arr);
            }
            if (s.wantsAllThumbnails()) {
                item.put("allThumbnails", true);
            }
            items.put(item);
        }
        return items;
    }

    private void pollOnce() {
        if (finished) return;
        try {
            JSONObject j = httpJson("GET", BASE + "/jobs/" + jobId, null, CALL_TIMEOUT_MS);
            pollErrors = 0;
            String phase = j.optString("phase", "");
            int percent = j.optInt("percent", -1);
            long speed = j.optLong("speed", 0L);
            String detail = j.isNull("detail") ? null : j.optString("detail", null);
            String error = j.isNull("error") ? null : j.optString("error", null);

            if (detail != null && !detail.isEmpty()) {
                final String line = detail;
                deliver(() -> listener.onLog(line));
            }

            switch (phase) {
                case "downloading":
                    // Cleared on the way in, not only set on the way out: a job that
                    // seeds several channels re-enters processing once per channel,
                    // and a latching flag would announce the phase for the first
                    // channel only. The ZIM client can latch because it handles one
                    // item; this one cannot.
                    indexing = false;
                    if (percent >= 0) {
                        final int p = percent;
                        final String rate = formatRate(speed);
                        deliver(() -> listener.onProgress(p, rate));
                    }
                    break;
                case "indexing":
                case "processing":
                    if (!indexing) {
                        indexing = true;
                        deliver(() -> listener.onIndexing());
                    }
                    break;
                case "done":
                    done();
                    return;
                case "error":
                    fail(error != null ? error : "seeding failed");
                    return;
                case "canceled":
                    fail("canceled");
                    return;
                default:
                    break; // queued / unknown: keep polling
            }
            main.postDelayed(pollTask, POLL_MS);
        } catch (Exception e) {
            if (++pollErrors > MAX_POLL_ERRORS) {
                fail("lost contact with the content service");
                return;
            }
            main.postDelayed(pollTask, POLL_MS);
        }
    }

    /** Best-effort cancel of the in-flight job. */
    public void cancel() {
        final String id = jobId;
        if (id != null && !id.isEmpty()) {
            AppExecutors.get().io().execute(() -> {
                try {
                    httpJson("POST", BASE + "/jobs/" + id + "/cancel", null, CALL_TIMEOUT_MS);
                } catch (Exception ignore) {
                    // best effort
                }
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

    /** bytes/sec -> a short display token ("3.4 MB"); caller appends the localized "/s". */
    private static String formatRate(long bps) {
        if (bps <= 0) return "0 B";
        final String[] units = {"B", "KB", "MB", "GB"};
        double v = bps;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return i == 0
                ? String.format(java.util.Locale.US, "%.0f %s", v, units[i])
                : String.format(java.util.Locale.US, "%.1f %s", v, units[i]);
    }

    private static JSONObject httpJson(String method, String urlStr, JSONObject body, int timeoutMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            c.setUseCaches(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload);
                }
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
