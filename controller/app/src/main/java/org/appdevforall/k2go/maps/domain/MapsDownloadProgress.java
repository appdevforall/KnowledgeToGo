/*
 * ============================================================================
 * Name        : MapsDownloadProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394. One base-map download's progress, as the subordinate
 *               download bar needs it, built from the dash-node REST poll
 *               (RestContentClient). Pure JVM, no Android, no JSON framework.
 * ============================================================================
 */
package org.appdevforall.k2go.maps.domain;

/**
 * A snapshot of the base-map download for the subordinate bar. The base maps are downloaded through
 * dash-node's durable job engine (the same path as ZIMs), so this is built from the REST poll fields
 * ({@code RestContentClient.Listener}) -- a phase, a percent, a speed token, and the reconnect counter
 * -- not from aria2's raw RPC. The rule for "which phase" lives in one testable place, not in the UI.
 */
public final class MapsDownloadProgress {

    /** The states the download bar distinguishes. RECONNECTING is a network drop the server rides out. */
    public enum Phase { NONE, ACTIVE, PAUSED, RECONNECTING, COMPLETE }

    public final Phase phase;
    /** 0..100, or -1 when not known yet (queued / no percent reported). */
    public final int percent;
    /** Display token for the rate WITHOUT the per-second suffix (e.g. "3.4 MB"); the UI appends "/s". */
    public final String speed;
    /** Reconnect counter for "Reconnecting n of N"; both 0 when not reconnecting. */
    public final int reconnectAttempt;
    public final int reconnectTotal;

    private MapsDownloadProgress(Phase phase, int percent, String speed, int attempt, int total) {
        this.phase = phase;
        this.percent = percent < 0 ? -1 : Math.min(100, percent);
        this.speed = speed != null ? speed : "";
        this.reconnectAttempt = Math.max(0, attempt);
        this.reconnectTotal = Math.max(0, total);
    }

    /** Nothing is downloading right now -- before the job starts, or after it finishes/clears. */
    public static MapsDownloadProgress none() {
        return new MapsDownloadProgress(Phase.NONE, -1, "", 0, 0);
    }

    /** A live download at {@code percent} moving at {@code speed} (a display token, no "/s"). */
    public static MapsDownloadProgress active(int percent, String speed) {
        return new MapsDownloadProgress(Phase.ACTIVE, percent, speed, 0, 0);
    }

    /** The user paused the download; the partial is kept and resume continues from it. */
    public static MapsDownloadProgress paused(int percent) {
        return new MapsDownloadProgress(Phase.PAUSED, percent, "", 0, 0);
    }

    /** The server lost the network and is reconnecting (attempt n of total), keeping the partial. */
    public static MapsDownloadProgress reconnecting(int attempt, int total) {
        return new MapsDownloadProgress(Phase.RECONNECTING, -1, "", attempt, total);
    }

    /** The download finished. */
    public static MapsDownloadProgress complete() {
        return new MapsDownloadProgress(Phase.COMPLETE, 100, "", 0, 0);
    }

    public boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    public boolean isPaused() {
        return phase == Phase.PAUSED;
    }

    public boolean isReconnecting() {
        return phase == Phase.RECONNECTING;
    }

    public boolean isComplete() {
        return phase == Phase.COMPLETE;
    }

    /** Whether there is a live download to show a bar for (active, paused, or reconnecting). */
    public boolean isRunning() {
        return phase == Phase.ACTIVE || phase == Phase.PAUSED || phase == Phase.RECONNECTING;
    }
}
