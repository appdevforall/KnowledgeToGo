/*
 * ============================================================================
 * Name        : PickedSubtrees.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule: the set of subtree roots chosen inside one channel,
 *               kept free of nodes another pick already covers. Pure JVM
 *               (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the user picked inside one channel, kept canonical.
 *
 * <p>The rule this type exists for: <b>a node id sent to Kolibri imports its whole
 * subtree</b>. So picking "Mathematics" and then also picking "Mathematics ›
 * Fractions" does not add anything — Fractions was already coming — but it does
 * make the request wrong in two visible ways. The total would count Fractions
 * twice and over-state the download, and the request would carry an id that
 * contradicts its own parent. Neither is caught by
 * {@link ChannelSelection#ofSubtrees}, which can drop duplicates but knows nothing
 * about ancestry.
 *
 * <p>So this collection stays <em>disjoint</em> at all times, and the direction of
 * the rule follows what the user meant by their last tap:
 *
 * <ul>
 *   <li>Picking a node that an already-picked ancestor covers is a no-op — asking
 *       for part of something you already asked for whole.</li>
 *   <li>Picking a node whose descendants were already picked <b>widens</b> the
 *       choice: the descendants are dropped and the ancestor stands for them.</li>
 * </ul>
 *
 * <p>Because the members are disjoint, {@link #totalBytes()} is a plain sum with
 * no risk of double counting, and it is a <em>floor</em> whenever
 * {@link #hasUnknownSize()} is true — Studio does not report every subtree's size,
 * and a missing figure must not silently read as zero.
 *
 * <p>Immutable from the caller's point of view: every mutator returns a new
 * instance, so a view model can hold one field and never worry about aliasing.
 * No {@code android.*}, no HTTP.
 */
public final class PickedSubtrees {

    private static final PickedSubtrees EMPTY = new PickedSubtrees(
            Collections.<String, Pick>emptyMap());

    /** One picked node: its id, where it sits, and what it weighs. */
    private static final class Pick {
        final Set<String> ancestors;
        final long bytes;
        final boolean sizeKnown;

        Pick(Set<String> ancestors, long bytes, boolean sizeKnown) {
            this.ancestors = ancestors;
            this.bytes = bytes;
            this.sizeKnown = sizeKnown;
        }
    }

    /** Insertion-ordered so the request the device sends is stable and diffable. */
    private final Map<String, Pick> picks;

    private PickedSubtrees(Map<String, Pick> picks) {
        this.picks = picks;
    }

    public static PickedSubtrees empty() {
        return EMPTY;
    }

    /**
     * Adds a node, dropping anything it subsumes.
     *
     * @param rawNodeId    the node the user picked; an unusable id is ignored
     * @param rawAncestors ids of the nodes above it, in any order. The path the
     *                     picker walked to reach it — without this the ancestry
     *                     rule cannot be applied, so an empty path means "treat it
     *                     as a root".
     * @param bytes        the subtree's size, or anything when {@code sizeKnown} is
     *                     false
     * @param sizeKnown    whether {@code bytes} is a real figure
     * @return a new instance; {@code this} when nothing changed
     */
    public PickedSubtrees add(String rawNodeId, Collection<String> rawAncestors,
                             long bytes, boolean sizeKnown) {
        String id = ChannelId.normalise(rawNodeId);
        if (id == null) {
            return this;
        }
        Set<String> ancestors = normalise(rawAncestors);

        // Already covered by something picked higher up: the user is asking for a
        // slice of a cake they already ordered whole. Nothing to do.
        for (String existing : picks.keySet()) {
            if (ancestors.contains(existing)) {
                return this;
            }
        }

        LinkedHashMap<String, Pick> next = new LinkedHashMap<>(picks);
        // Widening: this node stands for any picked descendant, which must go or the
        // total counts it twice.
        java.util.Iterator<Map.Entry<String, Pick>> it = next.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Pick> e = it.next();
            if (e.getValue().ancestors.contains(id)) {
                it.remove();
            }
        }
        next.remove(id);
        next.put(id, new Pick(ancestors, Math.max(0L, bytes), sizeKnown));
        return new PickedSubtrees(Collections.unmodifiableMap(next));
    }

    /** Removes a node. Returns {@code this} when it was not picked. */
    public PickedSubtrees remove(String rawNodeId) {
        String id = ChannelId.normalise(rawNodeId);
        if (id == null || !picks.containsKey(id)) {
            return this;
        }
        LinkedHashMap<String, Pick> next = new LinkedHashMap<>(picks);
        next.remove(id);
        return next.isEmpty() ? EMPTY : new PickedSubtrees(Collections.unmodifiableMap(next));
    }

    /**
     * Whether this exact node was picked. Note it is <em>not</em> the same question
     * as {@link #covers}: a child of a picked topic is coming, but is not itself a
     * member, and a checkbox has to show the difference or the user cannot tell
     * what a tap would undo.
     */
    public boolean contains(String rawNodeId) {
        String id = ChannelId.normalise(rawNodeId);
        return id != null && picks.containsKey(id);
    }

    /**
     * Whether the node will be downloaded — either it was picked, or an ancestor
     * was. The check a row uses to show itself as "already included".
     */
    public boolean covers(String rawNodeId, Collection<String> rawAncestors) {
        String id = ChannelId.normalise(rawNodeId);
        if (id == null) {
            return false;
        }
        if (picks.containsKey(id)) {
            return true;
        }
        for (String a : normalise(rawAncestors)) {
            if (picks.containsKey(a)) {
                return true;
            }
        }
        return false;
    }

    /** The node ids to send, in the order they were picked. Unmodifiable. */
    public List<String> nodeIds() {
        return Collections.unmodifiableList(new ArrayList<>(picks.keySet()));
    }

    public int size() {
        return picks.size();
    }

    public boolean isEmpty() {
        return picks.isEmpty();
    }

    /**
     * Sum of the picked subtrees. Safe to add up because the members are disjoint;
     * a floor rather than a figure when {@link #hasUnknownSize()} is true.
     */
    public long totalBytes() {
        long total = 0L;
        for (Pick p : picks.values()) {
            if (p.sizeKnown) {
                total += p.bytes;
            }
        }
        return total;
    }

    /** True when at least one pick has no published size, so the total is a floor. */
    public boolean hasUnknownSize() {
        for (Pick p : picks.values()) {
            if (!p.sizeKnown) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns this into the request for {@code channelId}.
     *
     * @return a subtree selection, or the whole channel when nothing was picked —
     *         which is the honest reading of "no narrowing was done", and avoids
     *         ever building the {@code node_ids: []} that Kolibri reads as zero
     *         nodes
     */
    public ChannelSelection toSelection(String channelId) {
        return picks.isEmpty()
                ? ChannelSelection.wholeChannel(channelId)
                : ChannelSelection.ofSubtrees(channelId, nodeIds());
    }

    private static Set<String> normalise(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String r : raw) {
            String n = ChannelId.normalise(r);
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "PickedSubtrees{" + picks.size() + " subtree(s)"
                + (hasUnknownSize() ? ", size is a floor" : "") + "}";
    }
}
