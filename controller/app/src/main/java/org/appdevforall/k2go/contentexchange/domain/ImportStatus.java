/*
 * ============================================================================
 * Name        : ImportStatus.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. One poll of an import long operation's progress on the box. Pure value,
 *               no Android or JSON: the Data layer maps the REST status to this.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

/**
 * A single status reading for an in-flight import operation. The import ingests an already-staged
 * item on the box (a Kolibri channel import takes 35 to 141 s), so the app polls this instead of
 * blocking one long request (which times out at the relay).
 *
 * <p>{@code movedBytes} is the server-side progress and the liveness heartbeat. {@link State#GONE}
 * marks an operation the box no longer knows (its registry entry was swept), which tells the caller
 * to re-start rather than keep polling a dead id. {@link #noSpace()} is set when the box reported a
 * structural out-of-space code, so the caller stops the run with a space message.
 */
public final class ImportStatus {

    public enum State { RUNNING, DONE, ERROR, GONE }

    private final State state;
    private final long movedBytes;
    private final long totalBytes;
    private final String error;
    private final boolean noSpace;

    public ImportStatus(State state, long movedBytes, long totalBytes, String error, boolean noSpace) {
        this.state = state != null ? state : State.ERROR;
        this.movedBytes = Math.max(0L, movedBytes);
        this.totalBytes = Math.max(0L, totalBytes);
        this.error = error;
        this.noSpace = noSpace;
    }

    /** The operation was swept and is unknown to the box: the caller re-starts. */
    public static ImportStatus gone() {
        return new ImportStatus(State.GONE, 0L, 0L, null, false);
    }

    public State state() {
        return state;
    }

    /** Bytes moved so far on the box (server-side progress and the liveness signal). */
    public long movedBytes() {
        return movedBytes;
    }

    /** The item's total size in bytes (0 when unknown). */
    public long totalBytes() {
        return totalBytes;
    }

    /** The failure detail when {@link #state} is {@link State#ERROR}, else null. */
    public String error() {
        return error;
    }

    /** True when the failure is a structural out-of-space: the run stops with a space message. */
    public boolean noSpace() {
        return noSpace;
    }
}
