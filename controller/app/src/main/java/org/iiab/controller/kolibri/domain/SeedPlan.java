/*
 * ============================================================================
 * Name        : SeedPlan.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Domain rule: the set of Kolibri channels queued for seeding and
 *               whether it fits on the device. Pure JVM (ADFA-4954).
 * ============================================================================
 */
package org.iiab.controller.kolibri.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the device has been asked to seed, as one unit.
 *
 * <p>Holds the ordered selections plus each one's published size so the app can
 * answer "does this fit?" <em>before</em> committing to a download. That question
 * has to be answered here rather than left to Kolibri: Kolibri subtracts a
 * configurable cushion ({@code MINIMUM_DISK_SPACE}, 250 MB by default) when it
 * computes free space, so it can report zero while the filesystem still has room,
 * and it only finds out mid-transfer.
 *
 * <p>Sizes are the <b>published size of the whole channel</b>, which is what the
 * offline catalog asset carries. For a partial selection that is an
 * over-estimate — see {@link #isEstimateExact()} — so a plan that does not fit is
 * a warning, not a verdict. The exact figure for a subtree needs the box:
 * {@code POST /k2go-api/kolibri/estimate}.
 *
 * <p>Immutable. No {@code android.*}, so it is unit-testable on a plain JVM.
 */
public final class SeedPlan {

    /**
     * Head-room kept free on top of the estimate, as a percentage. Content is not
     * the only thing writing to the device while an import runs.
     */
    public static final int DEFAULT_MARGIN_PERCENT = 110;

    private final List<ChannelSelection> selections;
    private final Map<String, Long> bytesByChannel;

    private SeedPlan(List<ChannelSelection> selections, Map<String, Long> bytesByChannel) {
        this.selections = selections;
        this.bytesByChannel = bytesByChannel;
    }

    /** An empty plan: nothing queued. */
    public static SeedPlan empty() {
        return new SeedPlan(Collections.<ChannelSelection>emptyList(),
                Collections.<String, Long>emptyMap());
    }

    /**
     * Builds a plan.
     *
     * <p>Later entries for a channel already present replace the earlier one
     * rather than queueing it twice: importing the same channel concurrently only
     * creates contention on the same SQLite file. Order of first appearance is
     * kept.
     *
     * @param selections    what to seed; null or empty yields {@link #empty()}
     * @param bytesByChannel published size per channel id; a channel missing from
     *                       the map, or mapped to a non-positive value, counts as
     *                       unknown and makes the total inexact
     */
    public static SeedPlan of(List<ChannelSelection> selections,
                              Map<String, Long> bytesByChannel) {
        if (selections == null || selections.isEmpty()) {
            return empty();
        }

        LinkedHashMap<String, ChannelSelection> byChannel = new LinkedHashMap<>();
        for (ChannelSelection s : selections) {
            if (s != null) {
                byChannel.put(s.channelId(), s);
            }
        }
        if (byChannel.isEmpty()) {
            return empty();
        }

        LinkedHashMap<String, Long> sizes = new LinkedHashMap<>();
        if (bytesByChannel != null) {
            for (String channelId : byChannel.keySet()) {
                Long bytes = bytesByChannel.get(channelId);
                if (bytes != null && bytes > 0L) {
                    sizes.put(channelId, bytes);
                }
            }
        }

        return new SeedPlan(
                Collections.unmodifiableList(new ArrayList<>(byChannel.values())),
                Collections.unmodifiableMap(sizes));
    }

    /** The selections, in order, one per channel. Immutable. */
    public List<ChannelSelection> selections() {
        return selections;
    }

    public boolean isEmpty() {
        return selections.isEmpty();
    }

    public int size() {
        return selections.size();
    }

    /** Published size of a channel, or 0 when unknown. */
    public long bytesFor(String channelId) {
        Long bytes = bytesByChannel.get(channelId);
        return bytes == null ? 0L : bytes;
    }

    /** Sum of the known published sizes. Channels with no size contribute 0. */
    public long estimatedBytes() {
        long total = 0L;
        for (ChannelSelection s : selections) {
            total += bytesFor(s.channelId());
        }
        return total;
    }

    /** How many channels in the plan have no published size. */
    public int channelsWithUnknownSize() {
        int unknown = 0;
        for (ChannelSelection s : selections) {
            if (bytesFor(s.channelId()) <= 0L) {
                unknown++;
            }
        }
        return unknown;
    }

    /**
     * True when {@link #estimatedBytes()} can be trusted as a real figure: every
     * channel has a published size <em>and</em> every selection is a whole channel.
     *
     * <p>A partial selection makes the total an over-estimate, because the catalog
     * only carries whole-channel sizes. Callers should present an inexact total as
     * an upper bound, and should not refuse to start on the strength of it alone.
     */
    public boolean isEstimateExact() {
        if (selections.isEmpty()) {
            return true;
        }
        if (channelsWithUnknownSize() > 0) {
            return false;
        }
        for (ChannelSelection s : selections) {
            if (!s.isWholeChannel()) {
                return false;
            }
        }
        return true;
    }

    /** Bytes required including the default head-room. */
    public long requiredBytes() {
        return requiredBytes(DEFAULT_MARGIN_PERCENT);
    }

    /**
     * Bytes required including {@code marginPercent} head-room, e.g. 110 for ten
     * per cent extra. A margin below 100 is treated as 100: never plan to use more
     * of the disk than the content actually needs.
     */
    public long requiredBytes(int marginPercent) {
        int margin = Math.max(100, marginPercent);
        // Multiply first: dividing by 100 up front truncates the remainder before it
        // is scaled, losing up to ~99 bytes per call. No overflow risk — the whole
        // public catalog is ~775 GB (8.3e11), and 8.3e11 * 150 is 1.2e14 against a
        // long ceiling of 9.2e18.
        return estimatedBytes() * margin / 100L;
    }

    /**
     * Whether the plan fits in {@code freeBytes}.
     *
     * <p>{@code null} means "cannot tell" — either the free space is unknown or
     * the estimate is not exact — and is deliberately different from
     * {@link Boolean#FALSE}. A caller that treats "cannot tell" as "does not fit"
     * would refuse to seed a small subtree of a large channel, which is exactly
     * the case a phone needs most.
     */
    public Boolean fitsIn(Long freeBytes) {
        return fitsIn(freeBytes, DEFAULT_MARGIN_PERCENT);
    }

    /** @see #fitsIn(Long) */
    public Boolean fitsIn(Long freeBytes, int marginPercent) {
        if (isEmpty()) {
            return Boolean.TRUE;
        }
        if (freeBytes == null || freeBytes < 0L || !isEstimateExact()) {
            return null;
        }
        return freeBytes >= requiredBytes(marginPercent) ? Boolean.TRUE : Boolean.FALSE;
    }

    @Override
    public String toString() {
        return "SeedPlan{" + selections.size() + " channel(s), "
                + estimatedBytes() + " bytes"
                + (isEstimateExact() ? "" : ", inexact") + "}";
    }
}
