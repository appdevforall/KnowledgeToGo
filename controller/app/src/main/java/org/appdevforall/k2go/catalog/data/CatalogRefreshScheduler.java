/*
 * ============================================================================
 * Name        : CatalogRefreshScheduler.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Enqueues the catalog refresh: a weekly,
 *               network-constrained periodic job, plus an opportunistic one-shot
 *               (e.g. when the picker opens). The worker's own TTL gate keeps the
 *               one-shot cheap. Unique per catalog, so relaunches don't reset the
 *               schedule. Automatic refreshes run on Wi-Fi only (K2GO-580).
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

import java.util.concurrent.TimeUnit;

public final class CatalogRefreshScheduler {

    private CatalogRefreshScheduler() {
    }

    private static Data input(String name, String manifestUrl, String basename) {
        return input(name, manifestUrl, basename, false);
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

    // K2GO-580: automatic refreshes run only on an unmetered network (Wi-Fi), never on the user's
    // mobile data; the catalog bundled in the APK is the floor until then. A user-started download
    // that hits a 404 still self-heals over any connection (forceRefresh).

    /** Weekly periodic refresh, one per catalog. Safe to call on every launch. UPDATE (not KEEP) so an
     *  install that already holds the old any-network job gets the Wi-Fi constraint, schedule kept. */
    public static void scheduleWeekly(Context ctx, String name, String manifestUrl, String basename) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                CatalogRefreshWorker.class, 7, TimeUnit.DAYS)
                .setConstraints(constraints(NetworkType.UNMETERED))
                .setInputData(input(name, manifestUrl, basename))
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork("catalog-refresh-" + name,
                        ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    /** Opportunistic one-shot; the worker's TTL gate no-ops it when still fresh. */
    public static void refreshNow(Context ctx, String name, String manifestUrl, String basename) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CatalogRefreshWorker.class)
                .setConstraints(constraints(NetworkType.UNMETERED))
                .setInputData(input(name, manifestUrl, basename))
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork("catalog-refresh-now-" + name,
                        ExistingWorkPolicy.KEEP, req);
    }

    /**
     * K2GO-390: force an on-demand check that bypasses the worker's TTL gate (for a 404 self-heal --
     * the catalog may have rolled to a newer dated file within the TTL window). The ETag conditional
     * GET still makes it cheap. Its own unique name (KEEP) coalesces a burst of failures into one run.
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
