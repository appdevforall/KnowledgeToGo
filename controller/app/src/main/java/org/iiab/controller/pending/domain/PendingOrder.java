/*
 * ============================================================================
 * Name        : PendingOrder.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One queued (banked) content order — a single ZIM collection,
 *               book, or course channel the user asked for that has not been
 *               drained yet. Pure JVM domain entity (ADFA-5169, finding 6).
 * ============================================================================
 */
package org.iiab.controller.pending.domain;

import org.iiab.controller.system.domain.ContentType;

import java.util.Comparator;

/**
 * A single queued content order.
 *
 * <p>Immutable value object. {@code id} is the wishlist key used to cancel this one
 * order (a ZIM file id, a book id, a Kolibri channel id); {@code name} is what the
 * user reads; {@code bytes} is its size, {@code <= 0} when unknown.
 *
 * <p>Pure domain type: no Android. What each type is and how it runs lives in
 * {@link ContentType}; how the orders are stored lives in the data layer.
 */
public final class PendingOrder {

    private final ContentType type;
    private final String id;
    private final String name;
    private final long bytes;

    public PendingOrder(ContentType type, String id, String name, long bytes) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.bytes = bytes;
    }

    public ContentType type() {
        return type;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public long bytes() {
        return bytes;
    }

    /**
     * Stable display order for the pending list: grouped by content type (the enum's
     * own order — ZIM, Books, Courses), then by name (case-insensitive), then by id
     * so ties never reorder between reads. Null names sort as empty and never throw.
     */
    public static final Comparator<PendingOrder> DISPLAY_ORDER =
            Comparator.comparingInt((PendingOrder o) -> o.type == null ? Integer.MAX_VALUE : o.type.ordinal())
                    .thenComparing(o -> o.name == null ? "" : o.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(o -> o.id == null ? "" : o.id);
}
