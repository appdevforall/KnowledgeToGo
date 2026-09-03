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
        if (!CatalogFreshness.dueForCheck(store.lastCheckMs(name), now, CatalogFreshness.DEFAULT_TTL_MS)) {
            return Result.success();   // still fresh; do not hit the network
        }

        CatalogManifestClient client = new CatalogManifestClient();
        CatalogManifestClient.Result res = client.fetchManifest(manifestUrl, store.etag(name));
        if (res.status == CatalogManifestClient.Status.FAILED) {
            return Result.retry();     // offline / server hiccup: back off and try again
        }
        store.recordChecked(name, now);
        if (res.status == CatalogManifestClient.Status.NOT_MODIFIED) {
            return Result.success();
        }

        CatalogManifest m = res.manifest;
        if (!CatalogFreshness.changed(store.activeHash(name), m.hash())) {
            // Same content the app already uses; just refresh the ETag so next check is cheaper.
            store.recordApplied(name, m.hash(), m.generated(), res.etag, now);
            return Result.success();
        }

        File overlay = CatalogOverlay.file(ctx, basename);
        if (!client.downloadTo(m.url(), overlay)) {
            return Result.retry();
        }
        if (!hashMatches(overlay, m.hash())) {
            Log.w(TAG, "catalog " + name + " hash mismatch after download; discarding");
            overlay.delete();
            return Result.retry();
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
