/*
 * ============================================================================
 * Name        : AddonsUpdater.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-503. The Code on the Go add-ons adapter for the shared ContentUpdaterView: a thin
 *               delegate over AddonsDownloadService (the aria2 download path) plus the module's
 *               strings. The whole updater UX lives in ContentUpdaterView; this just wires the module.
 * ============================================================================
 */
package org.appdevforall.k2go.addons.presentation;

import android.content.Context;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.redesign.ContentUpdater;

public final class AddonsUpdater implements ContentUpdater {

    public static final AddonsUpdater INSTANCE = new AddonsUpdater();

    private AddonsUpdater() {}

    @Override public void start(Context ctx) { AddonsDownloadService.start(ctx); }
    @Override public void pause(Context ctx) { AddonsDownloadService.pause(ctx); }
    @Override public void resume(Context ctx) { AddonsDownloadService.resume(ctx); }
    @Override public void cancel(Context ctx) { AddonsDownloadService.cancel(ctx); }
    @Override public void retry(Context ctx) { AddonsDownloadService.retry(ctx); }
    @Override public void finishSession() { AddonsDownloadService.finishSession(); }

    @Override public void setListener(Runnable onUpdate) {
        AddonsDownloadService.Listener l = onUpdate == null ? null : onUpdate::run;
        AddonsDownloadService.setListener(l);
    }

    @Override public boolean isRunning() { return AddonsDownloadService.isRunning(); }
    @Override public boolean isPaused() { return AddonsDownloadService.isPaused(); }
    @Override public boolean hasSession() { return AddonsDownloadService.hasSession(); }
    @Override public boolean isComplete() { return AddonsDownloadService.isComplete(); }
    @Override public boolean hasFailed() { return AddonsDownloadService.hasFailed(); }
    @Override public int percent() { return AddonsDownloadService.percent(); }
    @Override public long speed() { return AddonsDownloadService.speed(); }
    @Override public long etaSeconds() { return AddonsDownloadService.etaSeconds(); }
    @Override public int reconnectAttempt() { return AddonsDownloadService.reconnectAttempt(); }
    @Override public int reconnectTotal() { return AddonsDownloadService.reconnectTotal(); }
    @Override public boolean lastWasUpToDate() { return AddonsDownloadService.lastWasUpToDate(); }
    @Override public String currentDetail() { return AddonsDownloadService.currentDetail(); }

    @Override public boolean supportsPauseResume() { return true; }
    @Override public boolean supportsSpeedEta() { return true; }

    @Override public int titleRes() { return R.string.k2go_code_addons_updating; }
    @Override public int doneRes() { return R.string.k2go_code_addons_update_done; }
    @Override public int upToDateRes() { return R.string.k2go_code_addons_update_up_to_date; }
    @Override public int failedRes() { return R.string.k2go_code_addons_update_failed; }
    @Override public int cancelledRes() { return R.string.k2go_code_addons_update_cancelled; }
}
