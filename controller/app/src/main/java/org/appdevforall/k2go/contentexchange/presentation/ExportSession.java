/*
 * ============================================================================
 * Name        : ExportSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. The one export-run state the progress UI observes and the foreground
 *               service mutates. Encapsulated (no public mutable fields, not a static grab-bag):
 *               the service owns the single instance and the fragment reads it through getters,
 *               mirroring the Clone / download session pattern. Holds the pure ExportJob plus the
 *               request, labels, destination and the derived speed/ETA.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.os.Handler;
import android.os.Looper;

import org.appdevforall.k2go.contentexchange.domain.ExportJob;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ExportSession {

    /** Pinged on the main thread whenever the run state changes. */
    interface Listener {
        void onUpdate();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExportJob job = new ExportJob();

    private Listener listener;

    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private boolean planned = false;
    private String errorMessage;
    private String destinationLabel = "";
    /** The persisted SAF tree the run writes to; kept here so a retry (possibly a new service
     *  process) reaches the same destination without the Intent carrying it again. */
    private String treeUri;

    private ItemRef[] refs = new ItemRef[0];
    private String[] labels = new String[0];
    /** The bundle file name each item was stored under, by request index. Survives a retry so the
     *  manifest can list items completed in an earlier run (whose files are already on the drive). */
    private String[] payloadNames = new String[0];
    private List<String> skipped = Collections.emptyList();

    private long speed = 0L;         // bytes/sec, smoothed
    private long etaSeconds = -1L;   // whole-run seconds remaining, -1 unknown

    void setListener(Listener l) {
        this.listener = l;
    }

    // ---- service-side mutation -----------------------------------------------------------------

    /** Start a run: the request and destination are fixed now; sizes/labels arrive with the plan. */
    void beginPlanning(ItemRef[] request, String[] initialLabels, String treeUri) {
        this.refs = request != null ? request : new ItemRef[0];
        this.labels = initialLabels != null ? initialLabels : new String[this.refs.length];
        this.payloadNames = new String[this.refs.length];
        this.treeUri = treeUri;
        this.destinationLabel = "";
        this.skipped = Collections.emptyList();
        this.errorMessage = null;
        this.planned = false;
        this.cancelled = false;
        this.running = true;
        this.speed = 0L;
        this.etaSeconds = -1L;
        publish();
    }

    void setDestination(String label) {
        this.destinationLabel = label != null ? label : "";
        publish();
    }

    /** Apply the plan: begin the job over the sized items, upgrade labels to titles. */
    void applyPlan(String[] titleLabels, long[] plannedBytes, List<String> skippedReasons) {
        if (titleLabels != null && titleLabels.length == refs.length) {
            this.labels = titleLabels;
        }
        this.skipped = skippedReasons != null ? new ArrayList<>(skippedReasons) : Collections.emptyList();
        job.begin(plannedBytes);
        this.planned = true;
        publish();
    }

    void fail(String message) {
        this.errorMessage = message;
        this.running = false;
        publish();
    }

    void setRate(long bytesPerSecond, long etaSeconds) {
        this.speed = Math.max(0L, bytesPerSecond);
        this.etaSeconds = etaSeconds;
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
        planned = false;
        errorMessage = null;
        refs = new ItemRef[0];
        labels = new String[0];
        payloadNames = new String[0];
        treeUri = null;
        skipped = Collections.emptyList();
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

    void setPayloadName(int i, String name) {
        if (i >= 0 && i < payloadNames.length) {
            payloadNames[i] = name;
        }
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

    /** A run exists, is active, but the plan has not landed yet. */
    boolean isPreparing() {
        return running && !planned;
    }

    boolean isSettled() {
        return planned && !running && job.isSettled();
    }

    boolean allDone() {
        return job.allDone();
    }

    boolean hasFailed() {
        return job.hasFailed();
    }

    /** A settled run with work still left (failed items, or items re-queued by a failed re-plan). */
    boolean canRetry() {
        return planned && !running && itemCount() > 0 && !job.allDone();
    }

    String errorMessage() {
        return errorMessage;
    }

    String destinationLabel() {
        return destinationLabel;
    }

    List<String> skipped() {
        return skipped;
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
