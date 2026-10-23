package org.appdevforall.k2go.contentexchange.domain;

/**
 * One item the core planned for export: its address, a display title, and the exact byte
 * size the plan returned. The size is the progress weight (the export stream is chunked, so
 * there is no Content-Length to measure against). Immutable. Pure domain type.
 */
public final class ExportItem {

    private final ItemRef ref;
    private final String title;
    private final long bytes;

    public ExportItem(ItemRef ref, String title, long bytes) {
        this.ref = ref;
        this.title = title;
        this.bytes = bytes;
    }

    public ItemRef ref() {
        return ref;
    }

    public String title() {
        return title;
    }

    /** Exact size from the plan; {@code 0} when the core could not size it. */
    public long bytes() {
        return bytes;
    }
}
