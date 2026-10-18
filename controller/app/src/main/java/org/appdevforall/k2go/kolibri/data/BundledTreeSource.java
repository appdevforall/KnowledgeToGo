/*
 * ============================================================================
 * Name        : BundledTreeSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Serves the Kolibri topic tree from the flat bundle shipped in
 *               the APK (kolibri_tree.jsonl), preferring a newer copy pulled
 *               from Cloudflare. The offline floor for tree browsing: it answers
 *               when neither the box nor Studio can (ADFA-5094).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.data;

import android.content.Context;
import android.util.Log;

import org.appdevforall.k2go.catalog.data.CatalogOverlay;
import org.appdevforall.k2go.kolibri.domain.TopicNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The topic tree bundled with the app, as a {@link TreeSource}.
 *
 * <p>Mirrors {@link BundledCatalogSource}: JSON Lines, one folder per line (no header),
 * parsed once into a {@link BundledTreeIndex} and cached for the process — the file does
 * not change while the app runs, and a pulled Cloudflare overlay is preferred over the APK
 * asset, picked up by the same mtime check.
 *
 * <p>Unlike the box- and Studio-backed sources, this one is offline and holds the whole
 * (topics-only) tree, so it is the <em>last</em> fallback: when the box has not imported a
 * channel and there is no internet, this still lets the user browse what a channel contains.
 * Individual leaf resources are not in the bundle — a mixed level shows the folder's loose
 * aggregate, and the live source fills the leaves when online.
 *
 * <p>Blocking; call from an IO thread. Never throws (the {@link TreeSource} contract): a
 * missing asset or unknown node id is a {@code null} return, so a caller can move on.
 */
public final class BundledTreeSource implements TreeSource {

    private static final String TAG = "K2Go-Kolibri";
    // ADFA-5094: the OVERLAY basename — what the refresh worker writes and what R2 serves. It is a
    // real gzip (~3 MB), so the pull stays small. CatalogRepositoryImpl points the worker at this
    // same name, so it writes where we look. Read through readGzipAware(), which un-gzips it.
    static final String ASSET = "kolibri_tree.jsonl.gz";
    // The APK-bundled tree lands under THIS name, not ASSET: AGP auto-decompresses any *.gz asset at
    // merge time and drops the ".gz" extension, so a source `kolibri_tree.jsonl.gz` ships inside the
    // APK as an already-decompressed `kolibri_tree.jsonl`. Opening ASSET here would miss it entirely
    // (the silent-empty-tree bug). readGzipAware() then reads it as-is (plain), while the same reader
    // still un-gzips the R2 overlay — the byte signature decides, so neither path can drift again.
    private static final String APK_ASSET = "kolibri_tree.jsonl";

    private static volatile BundledTreeIndex cachedIndex;
    // -1 = not loaded, 0 = APK asset, >0 = the pulled overlay's lastModified.
    private static volatile long cachedOverlayMtime = -1L;

    private final Context appContext;

    public BundledTreeSource(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public TopicNode fetchTree(String nodeId) {
        reloadIfOverlayChanged();
        BundledTreeIndex idx = load();
        return idx == null ? null : idx.fetchOneLevel(nodeId);
    }

    /** Drops the cache. For tests and for the in-place tree refresh (ADFA-5094). */
    public static void invalidate() {
        cachedIndex = null;
        cachedOverlayMtime = -1L;
    }

    /**
     * Pick up a freshly pulled overlay (or its removal) without an explicit callback: if the
     * overlay file's mtime differs from what the cache was loaded from, drop the cache so the
     * next {@link #load()} reads the current source.
     */
    private void reloadIfOverlayChanged() {
        if (cachedIndex == null) {
            return; // not loaded yet; load() picks the source
        }
        File overlay = CatalogOverlay.file(appContext, ASSET);
        long mtime = overlay.exists() ? overlay.lastModified() : 0L;
        if (mtime != cachedOverlayMtime) {
            invalidate();
        }
    }

    private BundledTreeIndex load() {
        if (cachedIndex != null) {
            return cachedIndex;
        }
        synchronized (BundledTreeSource.class) {
            if (cachedIndex != null) {
                return cachedIndex;
            }
            BundledTreeIndex.Builder builder = BundledTreeIndex.builder();

            // Prefer the pulled overlay over the APK asset, unless an app update shipped a newer
            // asset (K2GO-476).
            File overlay = CatalogOverlay.file(appContext, ASSET);
            boolean useOverlay = CatalogOverlay.preferOverlay(appContext, ASSET, APK_ASSET);
            // The overlay's own mtime even when the asset won, or reloadIfOverlayChanged would
            // see a difference on every call and re-read the whole tree each time.
            long mtime = overlay.exists() ? overlay.lastModified() : 0L;

            try (InputStream is = CatalogOverlay.gzipAware(useOverlay
                    ? new FileInputStream(overlay) : appContext.getAssets().open(APK_ASSET));
                 BufferedReader r = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    builder.add(line);
                }
            } catch (Exception e) {
                // A missing asset is a build problem, not a runtime one; a truncated/corrupt .gz
                // trips GZIPInputStream's CRC. Either way it must not take the app down: log and
                // serve an empty tree, so the caller falls through to the live sources.
                Log.w(TAG, "bundled tree unavailable: " + e.getMessage());
            }

            if (builder.skipped() > 0) {
                Log.w(TAG, "bundled tree: skipped " + builder.skipped() + " unusable line(s)");
            }
            cachedIndex = builder.build();
            cachedOverlayMtime = mtime;
            return cachedIndex;
        }
    }
}
