/*
 * ============================================================================
 * Name        : ModuleDownloadProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-394, K2GO-593. The download that a module install does
 *               through dash-node before its role (maps base maps, the Forgejo
 *               binary), as the download bar needs it. Pure JVM.
 * ============================================================================
 */
package org.appdevforall.k2go.install.domain;

/**
 * A snapshot of a module's download for the download bar. The download runs as a dash-node job, so
 * this is built from the REST poll fields ({@code RestContentClient.Listener}): a phase, a percent, a
 * speed token, the time left and the reconnect counter. The rule for "which phase" lives here, not in
 * the UI.
 */
public final class ModuleDownloadProgress {

    /**
     * The states the download bar shows. RECONNECTING is a network drop that the box rides out. HELD
     * is a stop that the app made because the network may not be used now (mobile data with no
     * consent, or Keep offline); it continues on Wi-Fi or after the user allows it.
     */
    public enum Phase { NONE, ACTIVE, PAUSED, HELD, RECONNECTING, COMPLETE }

    public final Phase phase;
    /** The module key that owns the download ("maps", "forgejo"), or null for NONE. */
    public final String module;
    /** 0..100, or -1 when not known yet. */
    public final int percent;
    /** Display token for the rate WITHOUT the per-second suffix (e.g. "3.4 MB"); the UI appends "/s". */
    public final String speed;
    /** Seconds left, or -1 when not known. */
    public final long etaSeconds;
    /** Reconnect counter for "Reconnecting n of N"; both 0 when not reconnecting. */
    public final int reconnectAttempt;
    public final int reconnectTotal;
    /** Why the download is HELD; null in the other phases. */
    public final DownloadHold.Reason holdReason;

    private ModuleDownloadProgress(Phase phase, String module, int percent, String speed, long eta,
                                   int attempt, int total, DownloadHold.Reason holdReason) {
        this.phase = phase;
        this.module = module;
        this.percent = percent < 0 ? -1 : Math.min(100, percent);
        this.speed = speed != null ? speed : "";
        this.etaSeconds = eta < 0 ? -1 : eta;
        this.reconnectAttempt = Math.max(0, attempt);
        this.reconnectTotal = Math.max(0, total);
        this.holdReason = holdReason;
    }

    /** Nothing is downloading: before the job starts, or after it ends. */
    public static ModuleDownloadProgress none() {
        return new ModuleDownloadProgress(Phase.NONE, null, -1, "", -1, 0, 0, null);
    }

    /** A live download at {@code percent} moving at {@code speed} (a display token, no "/s"). */
    public static ModuleDownloadProgress active(String module, int percent, String speed, long etaSeconds) {
        return new ModuleDownloadProgress(Phase.ACTIVE, module, percent, speed, etaSeconds, 0, 0, null);
    }

    /** The user paused the download; the partial is kept and Resume continues from it. */
    public static ModuleDownloadProgress paused(String module, int percent) {
        return new ModuleDownloadProgress(Phase.PAUSED, module, percent, "", -1, 0, 0, null);
    }

    /** The app stopped the download for {@code reason}; the partial is kept. */
    public static ModuleDownloadProgress held(String module, int percent, DownloadHold.Reason reason) {
        return new ModuleDownloadProgress(Phase.HELD, module, percent, "", -1, 0, 0, reason);
    }

    /** The box lost the network and reconnects (attempt n of total), keeping the partial. */
    public static ModuleDownloadProgress reconnecting(String module, int attempt, int total) {
        return new ModuleDownloadProgress(Phase.RECONNECTING, module, -1, "", -1, attempt, total, null);
    }

    /** The download finished. */
    public static ModuleDownloadProgress complete(String module) {
        return new ModuleDownloadProgress(Phase.COMPLETE, module, 100, "", 0, 0, 0, null);
    }

    public boolean isActive() {
        return phase == Phase.ACTIVE;
    }

    public boolean isPaused() {
        return phase == Phase.PAUSED;
    }

    public boolean isHeld() {
        return phase == Phase.HELD;
    }

    public boolean isReconnecting() {
        return phase == Phase.RECONNECTING;
    }

    public boolean isComplete() {
        return phase == Phase.COMPLETE;
    }

    /** Whether there is a download to show a bar for (active, paused, held or reconnecting). */
    public boolean isRunning() {
        return phase == Phase.ACTIVE || phase == Phase.PAUSED || phase == Phase.HELD
                || phase == Phase.RECONNECTING;
    }

    /** Whether this snapshot is the download of {@code moduleKey}. */
    public boolean isFor(String moduleKey) {
        return module != null && module.equals(moduleKey);
    }
}
