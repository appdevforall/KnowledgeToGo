/*
 * ============================================================================
 * Name        : ContentDownloadSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4893. The one download-session engine shared by the content streams (ZIM, Books,
 *               ...). It owns the sequential queue, the shared RestContentClient wiring, and all the
 *               state (progress, paused, reconnect, heartbeat) that the UI observes; ZimDownloadService
 *               and BooksDownloadService used to carry an identical copy of this. Each service holds ONE
 *               instance (so their static session state never collides) and delegates to it, attaching
 *               itself as {@link Host} for the per-type body/percent and the foreground notification.
 *               The device only POSTs + polls; the server owns download reconnection (visible).
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

    /** Per-type hooks the owning Service provides (body, percent, notification, stop). */
    public interface Host {
        JSONObject buildBody(int i);   // start body for item i (kiwix {ids:[..]}, books {items:[..]})
        int computeOverallPercent();   // per-type overall percent (byte-weighted vs item-count)
        String label(int i);           // display label for item i (notification)
        void notify(String label);     // update the foreground notification
        void stop();                   // stopForeground(true) + stopSelf()
        void onPurged();               // clear the per-type arrays so the snapshot stays consistent
    }

    private final String type;                          // "kiwix" / "books" -> /api/<type>
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final long POLL_MS = 1000L;

    private Host host;
    private Listener listener;
    private RestContentClient client;

    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile int reconnectAttempt = 0;
    private volatile int reconnectTotal = 0;
    private volatile long lastProgressAt = 0L;          // ADFA-5146 heartbeat
    private int[] status = new int[0];
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
    /** Overall percent for the bars — delegated to the per-type Host (byte-weighted vs item-count). */
    public int overallPercent() { return host != null ? host.computeOverallPercent() : 0; }

    // ---- actions (the services' onStartCommand / statics delegate here) ------------------------

    /** Fresh session over {@code count} items (status all PENDING), then start pumping the queue. */
    public void begin(int count) {
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

    /** Clear all session state (the services' finishSession()). Also clears the owner's per-type arrays
     *  via {@link Host#onPurged()} so the snapshot the UI reads stays length-consistent (labels/status/
     *  bytes all empty together) — otherwise a Cancel left labels>0 with status=0 and the ZIM fragment
     *  indexed status[] off the label count → AIOOBE (ADFA-4893). */
    public void purge() {
        status = new int[0];
        index = 0; percent = 0; speed = 0;
        running = false; paused = false; reconnectAttempt = 0; reconnectTotal = 0;
        lastProgressAt = 0L;
        if (host != null) host.onPurged();
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
        if (host != null) host.notify(host.label(i));
        startItem(i);
    }

    private void startItem(final int i) {
        client = new RestContentClient(type);
        client.start(host.buildBody(i), new RestContentClient.Listener() {
            @Override public void onProgress(int pct, String rate) {
                if (paused) paused = false;
                reconnectAttempt = 0;
                percent = pct; speed = parseRate(rate);
                lastProgressAt = SystemClock.elapsedRealtime();
                if (status[i] != PROCESSING) status[i] = ACTIVE;
                publish();
                if (host != null) host.notify(host.label(i));
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
            @Override public void onDone() { status[i] = DONE; publish(); pump(); }
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
