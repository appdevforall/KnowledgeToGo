/*
 * ============================================================================
 * Name        : ExportStatus.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-507. One poll of an export operation's staging progress on the box.
 *               Pure value, no Android or JSON: the Data layer maps the REST status to this.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.domain;

/**
 * A single status reading for an in-flight export operation. {@code stagedBytes} is both the
 * prepare-phase progress and the liveness heartbeat: it grows while the box stages the item.
 *
 * <p>{@link State#GONE} marks an operation the box no longer knows (its registry entry was swept),
 * which tells the caller to re-prepare rather than keep polling a dead id.
 */
public final class ExportStatus {

    public enum State { STAGING, READY, ERROR, GONE }

    private final State state;
    private final long stagedBytes;
    private final long totalBytes;
    private final String error;

    public ExportStatus(State state, long stagedBytes, long totalBytes, String error) {
        this.state = state != null ? state : State.ERROR;
        this.stagedBytes = Math.max(0L, stagedBytes);
        this.totalBytes = Math.max(0L, totalBytes);
        this.error = error;
    }

    /** The operation was swept and is unknown to the box: the caller re-prepares. */
    public static ExportStatus gone() {
        return new ExportStatus(State.GONE, 0L, 0L, null);
    }

    public State state() {
        return state;
    }

    /** Bytes staged so far (the prepare-phase progress and the liveness signal). */
    public long stagedBytes() {
        return stagedBytes;
    }

    /** The item's total size in bytes (0 when unknown). */
    public long totalBytes() {
        return totalBytes;
    }

    /** The failure detail when {@link #state} is {@link State#ERROR}, else null. */
    public String error() {
        return error;
    }
}
