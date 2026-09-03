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
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
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

            // Prefer the pulled overlay over the APK asset when it is present.
            File overlay = CatalogOverlay.file(appContext, ASSET);
            boolean useOverlay = overlay.exists();
            long mtime = useOverlay ? overlay.lastModified() : 0L;

            try (InputStream is = readGzipAware(useOverlay
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

    /**
     * Reads {@code in} whether it is gzip or plain, so the single reader serves both tree sources:
     * the R2 overlay is a real gzip, while the APK asset is the same bundle but already decompressed
     * by AGP (which strips the {@code .gz} from any {@code *.gz} asset at merge time). The 2-byte
     * gzip signature (0x1f 0x8b) decides, so the two paths cannot drift out of sync again. The
     * caller closes the returned stream, which closes {@code in}.
     */
    private static InputStream readGzipAware(InputStream in) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(in, 2);
        byte[] sig = new byte[2];
        int n = 0;
        while (n < 2) {
            int r = pb.read(sig, n, 2 - n);
            if (r < 0) {
                break;
            }
            n += r;
        }
        boolean gzip = n == 2 && (sig[0] & 0xff) == 0x1f && (sig[1] & 0xff) == 0x8b;
        if (n > 0) {
            pb.unread(sig, 0, n);
        }
        return gzip ? new GZIPInputStream(pb) : pb;
    }
}
