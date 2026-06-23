/*
 * ============================================================================
 * Name        : DownloadManagerGateway.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : OtaDownloadGateway backed by Android DownloadManager.
 * ============================================================================
 */
package org.iiab.controller.update.data;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;

import org.iiab.controller.update.domain.DownloadProgress;
import org.iiab.controller.update.domain.OtaDownloadGateway;

/** Queries DownloadManager for live progress and removes the download on cancel. */
public final class DownloadManagerGateway implements OtaDownloadGateway {

    private final DownloadManager dm;

    public DownloadManagerGateway(Context context) {
        this.dm = (DownloadManager) context.getApplicationContext().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    @Override
    public DownloadProgress query(long downloadId) {
        if (dm == null || downloadId < 0) return DownloadProgress.none();
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId))) {
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long dl = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                return new DownloadProgress(map(status), dl, total);
            }
        } catch (Exception ignored) {
        }
        return DownloadProgress.none();
    }

    @Override
    public void cancel(long downloadId) {
        if (dm != null && downloadId >= 0) dm.remove(downloadId);
    }

    private static DownloadProgress.Status map(int s) {
        switch (s) {
            case DownloadManager.STATUS_RUNNING: return DownloadProgress.Status.RUNNING;
            case DownloadManager.STATUS_PENDING: return DownloadProgress.Status.PENDING;
            case DownloadManager.STATUS_PAUSED: return DownloadProgress.Status.PAUSED;
            case DownloadManager.STATUS_SUCCESSFUL: return DownloadProgress.Status.SUCCESSFUL;
            case DownloadManager.STATUS_FAILED: return DownloadProgress.Status.FAILED;
            default: return DownloadProgress.Status.NONE;
        }
    }
}
