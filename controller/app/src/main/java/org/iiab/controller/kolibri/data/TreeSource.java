/*
 * ============================================================================
 * Name        : TreeSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One seam for reading a channel's topic tree, so the repository
 *               can prefer a box-served tree and fall back to Studio without
 *               knowing which is which (ADFA-5094).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import org.iiab.controller.kolibri.domain.TopicNode;

/**
 * A source of one level of a channel's topic tree.
 *
 * <p>Introduced so the two implementations — {@link StudioTreeSource} over the
 * internet and {@link LocalTreeSource} over the box on localhost — are
 * interchangeable behind {@link FallbackTreeSource}. Before ADFA-5094 the
 * repository held a {@code StudioTreeSource} directly; the tree was Studio's or
 * nothing.
 *
 * <p>Same contract as {@code CatalogRepository.fetchTree}: <b>never throws</b>.
 * A failure — no network, box not running, channel not imported, a malformed
 * body — is a {@code null} return, so a fallback source can be tried in turn.
 */
public interface TreeSource {

    /**
     * One level of the tree rooted at {@code nodeId}, with its direct children.
     *
     * @return the subtree, or {@code null} when this source could not serve it
     */
    TopicNode fetchTree(String nodeId);
}
