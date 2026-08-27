/*
 * ============================================================================
 * Name        : ContentDownloadSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4893. The one download-session engine shared by the content streams (ZIM, Books,
 *               ...). It owns the sequential queue, the shared RestContentClient wiring, ALL the state
 *               the UI observes (progress, paused, reconnect, heartbeat) AND the per-item snapshot
 *               (labels / sizes / start-bodies). Holding the snapshot here — set together with status in
 *               begin(), cleared together in purge() — makes length-consistency a structural invariant:
 *               status[] and the metadata the UI indexes off it can never diverge, so a Cancel can't
 *               leave the fragment indexing one array off another (the AIOOBE we fixed). Each service
 *               holds ONE instance and delegates to it, attaching itself as {@link Host} only to build
 *               the foreground notification and stop itself. The device only POSTs + polls; the server
 *               owns download reconnection (visible).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.iiab.controller.content.RestContentClient;
import org.json.JSONObject;

public final class ContentDownloadSession {

    /** Per-item status, shared by all streams. */
    public static final int PENDING = 0, ACTIVE = 1, PROCESSING = 2, DONE = 3, FAILED = 4;

    /** UI observer: pinged whenever session state changes (same shape both services exposed). */
    public interface Listener { void onUpdate(); }

    /** Per-type hooks the owning Service provides. The session owns the queue AND the per-item snapshot,
     *  so the service is left only with the foreground notification and its own stop — there is no
     *  per-type array for it to keep length-consistent anymore (ADFA-4893). */
    public interface Host {
        void notify(String label);     // update the foreground notification for the current item
        void stop();                   // stopForeground(true) + stopSelf()
        void onItemDone(String key);   // item confirmed DONE -> drop its wishlist entry (ADFA-4897)
    }

    private final String type;                          // "kiwix" / "books" -> /api/<type>
    private final Handler main = new Handler(Looper.getMainLooper());

    private Host host;
    private Listener listener;
    private RestContentClient client;

    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile int reconnectAttempt = 0;
    private volatile int reconnectTotal = 0;
    private volatile long lastProgressAt = 0L;          // ADFA-5146 heartbeat
    private int[] status = new int[0];
    // Per-item snapshot — same length as status by construction (set together in begin(), cleared
    // together in purge()). bodies[i] is the REST start request for item i (kiwix {ids:[..]}, books
    // {items:[..]}); sizes[i] is the byte weight (0 for books -> count-based percent); keys[i] is the
    // owning wishlist key, so the service can drop the entry the moment item i is confirmed DONE
    // (ADFA-4897 — the wishlist is the durable order, kept until each item completes).
    private String[] keys = new String[0];
    private String[] labels = new String[0];
    private long[] sizes = new long[0];
    private JSONObject[] bodies = new JSONObject[0];
    private int index = 0;
    private int percent = 0;
    private long speed = 0;

    public ContentDownloadSession(String type) { this.type = type; }

    /** The Service attaches itself before dispatching an action (foreground/notification live there). */
    public void attach(Host h) { this.host = h; }
    public void setListener(Listener l) { this.listener = l; }

    // ---- observed state (the services' static getters delegate here) --------------------------
    public boolean isRunning() { return running; }
    public boolean isPaused() { return paused; }
    public int reconnectAttempt() { return reconnectAttempt; }
    public int reconnectTotal() { return reconnectTotal; }
    public int[] status() { return status; }
    public int index() { return index; }
    public int percent() { return percent; }
    public long speed() { return speed; }
    public String[] labels() { return labels; }
    public long[] sizes() { return sizes; }
    public String label(int i) { return i >= 0 && i < labels.length ? labels[i] : ""; }
    public long size(int i) { return i >= 0 && i < sizes.length ? sizes[i] : 0L; }
    public String key(int i) { return i >= 0 && i < keys.length ? keys[i] : ""; }
    public boolean hasSession() { return status.length > 0; }
    public boolean isComplete() {
        if (status.length == 0 || running) return false;
        for (int st : status) if (st == PENDING || st == ACTIVE || st == PROCESSING) return false;
        return true;
    }
    public boolean isActiveNow() {
        return hasSession() && !isComplete()
                && org.iiab.controller.env.Freshness.fresh(
                        lastProgressAt, SystemClock.elapsedRealtime(), org.iiab.controller.env.Freshness.STALE_MS);
    }
    public boolean hasFailed() {
        for (int st : status) if (st == FAILED) return true;
        return false;
    }

    /** Overall percent for the bars. Byte-weighted when the items carry sizes (ZIM); item-count when
     *  they don't (books send size 0). One formula, one owner — the services no longer compute this,
     *  so the status screen and the index bar read the exact same number by construction. */
    public int overallPercent() {
        int n = Math.min(sizes.length, status.length);
        long total = 0, done = 0;
        for (int i = 0; i < n; i++) {
            total += sizes[i];
            if (status[i] == DONE || status[i] == FAILED) done += sizes[i];
            else if (i == index && (status[i] == ACTIVE || status[i] == PROCESSING) && percent >= 0)
                done += sizes[i] * percent / 100;
        }
        if (total > 0) return (int) Math.min(100, done * 100 / total);
        // No per-item sizes (books): fall back to item count.
        int m = status.length;
        if (m == 0) return isComplete() ? 100 : 0;
        int c = 0;
        for (int st : status) if (st == DONE || st == FAILED) c++;
        return (int) Math.min(100, (long) c * 100 / m);
    }

    // ---- actions (the services' onStartCommand / statics delegate here) ------------------------

    /** Fresh session over the given items (status all PENDING), then start pumping the queue. The
     *  labels/sizes/bodies snapshot is held here so it stays the same length as status for the whole
     *  session — the service passes it in and never keeps its own copy. */
    public void begin(String[] keys, String[] labels, long[] sizes, JSONObject[] bodies) {
        int count = labels != null ? labels.length : 0;
        this.keys = keys != null && keys.length == count ? keys : new String[count];
        this.labels = labels != null ? labels : new String[0];
        this.sizes = sizes != null && sizes.length == count ? sizes : new long[count];
        this.bodies = bodies != null && bodies.length == count ? bodies : new JSONObject[count];
        status = new int[count];
        index = 0; percent = 0; speed = 0;
        paused = false; reconnectAttempt = 0; reconnectTotal = 0;
        running = true;
        lastProgressAt = SystemClock.elapsedRealtime();
        pump();
    }

    /** Resume the queue after failed items were re-queued to PENDING (ACTION_RETRY). */
    public void resumeQueue() {
        running = true;
        lastProgressAt = SystemClock.elapsedRealtime();
        pump();
    }

    /** Re-queue ALL failed items to PENDING (for a status-screen Retry). Returns true if any changed. */
    public boolean requeueFailed() {
        boolean any = false;
        for (int i = 0; i < status.length; i++) if (status[i] == FAILED) { status[i] = PENDING; any = true; }
        return any;
    }

    public void pauseActive() {
        if (client != null) client.pause();
        paused = true; publish();
    }

    public void resumeActive() {
        if (client != null) client.resume();
        paused = false; publish();
    }

    /** Cancel the in-flight job AND purge the session so it doesn't sit in limbo / block the next drain,
     *  then stop the foreground service. */
    public void cancelAndPurge() {
        if (client != null) client.cancel();
        purge();
        publish();
        if (host != null) host.stop();
    }

    /** Clear all session state AND the per-item snapshot (labels/sizes/bodies) together, so the UI can
     *  never read a length mismatch after a Cancel (ADFA-4893). */
    public void purge() {
        status = new int[0];
        keys = new String[0]; labels = new String[0]; sizes = new long[0]; bodies = new JSONObject[0];
        index = 0; percent = 0; speed = 0;
        running = false; paused = false; reconnectAttempt = 0; reconnectTotal = 0;
        lastProgressAt = 0L;
    }

    private int firstPending() {
        for (int i = 0; i < status.length; i++) if (status[i] == PENDING) return i;
        return -1;
    }

    private void pump() {
        int i = firstPending();
        if (i < 0) { running = false; publish(); if (host != null) host.stop(); return; }
        index = i; percent = 0; status[i] = ACTIVE;
        publish();
        if (host != null) host.notify(label(i));
        startItem(i);
    }

    private void startItem(final int i) {
        client = new RestContentClient(type);
        JSONObject body = i >= 0 && i < bodies.length && bodies[i] != null ? bodies[i] : new JSONObject();
        client.start(body, new RestContentClient.Listener() {
            @Override public void onProgress(int pct, String rate) {
                if (paused) paused = false;
                reconnectAttempt = 0;
                percent = pct; speed = parseRate(rate);
                lastProgressAt = SystemClock.elapsedRealtime();
                if (status[i] != PROCESSING) status[i] = ACTIVE;
                publish();
                if (host != null) host.notify(label(i));
            }
            @Override public void onIndexing() {
                status[i] = PROCESSING; lastProgressAt = SystemClock.elapsedRealtime(); publish();
            }
            @Override public void onPaused(int pct) {
                percent = pct; paused = true; lastProgressAt = SystemClock.elapsedRealtime(); publish();
            }
            @Override public void onReconnecting(int attempt, int total) {
                reconnectAttempt = attempt; reconnectTotal = total;
                lastProgressAt = SystemClock.elapsedRealtime(); publish();
            }
            @Override public void onLog(String line) { /* logcat only */ }
            @Override public void onDone() {
                status[i] = DONE; publish();
                // ADFA-4897: item confirmed done -> drop its wishlist entry, so a later process death
                // only re-drains what actually did NOT finish (no re-download of completed items).
                String k = key(i);
                if (host != null && !k.isEmpty()) host.onItemDone(k);
                pump();
            }
            @Override public void onError(String message) {
                // ADFA-4893: server owns reconnection (visible); on give-up, FAILED for a manual Retry.
                android.util.Log.w("K2Go-Provision", "[" + type + "] job [" + i + "] error: " + message);
                status[i] = FAILED; reconnectAttempt = 0; publish(); pump();
            }
        });
    }

    private void publish() { main.post(() -> { if (listener != null) listener.onUpdate(); }); }

    /** "3.4 MB" display token -> bytes/sec (books send none -> 0). */
    private static long parseRate(String s) {
        if (s == null) return 0;
        try {
            String t = s.trim();
            int sp = t.indexOf(' ');
            if (sp < 0) return 0;
            double v = Double.parseDouble(t.substring(0, sp));
            String u = t.substring(sp + 1);
            double m = "GB".equals(u) ? 1024d * 1024 * 1024 : "MB".equals(u) ? 1024d * 1024
                    : "KB".equals(u) ? 1024d : 1d;
            return Math.round(v * m);
        } catch (Exception e) { return 0; }
    }
}
