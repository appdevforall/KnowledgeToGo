/*
 * ============================================================================
 * Name        : CatalogRepositoryImpl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Wires the bundled catalog and the topic tree (box-served first,
 *               Studio as fallback) behind the domain port (ADFA-4954, -5094).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.data;

import android.content.Context;

import org.appdevforall.k2go.kolibri.domain.CatalogRepository;
import org.appdevforall.k2go.kolibri.domain.Channel;
import org.appdevforall.k2go.kolibri.domain.TopicNode;

import java.util.List;

/**
 * The Data-layer implementation of {@link CatalogRepository}.
 *
 * <p>Two sources with different characters, which is the whole point of putting
 * them behind one port: channels come from a file in the APK and cannot fail in
 * any interesting way, trees come from the internet and can.
 *
 * <p>Blocking, like its sources. Callers run it on {@code AppExecutors.io()}.
 */
public final class CatalogRepositoryImpl implements CatalogRepository {

    // ADFA-5094: the Kolibri catalog is refreshed from Cloudflare (ADR-5094), overlaying the APK
    // asset when a newer version has been pulled.
    private static final String CATALOG = "kolibri";
    private static final String MANIFEST_URL =
            "https://k2go-download.appdevforall.org/catalogs/kolibri.manifest.json";
    // Single source of truth for the basename: the same name BundledCatalogSource reads the overlay
    // from, so the worker writes it exactly where the source looks (they must never drift).
    private static final String BASENAME = BundledCatalogSource.ASSET;

    // ADFA-5094: the offline topic-tree bundle is refreshed the same way, from its own manifest,
    // overlaying its own APK asset. A separate name so the two refreshes never collide.
    private static final String TREE_CATALOG = "kolibri-tree";
    private static final String TREE_MANIFEST_URL =
            "https://k2go-download.appdevforall.org/catalogs/kolibri-tree.manifest.json";
    private static final String TREE_BASENAME = BundledTreeSource.ASSET;

    private final BundledCatalogSource bundled;
    private final TreeSource tree;

    public CatalogRepositoryImpl(Context context) {
        // ADFA-5094: prefer the box-served topic tree (offline, whole tree once the channel's
        // metadata is imported), then Studio when online, and finally the bundled tree — the
        // offline floor that lets the user browse a channel even with the box unimported and no
        // internet. Live sources win when they can answer, so the bundle is consulted last.
        this(new BundledCatalogSource(context),
                new FallbackTreeSource(new LocalTreeSource(),
                        new FallbackTreeSource(new StudioTreeSource(), new BundledTreeSource(context))));
        // Keep the bundled catalog current: a weekly refresh plus an opportunistic (TTL-gated)
        // check now. Only the Context constructor schedules — the test constructor below does not.
        org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler.scheduleWeekly(
                context, CATALOG, MANIFEST_URL, BASENAME);
        org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler.refreshNow(
                context, CATALOG, MANIFEST_URL, BASENAME);
        // The tree bundle refreshes on the same cadence, but only on Wi-Fi (UNMETERED): it is ~16 MB
        // against the catalog's ~83 KB, and the APK asset is a working floor, so a metered pull is
        // not worth the user's mobile data.
        org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler.scheduleWeekly(
                context, TREE_CATALOG, TREE_MANIFEST_URL, TREE_BASENAME,
                androidx.work.NetworkType.UNMETERED);
        org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler.refreshNow(
                context, TREE_CATALOG, TREE_MANIFEST_URL, TREE_BASENAME,
                androidx.work.NetworkType.UNMETERED);
    }

    /** For tests and for pointing the tree source elsewhere (e.g. a local-first composite). */
    public CatalogRepositoryImpl(BundledCatalogSource bundled, TreeSource tree) {
        this.bundled = bundled;
        this.tree = tree;
    }

    @Override
    public List<Channel> channels() {
        return bundled.channels();
    }

    @Override
    public String catalogGeneratedOn() {
        return bundled.generatedOn();
    }

    @Override
    public TopicNode fetchTree(String nodeId) {
        return tree.fetchTree(nodeId);
    }
}
