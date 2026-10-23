package org.appdevforall.k2go.contentexchange.domain;

import java.util.Collections;
import java.util.List;

/**
 * The result of {@code POST /v1/export/plan}: the items the core can export (with exact
 * sizes), the ones it skipped (unknown app / not found), and the total bytes. Moving no
 * payload, so the front shows the total and the skip list before any byte moves.
 *
 * <p>Partial is first-class (ADR): a skipped item is reported, never fatal. Immutable.
 */
public final class ExportPlan {

    private final List<ExportItem> items;
    private final List<String> skipped;
    private final long totalBytes;

    public ExportPlan(List<ExportItem> items, List<String> skipped, long totalBytes) {
        this.items = Collections.unmodifiableList(items);
        this.skipped = Collections.unmodifiableList(skipped);
        this.totalBytes = totalBytes;
    }

    /** The items the core can export, in the order requested. */
    public List<ExportItem> items() {
        return items;
    }

    /** Human-readable reasons for each item the core could not plan (may be empty). */
    public List<String> skipped() {
        return skipped;
    }

    public long totalBytes() {
        return totalBytes;
    }
}
