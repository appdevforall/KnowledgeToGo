/*
 * ============================================================================
 * Name        : InstalledChannel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : A channel already on the device, and how much of it arrived.
 *               Pure JVM, no Android (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.domain;

/**
 * What the box already holds for one channel.
 *
 * <p>Kolibri imports metadata and files separately, so "the channel is there" and
 * "the content is there" are different statements — a channel can be listed with
 * every file missing. The counts come straight from the content database, which is
 * why they are two pairs rather than one flag: the picker has to be able to say
 * <em>how much</em> is missing, not only that something is.
 *
 * <p>Sizes are the ones the device sees, not the ones Studio publishes. They differ
 * for a good reason — a partially imported channel holds part of its files — and
 * conflating them is how a picker ends up offering to download what is already
 * there.
 *
 * <p>Immutable.
 */
public final class InstalledChannel {

    private final String id;
    private final String name;
    private final int version;
    private final int filesTotal;
    private final int filesAvailable;
    private final long bytesTotal;
    private final long bytesAvailable;

    private InstalledChannel(String id, String name, int version,
                             int filesTotal, int filesAvailable,
                             long bytesTotal, long bytesAvailable) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.filesTotal = filesTotal;
        this.filesAvailable = filesAvailable;
        this.bytesTotal = bytesTotal;
        this.bytesAvailable = bytesAvailable;
    }

    /**
     * @return the channel, or null when the id is not usable — a listing row we
     *         cannot key by is worse than a missing one, because it would silently
     *         fail to match anything in the catalog
     */
    public static InstalledChannel of(String rawId, String name, int version,
                                      int filesTotal, int filesAvailable,
                                      long bytesTotal, long bytesAvailable) {
        String id = ChannelId.normalise(rawId);
        if (id == null) {
            return null;
        }
        int total = Math.max(0, filesTotal);
        return new InstalledChannel(id,
                name == null ? "" : name.trim(),
                Math.max(0, version),
                total,
                // Cannot have more than exist: the pair has to stay coherent or
                // "how much is missing" goes negative.
                Math.min(total, Math.max(0, filesAvailable)),
                Math.max(0L, bytesTotal),
                Math.min(Math.max(0L, bytesTotal), Math.max(0L, bytesAvailable)));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int version() {
        return version;
    }

    public int filesTotal() {
        return filesTotal;
    }

    public int filesAvailable() {
        return filesAvailable;
    }

    /** Bytes this channel would occupy in full, as the device counts them. */
    public long bytesTotal() {
        return bytesTotal;
    }

    /** Bytes actually on disk. */
    public long bytesAvailable() {
        return bytesAvailable;
    }

    /** What is still to come. Never negative. */
    public long bytesRemaining() {
        return Math.max(0L, bytesTotal - bytesAvailable);
    }

    /**
     * Every file arrived.
     *
     * <p>A channel with no files at all is <b>not</b> complete: that is the
     * metadata-only import, where the catalog is known and nothing was downloaded.
     * Treating it as complete would hide the very content the user came for.
     */
    public boolean isComplete() {
        return filesTotal > 0 && filesAvailable >= filesTotal;
    }

    /** Listed, but nothing of it downloaded — a metadata-only import. */
    public boolean isMetadataOnly() {
        return filesAvailable == 0;
    }

    /** Some of it is here and some is not. */
    public boolean isPartial() {
        return filesAvailable > 0 && filesAvailable < filesTotal;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof InstalledChannel && id.equals(((InstalledChannel) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "InstalledChannel{" + id + " v" + version
                + " " + filesAvailable + "/" + filesTotal + " files}";
    }
}
