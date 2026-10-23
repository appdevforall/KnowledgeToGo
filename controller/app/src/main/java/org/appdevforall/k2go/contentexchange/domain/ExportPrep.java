/*
 * ============================================================================
 * Name        : ExportPrep.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-507. The result of preparing one export item: the long-operation id
 *               plus whether the box already has it staged. Pure value, no Android or JSON.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

/**
 * The outcome of {@link ContentExportSource#prepare}: the box allocated an export operation and
 * either started staging it ({@code ready == false}) or has it available to fetch at once
 * ({@code ready == true}, e.g. a direct stream with nothing to stage).
 */
public final class ExportPrep {

    private final String opId;
    private final boolean ready;
    private final long totalBytes;

    public ExportPrep(String opId, boolean ready, long totalBytes) {
        this.opId = opId != null ? opId : "";
        this.ready = ready;
        this.totalBytes = Math.max(0L, totalBytes);
    }

    /** The operation id to poll ({@link ContentExportSource#status}) and fetch. */
    public String opId() {
        return opId;
    }

    /** True when the item needs no staging and can be fetched immediately. */
    public boolean ready() {
        return ready;
    }

    /** The item's total size in bytes, as the box reports it (0 when unknown). */
    public long totalBytes() {
        return totalBytes;
    }
}
