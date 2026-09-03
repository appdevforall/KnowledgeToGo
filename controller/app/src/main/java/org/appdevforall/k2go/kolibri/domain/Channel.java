/*
 * ============================================================================
 * Name        : Channel.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : A Kolibri content channel as the picker needs to show it.
 *               Pure JVM, no Android (ADFA-4954).
 * ============================================================================
 */
package org.appdevforall.k2go.kolibri.domain;

/**
 * One selectable channel.
 *
 * <p>A channel is Kolibri's top-level unit and the only thing that has a
 * published size. Note what is deliberately <em>not</em> here: there is no
 * "project" grouping two language editions of the same corpus, and no "flavour".
 * No field in Kolibri or Studio provides either, so the picker cannot offer the
 * axes the ZIM picker offers.
 *
 * <p>{@code version} travels with the id because a channel is only fully
 * specified by the pair: {@code publishedSize} is the size of <em>that</em>
 * published version, and Kolibri's own content manifest treats
 * {@code (id, version)} as the compound key.
 *
 * <p>{@code publishedSize} is an upper bound on what will actually be
 * transferred, for two reasons that compound: Kolibri imports with
 * {@code renderable_only=true} by default, so formats this build cannot render
 * are skipped; and files are shared between channels by checksum, so anything
 * already on disk is not fetched again.
 *
 * <p>Immutable.
 */
public final class Channel {

    private final String id;
    private final int version;
    private final String name;
    private final String description;
    private final String author;
    private final String langCode;
    private final String langName;
    private final int totalResources;
    private final long publishedSize;
    private final String rootNodeId;

    private Channel(Builder b) {
        this.id = b.id;
        this.version = b.version;
        this.name = b.name;
        this.description = b.description;
        this.author = b.author;
        this.langCode = b.langCode;
        this.langName = b.langName;
        this.totalResources = b.totalResources;
        this.publishedSize = b.publishedSize;
        this.rootNodeId = b.rootNodeId;
    }

    /** The channel id, normalised to 32 lowercase hex. Never null. */
    public String id() {
        return id;
    }

    /** Published version. 0 when the source did not report one. */
    public int version() {
        return version;
    }

    /** Display name. Never null; empty only if the source had none. */
    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public String author() {
        return author;
    }

    /** Language code as the source reported it, e.g. {@code "es"}, {@code "pt-BR"}. */
    public String langCode() {
        return langCode;
    }

    /** Language name in its own language, e.g. {@code "Español"}. */
    public String langName() {
        return langName;
    }

    /** Resource count for the whole channel. 0 when unknown. */
    public int totalResources() {
        return totalResources;
    }

    /** Published size in bytes, an upper bound. 0 when unknown. */
    public long publishedSize() {
        return publishedSize;
    }

    /**
     * The channel's root {@code ContentNode} id, needed to browse the topic tree.
     * Studio reports it equal to the channel id for most channels, but not for
     * all, so it is carried rather than assumed.
     */
    public String rootNodeId() {
        return rootNodeId;
    }

    /** True when a size is known and can be shown or summed. */
    public boolean hasKnownSize() {
        return publishedSize > 0L;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Channel)) {
            return false;
        }
        Channel other = (Channel) o;
        return version == other.version && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode() * 31 + version;
    }

    @Override
    public String toString() {
        return "Channel{" + id + " v" + version + " '" + name + "' " + publishedSize + "B}";
    }

    public static Builder builder(String rawId) {
        return new Builder(rawId);
    }

    /**
     * Builds a {@link Channel}. The id is validated and normalised here so an
     * unusable entry never reaches the picker; {@link #build()} returns null
     * rather than throwing, because a single malformed row in a catalog of
     * hundreds should be skipped, not abort the parse.
     */
    public static final class Builder {
        private final String id;
        private int version;
        private String name = "";
        private String description = "";
        private String author = "";
        private String langCode = "";
        private String langName = "";
        private int totalResources;
        private long publishedSize;
        private String rootNodeId = "";

        private Builder(String rawId) {
            this.id = ChannelId.normalise(rawId);
        }

        public Builder version(int v) {
            this.version = Math.max(0, v);
            return this;
        }

        public Builder name(String v) {
            this.name = v == null ? "" : v.trim();
            return this;
        }

        public Builder description(String v) {
            this.description = v == null ? "" : v.trim();
            return this;
        }

        public Builder author(String v) {
            this.author = v == null ? "" : v.trim();
            return this;
        }

        public Builder language(String code, String displayName) {
            this.langCode = code == null ? "" : code.trim();
            this.langName = displayName == null ? "" : displayName.trim();
            return this;
        }

        public Builder totalResources(int v) {
            this.totalResources = Math.max(0, v);
            return this;
        }

        public Builder publishedSize(long v) {
            this.publishedSize = Math.max(0L, v);
            return this;
        }

        public Builder rootNodeId(String v) {
            String n = ChannelId.normalise(v);
            this.rootNodeId = n == null ? "" : n;
            return this;
        }

        /** The channel, or null when the id was not a usable channel id. */
        public Channel build() {
            if (id == null) {
                return null;
            }
            if (rootNodeId.isEmpty()) {
                // Studio reports root == id for most channels; fall back to that
                // rather than losing the ability to browse the tree.
                rootNodeId = id;
            }
            return new Channel(this);
        }
    }
}
