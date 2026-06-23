/*
 * ============================================================================
 * Name        : UpdateUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable UI state for the in-app OTA update dialog.
 * ============================================================================
 */
package org.iiab.controller.update.presentation;

import org.iiab.controller.update.domain.DownloadProgress;

/** UI state for the update dialog: download -> verify -> ready/install, or error. */
public final class UpdateUiState {

    public enum Status { IDLE, DOWNLOADING, VERIFYING, READY, INSTALLING, ERROR }

    public final Status status;
    public final int percent;          // 0..100, or -1 when indeterminate
    public final boolean indeterminate;
    public final String message;

    private UpdateUiState(Status status, int percent, boolean indeterminate, String message) {
        this.status = status;
        this.percent = percent;
        this.indeterminate = indeterminate;
        this.message = message;
    }

    public static UpdateUiState idle() { return new UpdateUiState(Status.IDLE, 0, false, null); }
    public static UpdateUiState downloading(int percent, boolean indeterminate) {
        return new UpdateUiState(Status.DOWNLOADING, percent, indeterminate, null);
    }
    public static UpdateUiState verifying() { return new UpdateUiState(Status.VERIFYING, 100, true, null); }
    public static UpdateUiState ready() { return new UpdateUiState(Status.READY, 100, false, null); }
    public static UpdateUiState installing() { return new UpdateUiState(Status.INSTALLING, 100, true, null); }
    public static UpdateUiState error(String message) { return new UpdateUiState(Status.ERROR, 0, false, message); }

    /** Pure mapping from a download snapshot to UI state. */
    public static UpdateUiState fromDownload(DownloadProgress p) {
        if (p == null) return idle();
        switch (p.status) {
            case RUNNING:
            case PENDING:
            case PAUSED:
                return downloading(p.percent(), p.isIndeterminate());
            case SUCCESSFUL:
                return verifying();
            case FAILED:
                return error(null);
            default:
                return idle();
        }
    }
}
