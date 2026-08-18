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
package org.iiab.controller.kolibri.data;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.catalog.data.CatalogOverlay;
import org.iiab.controller.kolibri.domain.TopicNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

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
    // ADFA-5094: the overlay basename (what the refresh worker writes) AND the APK asset name.
    // Both are the GZIPPED tree (~3 MB vs ~16 MB raw): R2 serves the .gz so the pull is small, and
    // the APK floor ships the .gz too. Decompressed on read — never written out uncompressed.
    // CatalogRepositoryImpl points the worker at this same name, so it writes where we look.
    static final String ASSET = "kolibri_tree.jsonl.gz";

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

            // Prefer the pulled overlay over the APK asset when it is present.
            File overlay = CatalogOverlay.file(appContext, ASSET);
            boolean useOverlay = overlay.exists();
            long mtime = useOverlay ? overlay.lastModified() : 0L;

            try (InputStream is = new GZIPInputStream(useOverlay
                    ? new FileInputStream(overlay) : appContext.getAssets().open(ASSET));
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
