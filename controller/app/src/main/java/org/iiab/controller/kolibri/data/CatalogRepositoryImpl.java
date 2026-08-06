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

    private final BundledCatalogSource bundled;
    private final StudioTreeSource studio;

    public CatalogRepositoryImpl(Context context) {
        this(new BundledCatalogSource(context), new StudioTreeSource());
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
