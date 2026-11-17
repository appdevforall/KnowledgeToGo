/*
 * ============================================================================
 * Name        : CatalogRefreshScheduler.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Enqueues a catalog refresh. K2GO-580: a refresh
 *               runs only when the user asks (the Get more "Update catalogs" row) or
 *               when a user-started download hits a 404. Nothing refreshes by itself.
 * ============================================================================
 */
package org.appdevforall.k2go.catalog.data;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

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

    /** Tag on every refresh request, so the Get more row can watch and cancel them as one. */
    public static final String TAG = "catalog-refresh";

    /**
     * K2GO-580: removes the automatic refresh jobs that older builds scheduled (weekly + on open).
     * WorkManager keeps them across an app update, so the call sites that used to schedule them
     * now cancel them. Idempotent and cheap.
     */
    // ponytail: delete once no supported install predates K2GO-580.
    public static void cancelAutomatic(Context ctx, String name) {
        WorkManager wm = WorkManager.getInstance(ctx.getApplicationContext());
        wm.cancelUniqueWork("catalog-refresh-" + name);
        wm.cancelUniqueWork("catalog-refresh-now-" + name);
    }

    /** Stops every running or queued refresh (the Get more row's Cancel). */
    public static void cancelAll(Context ctx) {
        WorkManager.getInstance(ctx.getApplicationContext()).cancelAllWorkByTag(TAG);
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
                .addTag(TAG)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork("catalog-refresh-force-" + name,
                        ExistingWorkPolicy.KEEP, req);
    }
}
