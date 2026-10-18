/*
 * ============================================================================
 * Name        : CatalogRefreshWorker.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5094 (ADR-5094). Catalog-agnostic WorkManager job that
 *               refreshes one catalog: TTL gate -> conditional manifest fetch
 *               (ETag) -> if the hash changed, download the catalog, verify its
 *               sha256, write the overlay. The catalog to refresh is passed as
 *               input data {name, manifestUrl, basename}, so one worker serves
 *               Kolibri, Kiwix, etc.
 * ============================================================================
 */
package org.appdevforall.k2go.catalog.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.appdevforall.k2go.catalog.domain.CatalogFreshness;
import org.appdevforall.k2go.catalog.domain.CatalogManifest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

public final class CatalogRefreshWorker extends Worker {

    private static final String TAG = "K2Go-Catalog";

    public static final String KEY_NAME = "name";
    public static final String KEY_MANIFEST_URL = "manifest_url";
    public static final String KEY_BASENAME = "basename";
    // K2GO-390: bypass the TTL gate for an on-demand check (e.g. a 404 self-heal needs to look now,
    // even if the weekly check ran recently). The ETag conditional GET still keeps it cheap.
    public static final String KEY_FORCE = "force";

    public CatalogRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String name = getInputData().getString(KEY_NAME);
        String manifestUrl = getInputData().getString(KEY_MANIFEST_URL);
        String basename = getInputData().getString(KEY_BASENAME);
        if (name == null || manifestUrl == null || basename == null) {
            return Result.success();   // nothing to do; misconfigured request
        }

        CatalogRefreshStore store = new CatalogRefreshStore(ctx);
        long now = System.currentTimeMillis();
        boolean force = getInputData().getBoolean(KEY_FORCE, false);
        if (!force && !CatalogFreshness.dueForCheck(store.lastCheckMs(name), now, CatalogFreshness.DEFAULT_TTL_MS)) {
            return Result.success();   // still fresh; do not hit the network
        }
        // K2GO-476: a pull that failed recently (a bad publish) is not repeated on every open.
        long lastFailure = store.lastFailureMs(name);
        if (!force && lastFailure > 0
                && !CatalogFreshness.dueForCheck(lastFailure, now, CatalogFreshness.FAILURE_BACKOFF_MS)) {
            return Result.success();
        }

        // K2GO-476: a check counts only once it has an answer. Recording it before the download
        // let one failed pull hide behind the TTL for a week.
        File overlay = CatalogOverlay.file(ctx, basename);
        CatalogManifestClient client = new CatalogManifestClient();
        // Without the overlay there is nothing a 304 could confirm, so ask for the manifest.
        CatalogManifestClient.Result res = client.fetchManifest(manifestUrl,
                overlay.exists() ? store.etag(name) : null);
        if (res.status == CatalogManifestClient.Status.FAILED) {
            return Result.retry();     // offline / server hiccup: back off and try again
        }
        if (res.status == CatalogManifestClient.Status.NOT_MODIFIED) {
            store.recordChecked(name, now);
            return Result.success();
        }

        CatalogManifest m = res.manifest;
        if (overlay.exists() && !CatalogFreshness.changed(store.activeHash(name), m.hash())) {
            // Same content the app already uses; just refresh the ETag so next check is cheaper.
            store.recordApplied(name, m.hash(), m.generated(), res.etag, now);
            return Result.success();
        }

        // Download beside the overlay, verify, then swap: a bad file never replaces a good one.
        // The tmp from an attempt the process did not finish is overwritten here.
        File tmp = new File(overlay.getAbsolutePath() + ".tmp");
        if (!client.downloadTo(m.url(), tmp) || !hashMatches(tmp, m.hash()) || !tmp.renameTo(overlay)) {
            Log.w(TAG, "catalog " + name + " pull failed; keeping the current one for now");
            tmp.delete();
            store.recordFailed(name, now);
            return Result.success();   // backed off by FAILURE_BACKOFF_MS, not retried in a loop
        }
        store.recordApplied(name, m.hash(), m.generated(), res.etag, now);
        Log.d(TAG, "catalog " + name + " updated to " + m.version());
        return Result.success();
    }

    /** True when the file's sha256 equals the manifest hash ({@code sha256:<hex>}). */
    private static boolean hashMatches(File file, String manifestHash) {
        if (manifestHash == null) {
            return false;
        }
        String expected = manifestHash.startsWith("sha256:") ? manifestHash.substring(7) : manifestHash;
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString().equalsIgnoreCase(expected);
        } catch (Throwable t) {
            return false;
        }
    }
}
