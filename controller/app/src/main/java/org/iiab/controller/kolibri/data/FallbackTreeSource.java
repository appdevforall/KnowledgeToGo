/*
 * ============================================================================
 * Name        : FallbackTreeSource.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Tries one tree source, then another. Lets the repository prefer
 *               the box-served tree and fall back to Studio (ADFA-5094).
 * ============================================================================
 */
package org.iiab.controller.kolibri.data;

import org.iiab.controller.kolibri.domain.TopicNode;

/**
 * A {@link TreeSource} that asks {@code primary} first and {@code secondary}
 * only when the first has nothing.
 *
 * <p>The one place the local-first policy lives: prefer the box (offline,
 * instant, whole tree once imported), fall to Studio when the box cannot answer
 * — box down, channel not imported, or PR3 not yet shipped, in which case the
 * primary always misses and behaviour is exactly what it was before ADFA-5094.
 *
 * <p>Deliberately pure — no network, no Android — so this routing is unit-tested
 * on a plain JVM while the sources it composes are integration concerns.
 * {@code secondary} is not consulted when {@code primary} answers, so a hit
 * costs no extra call.
 */
public final class FallbackTreeSource implements TreeSource {

    private final TreeSource primary;
    private final TreeSource secondary;

    public FallbackTreeSource(TreeSource primary, TreeSource secondary) {
        if (primary == null || secondary == null) {
            throw new IllegalArgumentException("both sources are required");
        }
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public TopicNode fetchTree(String nodeId) {
        TopicNode fromPrimary = primary.fetchTree(nodeId);
        return fromPrimary != null ? fromPrimary : secondary.fetchTree(nodeId);
    }
}
