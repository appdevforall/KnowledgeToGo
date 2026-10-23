/*
 * ============================================================================
 * Name        : DeleteSession.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. The one delete-run state the progress UI observes and the foreground
 *               service mutates. Encapsulated (no public mutable fields, not a static grab-bag):
 *               the service owns the single instance and the fragment reads it through getters,
 *               mirroring the export / download session pattern. Holds the pure DeleteJob plus the
 *               request and labels. Simpler than the export session: no plan phase, no destination,
 *               no SAF grant, no speed/ETA (delete is atomic per item, not a byte copy).
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.os.Handler;
import android.os.Looper;

import org.appdevforall.k2go.contentexchange.domain.DeleteJob;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;

final class DeleteSession {

    /** Pinged on the main thread whenever the run state changes. */
    interface Listener {
        void onUpdate();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final DeleteJob job = new DeleteJob();

    private Listener listener;

    private volatile boolean running = false;
    private volatile boolean cancelled = false;
    private String errorMessage;

    private ItemRef[] refs = new ItemRef[0];
    private String[] labels = new String[0];

    void setListener(Listener l) {
        this.listener = l;
    }

    // ---- service-side mutation -----------------------------------------------------------------

    /** Start a run over the requested items: delete begins immediately (no plan phase). */
    void begin(ItemRef[] request, String[] itemLabels) {
        this.refs = request != null ? request : new ItemRef[0];
        this.labels = itemLabels != null && itemLabels.length == this.refs.length
                ? itemLabels : new String[this.refs.length];
        this.errorMessage = null;
        this.cancelled = false;
        this.running = true;
        job.begin(this.refs.length);
        publish();
    }

    void fail(String message) {
        this.errorMessage = message;
        this.running = false;
        publish();
    }

    void setRunning(boolean value) {
        this.running = value;
    }

    /** Request cancellation: flip the flag only. The worker owns teardown (clear + stop) once it
     *  sees the flag, so the job is mutated from one thread. Delete of a single item cannot be
     *  interrupted mid-call, so cancel takes effect after the item in flight finishes. */
    void markCancelled() {
        this.cancelled = true;
    }

    int requeueFailed() {
        return job.requeueFailed();
    }

    void clear() {
        running = false;
        cancelled = false;
        errorMessage = null;
        refs = new ItemRef[0];
        labels = new String[0];
        job.begin(0);
        publish();
    }

    DeleteJob job() {
        return job;
    }

    ItemRef refAt(int i) {
        return i >= 0 && i < refs.length ? refs[i] : null;
    }

    ItemRef[] refs() {
        return refs;
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
        return !running && job.isSettled();
    }

    boolean allDone() {
        return job.allDone();
    }

    boolean hasFailed() {
        return job.hasFailed();
    }

    /** A settled run with work still left (failed items to re-try). */
    boolean canRetry() {
        return !running && itemCount() > 0 && !job.allDone();
    }

    String errorMessage() {
        return errorMessage;
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

    int doneCount() {
        return job.doneCount();
    }

    int overallPercent() {
        return job.overallPercent();
    }

    long freedTotal() {
        return job.freedTotal();
    }
}
