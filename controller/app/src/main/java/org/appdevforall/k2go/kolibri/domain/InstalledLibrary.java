/*
 * ============================================================================
 * Name        : InstalledLibrary.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : What the device already holds, and what that means for a picker
 *               offering more. Pure JVM, no Android (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The channels already on the device, and the questions a picker asks about them.
 *
 * <p>Exists so the answers live in one place rather than being recomputed on each
 * row. There are three of them, and the differences matter:
 *
 * <ul>
 *   <li><b>Is it already here?</b> A complete channel should not be offered again
 *       as if it were new.</li>
 *   <li><b>How much would this actually cost?</b> A partially imported channel
 *       costs what is <em>missing</em>, not what it weighs. Quoting the published
 *       size for a channel that is already 80 % downloaded is the difference
 *       between "does not fit" and "fits easily".</li>
 *   <li><b>Is it listed but empty?</b> A metadata-only import knows the catalog and
 *       holds none of the files. It looks installed and behaves like nothing.</li>
 * </ul>
 *
 * <p>An <b>unknown</b> library — the listing could not be read, because the box is
 * off or the endpoint failed — is not the same as an <b>empty</b> one. Empty means
 * "nothing is installed", and a picker acting on that would offer everything as
 * new. {@link #isKnown()} keeps them apart, and the callers that would hide or
 * re-price a row must check it first.
 *
 * <p>Immutable.
 */
public final class InstalledLibrary {

    private static final InstalledLibrary UNKNOWN =
            new InstalledLibrary(Collections.<String, InstalledChannel>emptyMap(), false);
    private static final InstalledLibrary EMPTY =
            new InstalledLibrary(Collections.<String, InstalledChannel>emptyMap(), true);

    private final Map<String, InstalledChannel> byId;
    private final boolean known;

    private InstalledLibrary(Map<String, InstalledChannel> byId, boolean known) {
        this.byId = byId;
        this.known = known;
    }

    /** The listing could not be read. Says nothing about what is installed. */
    public static InstalledLibrary unknown() {
        return UNKNOWN;
    }

    /** The listing was read and nothing is installed. */
    public static InstalledLibrary empty() {
        return EMPTY;
    }

    /** The listing as read. Null or unusable entries are dropped. */
    public static InstalledLibrary of(List<InstalledChannel> channels) {
        if (channels == null) {
            return UNKNOWN;
        }
        LinkedHashMap<String, InstalledChannel> m = new LinkedHashMap<>();
        for (InstalledChannel c : channels) {
            if (c != null) {
                m.put(c.id(), c);
            }
        }
        return m.isEmpty() ? EMPTY : new InstalledLibrary(Collections.unmodifiableMap(m), true);
    }

    /** Whether this is an observation. False means the listing could not be read. */
    public boolean isKnown() {
        return known;
    }

    public int size() {
        return byId.size();
    }

    /** The installed record for a channel, or null when it is not on the device. */
    public InstalledChannel find(String rawChannelId) {
        String id = ChannelId.normalise(rawChannelId);
        return id == null ? null : byId.get(id);
    }

    /** Listed on the device at all, whole or not. */
    public boolean has(String rawChannelId) {
        return find(rawChannelId) != null;
    }

    /** Every file of it is on the device, so there is nothing to add. */
    public boolean isComplete(String rawChannelId) {
        InstalledChannel c = find(rawChannelId);
        return c != null && c.isComplete();
    }

    /**
     * What adding this channel would actually cost.
     *
     * @param publishedSize the catalog's figure, used when the device knows nothing
     *                      about the channel
     * @return the remaining bytes for a partial import, 0 for a complete one, and
     *         the published size when it is absent or the listing is unknown —
     *         which is the conservative answer, because over-quoting only makes the
     *         picker cautious while under-quoting fills the disk
     */
    public long costOf(String rawChannelId, long publishedSize) {
        if (!known) {
            return Math.max(0L, publishedSize);
        }
        InstalledChannel c = find(rawChannelId);
        if (c == null) {
            return Math.max(0L, publishedSize);
        }
        if (c.isComplete()) {
            return 0L;
        }
        // A metadata-only import holds no files, so the device's own total is the
        // honest figure only once it knows one; before that, fall back to the
        // catalog rather than quoting a zero that means "unknown".
        long remaining = c.bytesRemaining();
        return remaining > 0 ? remaining : Math.max(0L, publishedSize);
    }

    @Override
    public String toString() {
        return known ? "InstalledLibrary{" + byId.size() + " channels}"
                : "InstalledLibrary{unknown}";
    }
}
