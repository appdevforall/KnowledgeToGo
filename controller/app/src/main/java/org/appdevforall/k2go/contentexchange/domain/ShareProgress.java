/*
 * ============================================================================
 * Name        : ShareProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-577. The sender's view of a share: what the receiver reported and how the
 *               session ended. Pure JVM, so the "is it done, how far" rules are unit-tested.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

import java.util.Collections;
import java.util.List;

/** One poll of {@code GET /v1/share/{id}/progress}, as the sender's screen needs it. */
public final class ShareProgress {

    /** OPEN: the session lives. COMPLETED / CANCELLED / EXPIRED: how it ended. GONE: it ended, but
     *  the box is too old to say how (conex-core before 0.4.2). */
    public enum State { OPEN, COMPLETED, CANCELLED, EXPIRED, GONE }

    /** One item the receiver reported, or one it got over the HTTP fetch (bytes unknown, -1). */
    public static final class Item {
        public final long movedBytes;
        public final long totalBytes;
        public final boolean delivered;

        public Item(long movedBytes, long totalBytes, boolean delivered) {
            this.movedBytes = movedBytes;
            this.totalBytes = totalBytes;
            this.delivered = delivered;
        }
    }

    public final State state;
    private final List<Item> items;

    public ShareProgress(State state, List<Item> items) {
        this.state = state;
        this.items = items != null ? Collections.unmodifiableList(items) : Collections.emptyList();
    }

    public boolean isEnded() {
        return state != State.OPEN;
    }

    /** The receiver has started: it reported progress or got an item. */
    public boolean hasActivity() {
        return !items.isEmpty();
    }

    public int deliveredCount() {
        int n = 0;
        for (Item item : items) {
            if (item.delivered) {
                n++;
            }
        }
        return n;
    }

    /** Bytes the receiver has, counting a delivered item as whole. */
    public long movedBytes() {
        long moved = 0;
        for (Item item : items) {
            long whole = Math.max(item.totalBytes, 0);
            moved += item.delivered && whole > 0 ? whole : Math.max(item.movedBytes, 0);
        }
        return moved;
    }

    /**
     * Overall percent against the selection size the sender knows. The bytes count when the
     * receiver reports them; otherwise (an HTTP receiver) the delivered item count carries it.
     */
    public int percent(long selectionBytes, int itemCount) {
        if (itemCount > 0 && deliveredCount() >= itemCount) {
            return 100;
        }
        long moved = movedBytes();
        if (selectionBytes > 0 && moved > 0) {
            return (int) Math.min(99, moved * 100 / selectionBytes);
        }
        return itemCount > 0 ? Math.min(99, deliveredCount() * 100 / itemCount) : 0;
    }
}
