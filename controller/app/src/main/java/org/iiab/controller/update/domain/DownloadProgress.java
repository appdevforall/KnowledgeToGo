/*
 * ============================================================================
 * Name        : DownloadProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Pure value object for OTA download progress (PR B presentation core).
 * ============================================================================
 */
package org.iiab.controller.update.domain;

/**
 * Immutable snapshot of an in-flight OTA download. Pure domain (no Android): the
 * Data layer maps a DownloadManager query into this; the ViewModel turns it into
 * UI state. {@code totalBytes <= 0} means the size is unknown (indeterminate bar).
 */
public final class DownloadProgress {

    public enum Status { NONE, PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED }

    public final Status status;
    public final long downloadedBytes;
    public final long totalBytes;

    public DownloadProgress(Status status, long downloadedBytes, long totalBytes) {
        this.status = status == null ? Status.NONE : status;
        this.downloadedBytes = Math.max(0, downloadedBytes);
        this.totalBytes = totalBytes;
    }

    public static DownloadProgress none() {
        return new DownloadProgress(Status.NONE, 0, -1);
    }

    /** True when the total size is unknown -> the UI should show an indeterminate bar. */
    public boolean isIndeterminate() {
        return totalBytes <= 0;
    }

    /** 0..100, or -1 when indeterminate. */
    public int percent() {
        if (totalBytes <= 0) return -1;
        long p = downloadedBytes * 100L / totalBytes;
        if (p < 0) return 0;
        if (p > 100) return 100;
        return (int) p;
    }

    public boolean isTerminal() {
        return status == Status.SUCCESSFUL || status == Status.FAILED;
    }
}
