/*
 * ============================================================================
 * Name        : GetTopicTreeUseCase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Business rules for browsing one level of a channel's topic tree.
 *               Pure JVM, no Android (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.domain;

import java.util.Collections;
import java.util.List;

/**
 * Resolves one level of a channel's topic tree for the picker.
 *
 * <p>Four rules, each of them something the screen would otherwise get wrong:
 *
 * <ul>
 *   <li><b>"Did not arrive" is not "is empty".</b> The repository returns null for
 *       a failed fetch, and a topic with no children is a perfectly ordinary node.
 *       Collapsing the two would have the screen tell an offline user that a
 *       channel is empty, and then let them queue nothing. {@link Result} keeps
 *       them apart, and only the unavailable case is an error.</li>
 *   <li><b>Validate before the network.</b> A blank or malformed node id never
 *       reaches the repository; it fails closed here, the way the rest of this
 *       feature validates at its boundaries.</li>
 *   <li><b>Keep the author's order.</b> Studio reports children in nested-set
 *       order, which is the sequence the channel's author arranged — unit 1 before
 *       unit 2. Unlike the flat channel catalog, this list must <em>not</em> be
 *       re-sorted by size or alphabetically: the order carries meaning, and
 *       destroying it makes a curriculum unreadable.</li>
 *   <li><b>Everything is selectable, sized or not.</b> A node whose subtree size
 *       Studio does not fully report is still a legitimate thing to queue; the
 *       screen has to say the total is a floor, which is what
 *       {@link TopicNode#hasSubtreeSize()} is for. Hiding those rows would hide
 *       real content.</li>
 * </ul>
 *
 * <p>This is the only part of the catalog that needs connectivity: trees are not
 * bundled (ADR-4954 D1). Blocking, like the repository it calls — the caller
 * chooses the thread.
 */
public final class GetTopicTreeUseCase {

    /**
     * One browsable level: the node the user is standing on and its direct
     * children. Immutable.
     *
     * <p>Three shapes, and a caller must not confuse them: {@link #isUnavailable()}
     * (the fetch failed — retry is meaningful), {@link #isEmpty()} (it arrived and
     * this topic really has nothing under it) and a normal level.
     */
    public static final class Result {

        private static final Result UNAVAILABLE = new Result(null);

        private final TopicNode node;

        private Result(TopicNode node) {
            this.node = node;
        }

        static Result unavailable() {
            return UNAVAILABLE;
        }

        static Result of(TopicNode node) {
            return node == null ? UNAVAILABLE : new Result(node);
        }

        /** True when the level could not be read at all — offline, or Studio down. */
        public boolean isUnavailable() {
            return node == null;
        }

        /** The node the level is rooted at, or null when unavailable. */
        public TopicNode node() {
            return node;
        }

        /** Direct children, in the author's order. Unmodifiable, never null. */
        public List<TopicNode> children() {
            return node == null ? Collections.<TopicNode>emptyList() : node.children();
        }

        /**
         * True when the level arrived and has nothing under it. Distinct from
         * {@link #isUnavailable()}: this one is a fact about the channel.
         */
        public boolean isEmpty() {
            return node != null && !node.hasChildren();
        }

        /** The title to put at the top of the screen, or empty. */
        public String title() {
            return node == null ? "" : node.title();
        }
    }

    private final CatalogRepository repository;

    public GetTopicTreeUseCase(CatalogRepository repository) {
        this.repository = repository;
    }

    /**
     * Reads the level rooted at {@code nodeId}.
     *
     * @param nodeId a channel's {@link Channel#rootNodeId()} to start, or any
     *               {@link TopicNode#id()} to go deeper
     * @return never null; ask the result which of its three shapes it is
     */
    public Result execute(String nodeId) {
        String id = ChannelId.normalise(nodeId);
        if (id == null) {
            // Not a network problem, but from the screen's point of view the level
            // is unreadable, and there is exactly one way to say that.
            return Result.unavailable();
        }
        return Result.of(repository.fetchTree(id));
    }
}
