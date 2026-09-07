/*
 * ============================================================================
 * Name        : MapsDownloadProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394. One in-proot maps download's progress, derived from
 *               aria2's RPC fields. Pure JVM, no Android, no JSON framework.
 * ============================================================================
 */
package org.appdevforall.k2go.maps.domain;

/**
 * A snapshot of the one aria2 download the maps runrole is on, as the subordinate download bar
 * needs it: a phase, the bytes, and the two derived numbers (percent, ETA).
 *
 * <p>Pure by design: the RPC client ({@code MapsDownloadRpc}) parses aria2's JSON and hands the
 * primitives here; the rule for "what percent / what ETA / which phase" lives in one testable place,
 * not scattered in the client or the UI. aria2's own {@code status} string is the source of the
 * phase -- {@code active} / {@code paused} / {@code complete} -- so the app never invents one.
 */
public final class MapsDownloadProgress {

    /** aria2's download lifecycle, only the states the UI distinguishes. */
    public enum Phase { NONE, ACTIVE, PAUSED, COMPLETE }

    public final Phase phase;
    public final long completedBytes;
    public final long totalBytes;
    public final long speedBytesPerSec;

    private MapsDownloadProgress(Phase phase, long completed, long total, long speed) {
        this.phase = phase;
        this.completedBytes = Math.max(0, completed);
        this.totalBytes = Math.max(0, total);
        this.speedBytesPerSec = Math.max(0, speed);
    }

    /** Nothing is downloading right now -- between files, or the RPC has no active/paused job. */
    public static MapsDownloadProgress none() {
        return new MapsDownloadProgress(Phase.NONE, 0, 0, 0);
    }

    /**
     * Build from aria2's fields ({@code aria2.tellActive} / {@code tellStatus}): the {@code status}
     * string plus {@code completedLength} / {@code totalLength} / {@code downloadSpeed} in bytes.
     * An unknown or empty status is {@link Phase#NONE}.
     */
    public static MapsDownloadProgress of(String status, long completed, long total, long speed) {
        Phase p;
        if ("active".equals(status)) {
            p = Phase.ACTIVE;
        } else if ("paused".equals(status)) {
            p = Phase.PAUSED;
        } else if ("complete".equals(status)) {
            p = Phase.COMPLETE;
        } else {
            p = Phase.NONE;
        }
        return new MapsDownloadProgress(p, completed, total, speed);
    }

    /** 0..100, or -1 when the total is not known yet (aria2 is still resolving the metalink). */
    public int percent() {
        if (totalBytes <= 0) {
            return -1;
        }
        return (int) Math.min(100L, completedBytes * 100L / totalBytes);
    }

    /** Seconds left at the current rate, or -1 when there is nothing to go on (paused, or no rate). */
    public long etaSeconds() {
        if (speedBytesPerSec <= 0 || totalBytes <= completedBytes) {
            return -1L;
        }
        return (totalBytes - completedBytes) / speedBytesPerSec;
    }

    public boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    public boolean isPaused() {
        return phase == Phase.PAUSED;
    }

    /** The download finished -- the app's cue to {@code aria2.shutdown} so the runrole task returns. */
    public boolean isComplete() {
        return phase == Phase.COMPLETE;
    }

    /** Whether there is a live download to show a bar for (active or paused). */
    public boolean isRunning() {
        return phase == Phase.ACTIVE || phase == Phase.PAUSED;
    }
}
