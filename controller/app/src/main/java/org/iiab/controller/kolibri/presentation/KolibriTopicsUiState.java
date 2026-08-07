/*
 * ============================================================================
 * Name        : KolibriTopicsUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Immutable view state for one level of the topic picker
 *               (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

import org.iiab.controller.kolibri.domain.TopicNode;

import java.util.Collections;
import java.util.List;

/**
 * One level of a channel's tree as the screen renders it.
 *
 * <p>Carries the ancestry, not just the children, because the selection rule needs
 * it: a picked node has to know which nodes sit above it so
 * {@code PickedSubtrees} can tell whether an ancestor already covers it. The
 * screen would otherwise have to keep a parallel stack and the two could drift.
 *
 * <p>Three shapes: loading, unavailable, and a level. {@code unavailable} is kept
 * distinct from an empty level for the same reason the use case does — offline is
 * not the same statement as "this topic has nothing in it".
 *
 * <p>Immutable.
 */
public final class KolibriTopicsUiState {

    private final boolean loading;
    private final boolean unavailable;
    private final TopicNode node;
    private final String title;
    private final List<String> ancestorIds;
    private final List<String> trail;

    private KolibriTopicsUiState(boolean loading, boolean unavailable, TopicNode node,
                                 String title, List<String> ancestorIds,
                                 List<String> trail) {
        this.loading = loading;
        this.unavailable = unavailable;
        this.node = node;
        this.title = title == null ? "" : title;
        this.ancestorIds = ancestorIds;
        this.trail = trail;
    }

    static KolibriTopicsUiState loading(String title, List<String> trail) {
        return new KolibriTopicsUiState(true, false, null, title,
                Collections.<String>emptyList(), freeze(trail));
    }

    static KolibriTopicsUiState unavailable(String title, List<String> trail) {
        return new KolibriTopicsUiState(false, true, null, title,
                Collections.<String>emptyList(), freeze(trail));
    }

    static KolibriTopicsUiState level(TopicNode node, String title,
                                      List<String> ancestorIds, List<String> trail) {
        if (node == null) {
            return unavailable(title, trail);
        }
        return new KolibriTopicsUiState(false, false, node, title,
                freeze(ancestorIds), freeze(trail));
    }

    private static List<String> freeze(List<String> in) {
        return in == null || in.isEmpty()
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(in));
    }

    public boolean isLoading() {
        return loading;
    }

    /** The level could not be read — no connection, or Studio did not answer. */
    public boolean isUnavailable() {
        return unavailable;
    }

    /** The node the user is standing on, or null while loading or unavailable. */
    public TopicNode node() {
        return node;
    }

    /** Direct children in the author's order. Unmodifiable, never null. */
    public List<TopicNode> children() {
        return node == null ? Collections.<TopicNode>emptyList() : node.children();
    }

    /** True when the level arrived and genuinely has nothing under it. */
    public boolean isEmpty() {
        return !loading && !unavailable && (node == null || !node.hasChildren());
    }

    /** What to show as the screen's heading: the channel, or the topic drilled into. */
    public String title() {
        return title;
    }

    /**
     * Ids of the nodes above the current one, outermost first. Empty at the root.
     * A child's own ancestry is this plus {@link #node()}'s id.
     */
    public List<String> ancestorIds() {
        return ancestorIds;
    }

    /** Titles of the path walked so far, for the breadcrumb. Unmodifiable. */
    public List<String> trail() {
        return trail;
    }

    /** True when there is a level to go back up to. */
    public boolean canGoUp() {
        return trail.size() > 1;
    }
}
