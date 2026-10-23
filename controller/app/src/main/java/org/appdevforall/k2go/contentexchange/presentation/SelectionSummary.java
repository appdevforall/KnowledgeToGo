package org.appdevforall.k2go.contentexchange.presentation;

/**
 * The current multi-select summary the bottom action bar reads: how many items are
 * selected and their combined size. Immutable. Selection itself lives in the
 * {@link ContentManagerViewModel} (transient UI state, not persisted and not static,
 * per the "no new shared mutable static state" rule).
 */
public final class SelectionSummary {

    public final int count;
    public final long bytes;

    public SelectionSummary(int count, long bytes) {
        this.count = count;
        this.bytes = bytes;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public static SelectionSummary empty() {
        return new SelectionSummary(0, 0L);
    }
}
