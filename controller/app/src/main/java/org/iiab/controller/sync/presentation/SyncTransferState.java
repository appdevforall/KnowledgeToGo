/*
 * ============================================================================
 * Name        : SyncTransferState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable snapshot of the Share rsync TRANSFER (client/pull)
 *               progress, published through SyncProgressRepository so the UI can
 *               observe it and re-bind after a configuration-change recreation
 *               (e.g. theme toggle). The transfer keeps running in an
 *               Activity-scoped owner; this is just what the fragment renders.
 *               Terminal states carry a monotonically increasing seq so the UI
 *               fires the success/error dialog exactly once. ADFA-4492 (3b-2).
 * ============================================================================
 */
package org.iiab.controller.sync.presentation;

public final class SyncTransferState {

    // CONNECTING/CALCULATING/CONFIRM are the pre-transfer probing phases (ADFA-4492 step 4):
    // they now flow through the repository too, so the sondeo survives a recreation.
    public enum Phase { IDLE, CONNECTING, CALCULATING, CONFIRM, TRANSFERRING, SUCCESS, FAILED, ABORTED }

    public final Phase phase;
    public final int percent;
    public final String speed;
    public final String eta;
    public final String file;
    public final String message;
    public final String title;
    public final long seq;

    private SyncTransferState(Phase phase, int percent, String speed, String eta,
                              String file, String message, String title, long seq) {
        this.phase = phase;
        this.percent = percent;
        this.speed = speed != null ? speed : "";
        this.eta = eta != null ? eta : "";
        this.file = file != null ? file : "";
        this.message = message != null ? message : "";
        this.title = title != null ? title : "";
        this.seq = seq;
    }

    /** True while a transfer is actively running (drives the survive-recreation guard). */
    public boolean isActive() {
        return phase == Phase.TRANSFERRING || phase == Phase.CONNECTING
                || phase == Phase.CALCULATING || phase == Phase.CONFIRM;
    }

    SyncTransferState withSeq(long seq) {
        return new SyncTransferState(phase, percent, speed, eta, file, message, title, seq);
    }

    public static SyncTransferState idle() {
        return new SyncTransferState(Phase.IDLE, 0, "", "", "", "", "", 0L);
    }

    public static SyncTransferState transferring(int percent, String speed, String eta, String file) {
        return new SyncTransferState(Phase.TRANSFERRING, percent, speed, eta, file, "", "", 0L);
    }

    public static SyncTransferState success(String message) {
        return new SyncTransferState(Phase.SUCCESS, 0, "", "", "", message, "", 0L);
    }

    public static SyncTransferState failed(String message) {
        return new SyncTransferState(Phase.FAILED, 0, "", "", "", message, "", 0L);
    }

    public static SyncTransferState connecting() {
        return new SyncTransferState(Phase.CONNECTING, 0, "", "", "", "", "", 0L);
    }

    public static SyncTransferState calculating() {
        return new SyncTransferState(Phase.CALCULATING, 0, "", "", "", "", "", 0L);
    }

    /** Dry-run finished; UI must surface the confirm dialog (title + message) once. */
    public static SyncTransferState confirm(String title, String message) {
        return new SyncTransferState(Phase.CONFIRM, 0, "", "", "", message, title, 0L);
    }

    /** Probe/dry-run could not proceed; UI shows an info dialog (title + message) once, then resets. */
    public static SyncTransferState aborted(String title, String message) {
        return new SyncTransferState(Phase.ABORTED, 0, "", "", "", message, title, 0L);
    }
}
