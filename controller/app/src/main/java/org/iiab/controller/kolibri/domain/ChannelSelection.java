/*
 * ============================================================================
 * Name        : ChannelSelection.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule: what the user asked to seed from one Kolibri
 *               channel — the whole thing, or named subtrees. Pure JVM
 *               (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One channel the device should seed, optionally narrowed to a set of subtrees.
 *
 * <p>The distinction this type exists to protect is a real trap in Kolibri's API:
 *
 * <ul>
 *   <li><b>No node ids</b> means <em>the whole channel</em>.</li>
 *   <li><b>An empty node id list</b> means <em>nothing at all</em>. Kolibri's
 *       importer treats {@code node_ids: []} as "select zero nodes", finishes
 *       successfully and transfers no bytes.</li>
 * </ul>
 *
 * <p>Those two are one keystroke apart and fail silently, so the difference is
 * encoded in the type rather than left to each call site: {@link #wholeChannel}
 * builds the first, {@link #ofSubtrees} the second, and {@code ofSubtrees}
 * refuses to produce an empty selection at all.
 *
 * <p>Instances are immutable and always canonical: ids are normalised on the way
 * in, invalid ones rejected, duplicates dropped, insertion order kept so the
 * request is stable and diffable.
 *
 * <p>No {@code android.*} and no HTTP, so it is unit-testable on a plain JVM.
 */
public final class ChannelSelection {

    private final String channelId;
    private final List<String> nodeIds;

    private ChannelSelection(String channelId, List<String> nodeIds) {
        this.channelId = channelId;
        this.nodeIds = nodeIds;
    }

    /**
     * The entire channel.
     *
     * @throws IllegalArgumentException if the channel id is not usable
     */
    public static ChannelSelection wholeChannel(String rawChannelId) {
        return new ChannelSelection(requireChannelId(rawChannelId),
                Collections.<String>emptyList());
    }

    /**
     * The named subtrees of a channel. Each node id expands to its descendants on
     * Kolibri's side, so a topic id pulls in everything under it.
     *
     * @throws IllegalArgumentException if the channel id is not usable, if
     *         {@code rawNodeIds} is null or empty, or if none of the node ids
     *         survive validation — an empty result would silently download
     *         nothing, so it is rejected here instead
     */
    public static ChannelSelection ofSubtrees(String rawChannelId, List<String> rawNodeIds) {
        String id = requireChannelId(rawChannelId);

        if (rawNodeIds == null || rawNodeIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "empty subtree selection for channel " + id
                            + "; use wholeChannel() to seed everything");
        }

        // LinkedHashSet: drop duplicates, keep the order the user picked.
        LinkedHashSet<String> canonical = new LinkedHashSet<>();
        for (String raw : rawNodeIds) {
            String node = ChannelId.normalise(raw);
            if (node != null) {
                canonical.add(node);
            }
        }
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "no valid node id among " + rawNodeIds.size()
                            + " entries for channel " + id);
        }

        return new ChannelSelection(id,
                Collections.unmodifiableList(new ArrayList<>(canonical)));
    }

    private static String requireChannelId(String raw) {
        String id = ChannelId.normalise(raw);
        if (id != null) {
            return id;
        }
        if (ChannelId.looksLikeToken(raw)) {
            throw new IllegalArgumentException(
                    "'" + raw + "' is a channel token, not an id; resolve it first");
        }
        throw new IllegalArgumentException("invalid channel id: " + raw);
    }

    /** Canonical 32-hex channel id. */
    public String channelId() {
        return channelId;
    }

    /** Canonical node ids, empty when the whole channel was requested. Immutable. */
    public List<String> nodeIds() {
        return nodeIds;
    }

    /** True when nothing was narrowed down and the whole channel should come. */
    public boolean isWholeChannel() {
        return nodeIds.isEmpty();
    }

    /**
     * Whether to ask Kolibri for every topic thumbnail in the channel.
     *
     * <p>Only worth it for a partial selection: without it the topics the user did
     * not pick have no artwork and the browsing screen looks broken. A whole
     * channel already brings them, so asking again is pure extra download.
     */
    public boolean wantsAllThumbnails() {
        return !isWholeChannel();
    }

    @Override
    public String toString() {
        return isWholeChannel()
                ? "ChannelSelection{" + channelId + ", whole channel}"
                : "ChannelSelection{" + channelId + ", " + nodeIds.size() + " subtree(s)}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChannelSelection)) {
            return false;
        }
        ChannelSelection other = (ChannelSelection) o;
        return channelId.equals(other.channelId) && nodeIds.equals(other.nodeIds);
    }

    @Override
    public int hashCode() {
        return 31 * channelId.hashCode() + nodeIds.hashCode();
    }
}
