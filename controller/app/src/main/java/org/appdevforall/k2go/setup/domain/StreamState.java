/*
 * ============================================================================
 * Name        : StreamState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-434. One content stream's state, as the run verdict rule needs it. Pure value,
 *               no Android, no services. Part of the SetupProgressActivity decomposition
 *               (controller/docs/ADR-434-setupprogress-decomposition.md), slice 1.
 * ============================================================================
 */
package org.appdevforall.k2go.setup.domain;

public final class StreamState {
    /** A job of this type belongs to this run. */
    public final boolean session;
    /** That job has reached its terminal, fully drained. */
    public final boolean complete;
    /** Items of this type that failed (0 when there is no session). */
    public final int failed;

    public StreamState(boolean session, boolean complete, int failed) {
        this.session = session;
        this.complete = complete;
        this.failed = failed;
    }

    /** No longer blocks completion: not in this run, or finished. */
    public boolean settledForCompletion() {
        return !session || complete;
    }
}
