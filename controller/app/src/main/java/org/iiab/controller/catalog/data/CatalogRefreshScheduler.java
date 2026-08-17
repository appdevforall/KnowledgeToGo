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

    private static Constraints constraints(NetworkType netType) {
        return new Constraints.Builder()
                .setRequiredNetworkType(netType)
                .build();
    }

    /** Weekly periodic refresh over any connection. Safe to call on every launch. */
    public static void scheduleWeekly(Context ctx, String name, String manifestUrl, String basename) {
        scheduleWeekly(ctx, name, manifestUrl, basename, NetworkType.CONNECTED);
    }

    /**
     * Weekly periodic refresh, enqueued once per catalog (KEEP). Safe to call on every launch.
     * {@code netType} lets a large asset ask for {@link NetworkType#UNMETERED} so it refreshes only
     * on Wi-Fi and never spends the user's mobile data (ADFA-5094: the tree bundle is ~16 MB).
     */
    public static void scheduleWeekly(Context ctx, String name, String manifestUrl, String basename,
                                      NetworkType netType) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                CatalogRefreshWorker.class, 7, TimeUnit.DAYS)
                .setConstraints(constraints(netType))
                .setInputData(input(name, manifestUrl, basename))
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniquePeriodicWork("catalog-refresh-" + name,
                        ExistingPeriodicWorkPolicy.KEEP, req);
    }

    /** Opportunistic one-shot over any connection; the worker's TTL gate no-ops it when fresh. */
    public static void refreshNow(Context ctx, String name, String manifestUrl, String basename) {
        refreshNow(ctx, name, manifestUrl, basename, NetworkType.CONNECTED);
    }

    /**
     * Opportunistic one-shot; the worker's TTL gate no-ops it when still fresh. {@code netType}
     * constrains which connection may trigger it — {@link NetworkType#UNMETERED} keeps a large
     * asset off mobile data (ADFA-5094).
     */
    public static void refreshNow(Context ctx, String name, String manifestUrl, String basename,
                                  NetworkType netType) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(CatalogRefreshWorker.class)
                .setConstraints(constraints(netType))
                .setInputData(input(name, manifestUrl, basename))
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork("catalog-refresh-now-" + name,
                        ExistingWorkPolicy.KEEP, req);
    }
}
