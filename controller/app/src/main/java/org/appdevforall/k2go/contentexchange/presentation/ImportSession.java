/*
 * ============================================================================
 * Name        : ImportSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. The one import-run state the progress UI observes and the foreground service
 *               mutates. The mirror of ExportSession (encapsulated, not a static grab-bag). Holds the
 *               pure ExportJob (a generic per-item transfer state machine, reused for import) plus the
 *               selected items (address, title, manifest entry, archive name), the source drive and the
 *               derived speed/ETA. The sizes are known up front (from the manifest), so there is no
 *               separate planning phase.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.os.Handler;
import android.os.Looper;

import org.appdevforall.k2go.contentexchange.domain.ExportJob;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;

final class ImportSession {

    /** Pinged on the main thread whenever the run state changes. */
    interface Listener {
        void onUpdate();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExportJob job = new ExportJob();

    private Listener listener;

    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private boolean started = false;
    private String errorMessage;
    private String sourceLabel = "";
    /** The persisted SAF tree the run reads from; kept so a retry reaches the same drive. */
    private String treeUri;

    private ItemRef[] refs = new ItemRef[0];
    private String[] labels = new String[0];
    private String[] entryJson = new String[0];
    private String[] payloadNames = new String[0];

    private long speed = 0L;         // bytes/sec, smoothed
    private long etaSeconds = -1L;   // whole-run seconds remaining, -1 unknown

    void setListener(Listener l) {
        this.listener = l;
    }

    // ---- service-side mutation -----------------------------------------------------------------

    /** Start a run over the selected items; sizes are known now, so the job begins immediately. */
    void begin(ItemRef[] request, String[] titles, String[] entries, String[] payloads,
               long[] bytes, String treeUri) {
        this.refs = request != null ? request : new ItemRef[0];
        this.labels = titles != null ? titles : new String[this.refs.length];
        this.entryJson = entries != null ? entries : new String[this.refs.length];
        this.payloadNames = payloads != null ? payloads : new String[this.refs.length];
        this.treeUri = treeUri;
        this.sourceLabel = "";
        this.errorMessage = null;
        this.started = true;
        this.cancelled = false;
        this.running = true;
        this.speed = 0L;
        this.etaSeconds = -1L;
        job.begin(bytes != null ? bytes : new long[this.refs.length]);
        publish();
    }

    void setSource(String label) {
        this.sourceLabel = label != null ? label : "";
        publish();
    }

    void fail(String message) {
        this.errorMessage = message;
        this.running = false;
        publish();
    }

    void setRate(long bytesPerSecond, long eta) {
        this.speed = Math.max(0L, bytesPerSecond);
        this.etaSeconds = eta;
    }

    void setRunning(boolean value) {
        this.running = value;
    }

    void markCancelled() {
        this.cancelled = true;
        this.running = false;
    }

    int requeueFailed() {
        return job.requeueFailed();
    }

    void clear() {
        running = false;
        cancelled = false;
        started = false;
        errorMessage = null;
        refs = new ItemRef[0];
        labels = new String[0];
        entryJson = new String[0];
        payloadNames = new String[0];
        treeUri = null;
        job.begin(new long[0]);
        speed = 0L;
        etaSeconds = -1L;
        publish();
    }

    ExportJob job() {
        return job;
    }

    ItemRef refAt(int i) {
        return i >= 0 && i < refs.length ? refs[i] : null;
    }

    String entryJson(int i) {
        return i >= 0 && i < entryJson.length ? entryJson[i] : null;
    }

    String payloadName(int i) {
        return i >= 0 && i < payloadNames.length ? payloadNames[i] : null;
    }

    ItemRef[] refs() {
        return refs;
    }

    String treeUri() {
        return treeUri;
    }

    boolean isCancelled() {
        return cancelled;
    }

    void publish() {
        main.post(() -> {
            if (listener != null) {
                listener.onUpdate();
            }
        });
    }

    // ---- UI-side reads -------------------------------------------------------------------------

    boolean isRunning() {
        return running;
    }

    boolean hasSession() {
        return refs.length > 0;
    }

    boolean isSettled() {
        return started && !running && job.isSettled();
    }

    boolean allDone() {
        return job.allDone();
    }

    boolean hasFailed() {
        return job.hasFailed();
    }

    /** A settled run with work still left (failed items) for a retry. */
    boolean canRetry() {
        return started && !running && itemCount() > 0 && !job.allDone();
    }

    String errorMessage() {
        return errorMessage;
    }

    String sourceLabel() {
        return sourceLabel;
    }

    int itemCount() {
        return job.size();
    }

    int index() {
        return job.index();
    }

    String label(int i) {
        return i >= 0 && i < labels.length ? labels[i] : "";
    }

    int status(int i) {
        return job.status(i);
    }

    int overallPercent() {
        return job.overallPercent();
    }

    int currentItemPercent() {
        return job.currentItemPercent();
    }

    long copiedTotal() {
        return job.copiedTotal();
    }

    long plannedTotal() {
        return job.plannedTotal();
    }

    long speed() {
        return speed;
    }

    long etaSeconds() {
        return etaSeconds;
    }
}
