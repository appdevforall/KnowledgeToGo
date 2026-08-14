/*
 * ============================================================================
 * Name        : CatalogRefreshScheduler.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Enqueues the catalog refresh: a weekly,
 *               network-constrained periodic job, plus an opportunistic one-shot
 *               (e.g. when the picker opens). The worker's own TTL gate keeps the
 *               one-shot cheap. Unique per catalog, KEEP so relaunches don't
 *               reset the schedule.
 * ============================================================================
 */
package org.iiab.controller.catalog.data;

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
        return new Data.Builder()
                .putString(CatalogRefreshWorker.KEY_NAME, name)
                .putString(CatalogRefreshWorker.KEY_MANIFEST_URL, manifestUrl)
                .putString(CatalogRefreshWorker.KEY_BASENAME, basename)
                .build();
    }

    private static Constraints connected() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }

    /** Weekly periodic refresh, enqueued once per catalog (KEEP). Safe to call on every launch. */
    public static void scheduleWeekly(Context ctx, String name, String manifestUrl, String basename) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                CatalogRefreshWorker.class, 7, TimeUnit.DAYS)
                .setConstraints(connected())
                .setInputData(input(name, manifestUrl, basename))
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork("catalog-refresh-" + name,
                        ExistingPeriodicWorkPolicy.KEEP, req);
    }

    /** Opportunistic one-shot; the worker's TTL gate no-ops it when still fresh. */
    public static void refreshNow(Context ctx, String name, String manifestUrl, String basename) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CatalogRefreshWorker.class)
                .setConstraints(connected())
                .setInputData(input(name, manifestUrl, basename))
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork("catalog-refresh-now-" + name,
                        ExistingWorkPolicy.KEEP, req);
    }
}
