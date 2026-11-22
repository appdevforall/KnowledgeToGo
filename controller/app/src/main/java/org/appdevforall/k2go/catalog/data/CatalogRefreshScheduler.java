/*
 * ============================================================================
 * Name        : CatalogRefreshScheduler.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Enqueues a catalog refresh: weekly plus a
 *               TTL-gated check on open, on Wi-Fi only, when the user chose to request
 *               online content (K2GO-588); and a forced check when a user-started
 *               download hits a 404 (K2GO-390).
 * ============================================================================
 */
package org.appdevforall.k2go.catalog.data;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.appdevforall.k2go.delivery.data.OnlineConsent;

import java.util.concurrent.TimeUnit;

public final class CatalogRefreshScheduler {

    private CatalogRefreshScheduler() {
    }

    private static Data input(String name, String manifestUrl, String basename, boolean force) {
        return new Data.Builder()
                .putString(CatalogRefreshWorker.KEY_NAME, name)
                .putString(CatalogRefreshWorker.KEY_MANIFEST_URL, manifestUrl)
                .putString(CatalogRefreshWorker.KEY_BASENAME, basename)
                .putBoolean(CatalogRefreshWorker.KEY_FORCE, force)
                .build();
    }

    private static Constraints constraints(NetworkType netType) {
        return new Constraints.Builder()
                .setRequiredNetworkType(netType)
                .build();
    }

    /**
     * K2GO-588: keep the automatic refresh in step with the online choice. On: a weekly refresh plus
     * an opportunistic TTL-gated check now, both on an unmetered network only (K2GO-580). Off (Keep
     * offline): cancel both. Safe to call on every launch. The worker also reads the choice when it
     * runs, so a job queued before the user turned it off does not connect.
     */
    public static void syncAutomatic(Context ctx, String name, String manifestUrl, String basename) {
        WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
        if (!OnlineConsent.isEnabled(ctx)) {
            wm.cancelUniqueWork("catalog-refresh-" + name);
            wm.cancelUniqueWork("catalog-refresh-now-" + name);
            return;
        }
        // UPDATE (not KEEP) so an install that holds an older job gets the current constraint.
        wm.enqueueUniquePeriodicWork("catalog-refresh-" + name, ExistingPeriodicWorkPolicy.UPDATE,
                new PeriodicWorkRequest.Builder(CatalogRefreshWorker.class, 7, TimeUnit.DAYS)
                        .setConstraints(constraints(NetworkType.UNMETERED))
                        .setInputData(input(name, manifestUrl, basename, false))
                        .build());
        wm.enqueueUniqueWork("catalog-refresh-now-" + name, ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(CatalogRefreshWorker.class)
                        .setConstraints(constraints(NetworkType.UNMETERED))
                        .setInputData(input(name, manifestUrl, basename, false))
                        .build());
    }

    /**
     * K2GO-390: force an on-demand check that bypasses the worker's TTL gate (for a 404 self-heal --
     * the catalog may have rolled to a newer dated file within the TTL window). The ETag conditional
     * GET still makes it cheap. Its own unique name (KEEP) coalesces a burst of failures into one run.
     * It is part of the download the user started, so it does not depend on the online choice.
     */
    public static void forceRefresh(Context ctx, String name, String manifestUrl, String basename) {
        // K2GO-390: EXPEDITED so it dispatches promptly -- a 404 self-heal must land before the caller's
        // bounded retries give up (falls back to a normal request if the expedited quota is spent).
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CatalogRefreshWorker.class)
                .setConstraints(constraints(NetworkType.CONNECTED))
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(input(name, manifestUrl, basename, true))
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork("catalog-refresh-force-" + name,
                        ExistingWorkPolicy.KEEP, req);
    }
}
