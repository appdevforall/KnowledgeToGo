/*
 * ============================================================================
 * Name        : CodeAssetsUpdater.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-503. The Code on the Go build-assets adapter for the shared ContentUpdaterView: a
 *               thin delegate over CodeAssetsDownloadService (the aria2 download path) plus the
 *               module's strings. The whole updater UX lives in ContentUpdaterView; this wires the
 *               module. Byte-for-byte the add-ons adapter with a different service and string set:
 *               the duplication that used to live in the Refresh views is gone.
 * ============================================================================
 */
package org.appdevforall.k2go.codeassets.presentation;

import android.content.Context;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.redesign.ContentUpdater;

public final class CodeAssetsUpdater implements ContentUpdater {

    public static final CodeAssetsUpdater INSTANCE = new CodeAssetsUpdater();

    private CodeAssetsUpdater() {}

    @Override public void start(Context ctx) { CodeAssetsDownloadService.start(ctx); }
    @Override public void pause(Context ctx) { CodeAssetsDownloadService.pause(ctx); }
    @Override public void resume(Context ctx) { CodeAssetsDownloadService.resume(ctx); }
    @Override public void cancel(Context ctx) { CodeAssetsDownloadService.cancel(ctx); }
    @Override public void retry(Context ctx) { CodeAssetsDownloadService.retry(ctx); }
    @Override public void finishSession() { CodeAssetsDownloadService.finishSession(); }

    @Override public void setListener(Runnable onUpdate) {
        CodeAssetsDownloadService.Listener l = onUpdate == null ? null : onUpdate::run;
        CodeAssetsDownloadService.setListener(l);
    }

    @Override public boolean isRunning() { return CodeAssetsDownloadService.isRunning(); }
    @Override public boolean isPaused() { return CodeAssetsDownloadService.isPaused(); }
    @Override public boolean hasSession() { return CodeAssetsDownloadService.hasSession(); }
    @Override public boolean isComplete() { return CodeAssetsDownloadService.isComplete(); }
    @Override public boolean hasFailed() { return CodeAssetsDownloadService.hasFailed(); }
    @Override public int percent() { return CodeAssetsDownloadService.percent(); }
    @Override public long speed() { return CodeAssetsDownloadService.speed(); }
    @Override public long etaSeconds() { return CodeAssetsDownloadService.etaSeconds(); }
    @Override public int reconnectAttempt() { return CodeAssetsDownloadService.reconnectAttempt(); }
    @Override public int reconnectTotal() { return CodeAssetsDownloadService.reconnectTotal(); }
    @Override public boolean lastWasUpToDate() { return CodeAssetsDownloadService.lastWasUpToDate(); }
    @Override public String currentDetail() { return CodeAssetsDownloadService.currentDetail(); }

    @Override public boolean supportsPauseResume() { return true; }
    @Override public boolean supportsSpeedEta() { return true; }

    @Override public int titleRes() { return R.string.k2go_code_assets_updating; }
    @Override public int doneRes() { return R.string.k2go_code_assets_update_done; }
    @Override public int upToDateRes() { return R.string.k2go_code_assets_update_up_to_date; }
    @Override public int failedRes() { return R.string.k2go_code_assets_update_failed; }
    @Override public int cancelledRes() { return R.string.k2go_code_assets_update_cancelled; }
}
