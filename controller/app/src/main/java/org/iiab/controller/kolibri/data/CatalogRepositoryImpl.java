/*
 * ============================================================================
 * Name        : CatalogRepositoryImpl.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Wires the bundled catalog and the live Studio tree behind the
 *               domain port (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import android.content.Context;

import org.iiab.controller.kolibri.domain.CatalogRepository;
import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.kolibri.domain.TopicNode;

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

    private final BundledCatalogSource bundled;
    private final StudioTreeSource studio;

    public CatalogRepositoryImpl(Context context) {
        this(new BundledCatalogSource(context), new StudioTreeSource());
        // Keep the bundled catalog current: a weekly refresh plus an opportunistic (TTL-gated)
        // check now. Only the Context constructor schedules — the test constructor below does not.
        org.iiab.controller.catalog.data.CatalogRefreshScheduler.scheduleWeekly(
                context, CATALOG, MANIFEST_URL, BASENAME);
        org.iiab.controller.catalog.data.CatalogRefreshScheduler.refreshNow(
                context, CATALOG, MANIFEST_URL, BASENAME);
    }

    /** For tests and for pointing the tree source at a mirror. */
    public CatalogRepositoryImpl(BundledCatalogSource bundled, StudioTreeSource studio) {
        this.bundled = bundled;
        this.studio = studio;
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
        return studio.fetchTree(nodeId);
    }
}
