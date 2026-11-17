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
            org.appdevforall.k2go.config.DownloadEndpoints.APK_REPO + "/catalogs/kolibri.manifest.json";
    // Single source of truth for the basename: the same name BundledCatalogSource reads the overlay
    // from, so the worker writes it exactly where the source looks (they must never drift).
    private static final String BASENAME = BundledCatalogSource.ASSET;

    // ADFA-5094: the offline topic-tree bundle is refreshed the same way, from its own manifest,
    // overlaying its own APK asset. A separate name so the two refreshes never collide.
    private static final String TREE_CATALOG = "kolibri-tree";
    private static final String TREE_MANIFEST_URL =
            org.appdevforall.k2go.config.DownloadEndpoints.APK_REPO + "/catalogs/kolibri-tree.manifest.json";
    private static final String TREE_BASENAME = BundledTreeSource.ASSET;

    private final BundledCatalogSource bundled;
    private final TreeSource tree;

    public CatalogRepositoryImpl(Context context) {
        // ADFA-5094: prefer the box-served topic tree (offline, whole tree once the channel's
        // metadata is imported), then Studio when online, and finally the bundled tree — the
        // offline floor that lets the user browse a channel even with the box unimported and no
        // internet. Live sources win when they can answer, so the bundle is consulted last.
        this(new BundledCatalogSource(context), liveFirst(context, new BundledTreeSource(context)));
        // K2GO-580: the catalogs never refresh by themselves; the user asks (see refresh).
        org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler.cancelAutomatic(context, CATALOG);
        org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler.cancelAutomatic(context, TREE_CATALOG);
    }

    /** K2GO-580: user-asked refresh of the Kolibri catalog and its topic-tree bundle. */
    public static void refresh(Context context) {
        org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler.forceRefresh(
                context, CATALOG, MANIFEST_URL, BASENAME);
        org.appdevforall.k2go.catalog.data.CatalogRefreshScheduler.forceRefresh(
                context, TREE_CATALOG, TREE_MANIFEST_URL, TREE_BASENAME);
    }

    /** K2GO-476: box, then Studio with the bundle's folder sizes filled in, then the bundle.
     *  K2GO-580: Studio is an automatic read, so it follows allowsAutomatic; else the bundle answers. */
    private static TreeSource liveFirst(Context context, BundledTreeSource bundled) {
        final Context app = context.getApplicationContext();
        final StudioTreeSource studio = new StudioTreeSource();
        TreeSource gatedStudio = id ->
                org.appdevforall.k2go.networkpolicy.data.NetworkCostAdmission.allowsAutomatic(app)
                        ? studio.fetchTree(id) : null;
        return new FallbackTreeSource(new LocalTreeSource(),
                new FallbackTreeSource(new BundleSizedTreeSource(gatedStudio, bundled), bundled));
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
