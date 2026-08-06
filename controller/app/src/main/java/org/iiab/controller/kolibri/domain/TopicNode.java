/*
 * ============================================================================
 * Name        : TopicNode.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One node of a Kolibri channel's topic tree, as the picker
 *               browses it. Pure JVM, no Android (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A node in a channel's topic tree: either a topic (a folder) or a leaf resource.
 *
 * <p>This is what makes partial selection possible. A node id passed to Kolibri
 * as {@code node_ids} imports the whole subtree beneath it, so the picker selects
 * <em>subtree roots</em>, not individual leaves.
 *
 * <p>Two size figures, and the difference matters:
 * <ul>
 *   <li>{@link #ownBytes()} is the sum of this node's own files. Exact, and 0 for
 *       a topic, which has no files of its own beyond artwork.</li>
 *   <li>{@link #subtreeBytes()} is that sum over the whole subtree. Only known
 *       once the subtree has actually been walked — see {@link #hasSubtreeSize()}.
 *       Kolibri's own granular endpoint never reports bytes at all, only resource
 *       counts; the byte figures come from Studio's tree, which does carry
 *       {@code files[].file_size}.</li>
 * </ul>
 *
 * <p>{@code descendantCount} comes free from the nested-set bounds Studio
 * reports, so a row can say how large a topic is without fetching it.
 *
 * <p>Immutable; {@link #children()} is unmodifiable.
 */
public final class TopicNode {

    /** Kolibri's {@code kind} for a folder. Anything else is a resource. */
    public static final String KIND_TOPIC = "topic";

    private final String id;
    private final String title;
    private final String kind;
    private final boolean leaf;
    private final long ownBytes;
    private final long subtreeBytes;
    private final boolean subtreeSizeKnown;
    private final int descendantCount;
    private final List<TopicNode> children;

    private TopicNode(String id, String title, String kind, boolean leaf,
                      long ownBytes, long subtreeBytes, boolean subtreeSizeKnown,
                      int descendantCount, List<TopicNode> children) {
        this.id = id;
        this.title = title;
        this.kind = kind;
        this.leaf = leaf;
        this.ownBytes = ownBytes;
        this.subtreeBytes = subtreeBytes;
        this.subtreeSizeKnown = subtreeSizeKnown;
        this.descendantCount = descendantCount;
        this.children = children;
    }

    /**
     * Builds a node with a complete set of children.
     *
     * @see #of(String, String, String, boolean, long, int, List, boolean)
     */
    public static TopicNode of(String rawId, String title, String kind, boolean leaf,
                               long ownBytes, int descendantCount, List<TopicNode> children) {
        return of(rawId, title, kind, leaf, ownBytes, descendantCount, children, true);
    }

    /**
     * Builds a node.
     *
     * @param rawId            node id; the node is rejected (null returned) if unusable
     * @param children         direct children, or null for none
     * @param childrenComplete false when the source paged and more children exist.
     *                         Summing a partial set would under-count the subtree,
     *                         so the size is reported unknown instead. Studio's
     *                         tree endpoint signals this with a non-null
     *                         {@code children.more} cursor.
     * @return the node, or null when {@code rawId} is not a valid node id
     */
    public static TopicNode of(String rawId, String title, String kind, boolean leaf,
                               long ownBytes, int descendantCount, List<TopicNode> children,
                               boolean childrenComplete) {
        String id = ChannelId.normalise(rawId);
        if (id == null) {
            return null;
        }
        List<TopicNode> kids = new ArrayList<>();
        if (children != null) {
            for (TopicNode c : children) {
                if (c != null) {
                    kids.add(c);
                }
            }
        }
        long own = Math.max(0L, ownBytes);

        // A leaf's own size IS its subtree size; there is nothing below it.
        // A topic's is known only when the child set is complete AND every child
        // reported one. Either gap makes the total an under-estimate, which is
        // worse than admitting it is unknown: the caller would plan for too
        // little disk and find out mid-download.
        long total = own;
        boolean known = leaf;
        if (!leaf) {
            known = childrenComplete && !kids.isEmpty();
            if (known) {
                for (TopicNode c : kids) {
                    if (!c.hasSubtreeSize()) {
                        known = false;
                        break;
                    }
                    total += c.subtreeBytes();
                }
            }
            if (!known) {
                total = 0L;
            }
        }

        return new TopicNode(id,
                title == null ? "" : title.trim(),
                kind == null ? "" : kind.trim(),
                leaf, own, total, known,
                Math.max(0, descendantCount),
                Collections.unmodifiableList(kids));
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    /** Kolibri's content kind: {@code topic}, {@code video}, {@code exercise}... */
    public String kind() {
        return kind;
    }

    public boolean isLeaf() {
        return leaf;
    }

    /** True for a folder the user can drill into. */
    public boolean isTopic() {
        return KIND_TOPIC.equals(kind);
    }

    /** Bytes of this node's own files. Exact. */
    public long ownBytes() {
        return ownBytes;
    }

    /**
     * Bytes for the whole subtree, or 0 when not known.
     * Always check {@link #hasSubtreeSize()} first: 0 is also a legitimate size.
     */
    public long subtreeBytes() {
        return subtreeBytes;
    }

    public boolean hasSubtreeSize() {
        return subtreeSizeKnown;
    }

    /** How many nodes hang below this one. 0 when unknown or none. */
    public int descendantCount() {
        return descendantCount;
    }

    /** Direct children, in source order. Unmodifiable, never null. */
    public List<TopicNode> children() {
        return children;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof TopicNode && id.equals(((TopicNode) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "TopicNode{" + id + " '" + title + "' " + kind
                + (subtreeSizeKnown ? " " + subtreeBytes + "B" : " size?") + "}";
    }
}
