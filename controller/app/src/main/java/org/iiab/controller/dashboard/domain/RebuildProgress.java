/*
 * ============================================================================
 * Name        : RebuildProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-95 (Phase 2). Turns the dash-node rebuild log the app already polls into a
 *               determinate 0-100 bar, replacing the indeterminate spinner. Two pure functions: which
 *               RebuildPhase the log is in, and how full the bar is given how long we have been in that
 *               phase. It adds no new source of truth — the phase comes from the log's own markers, the
 *               terminal outcome stays with /rebuild/status, and the weights live in RebuildPhase. Pure
 *               JVM (no android.*), so the whole rule is unit-tested off device.
 * ============================================================================
 */
package org.iiab.controller.dashboard.domain;

/**
 * Derives rebuild progress from the log.
 *
 * <p>Usage from the presentation layer, once per poll of {@code /rebuild/log}:
 * <ol>
 *   <li>{@code phase = RebuildProgress.phaseOf(log)} — the most-advanced phase whose marker the log
 *       contains. When it differs from the previous poll, the caller records "now" as the phase's
 *       start; that keeps the wall clock (which the silent native-build stretch has no log timestamp
 *       for) in the presentation and this rule pure.</li>
 *   <li>{@code percent = RebuildProgress.percentFor(phase, now - phaseStart)} — the bar value.</li>
 * </ol>
 *
 * <p>Within a phase the bar fills <em>asymptotically</em> toward the phase's end and never reaches it
 * (fork 1): so a slow device keeps creeping instead of freezing, and the bar can never overshoot a
 * phase before that phase's real marker arrives — the next marker snaps it to the next slice. The
 * final snap to 100 belongs to {@code status=done}, not to this rule. Staging is treated as one
 * interpolated segment (fork 3); the yarn {@code [1/4]…[4/4]} sub-steps are not modelled here.
 */
public final class RebuildProgress {

    private RebuildProgress() {}

    /**
     * The most-advanced phase the log has entered, or {@link RebuildPhase#NONE} when no marker is
     * present yet (empty or still-buffering log → the caller shows an indeterminate bar). Terminal
     * outcomes are not detected here; {@code /rebuild/status} owns done/error/canceled.
     *
     * @param log the tail of {@code /var/log/dash-rebuild.log} (as returned by {@code /rebuild/log})
     */
    public static RebuildPhase phaseOf(String log) {
        if (log == null || log.isEmpty()) {
            return RebuildPhase.NONE;
        }
        // Markers are cumulative in an append-only log, so the highest-ordinal marker present is the
        // current phase. Scan newest-phase-first and return the first match. NONE (index 0, empty
        // marker) is skipped. This also ignores the transient "smoketest FAIL: ..." line the smoke
        // helper prints on a verify retry — it is not a phase marker, so it never shifts the phase.
        RebuildPhase[] phases = RebuildPhase.values();
        for (int i = phases.length - 1; i >= 1; i--) {
            if (log.contains(phases[i].marker)) {
                return phases[i];
            }
        }
        return RebuildPhase.NONE;
    }

    /**
     * The bar value (0-100) for {@code phase} after {@code elapsedInPhaseMs} in it. Monotonic in the
     * elapsed time and strictly below {@link RebuildPhase#endPercent} (asymptotic fill), so the whole
     * run climbs without overshoot or rollback. {@link RebuildPhase#NONE} and zero-width phases return
     * their start.
     *
     * @param phase             the phase from {@link #phaseOf}
     * @param elapsedInPhaseMs  wall-clock ms since the phase began (negative is treated as 0)
     */
    public static int percentFor(RebuildPhase phase, long elapsedInPhaseMs) {
        if (phase == null || phase == RebuildPhase.NONE) {
            return 0;
        }
        int width = phase.endPercent - phase.startPercent;
        if (width <= 0 || phase.medianMs <= 0L) {
            return phase.startPercent;
        }
        long elapsed = Math.max(0L, elapsedInPhaseMs);
        // Asymptotic toward the segment end: fraction = 1 - e^(-elapsed / tau), tau = median/2, so the
        // bar is ~86% through the segment at the profiled median and keeps approaching the end after
        // that. Clamp to width-1 so it stays strictly below endPercent even once e^(-elapsed/tau)
        // underflows to 0 (fraction == 1.0) at very large elapsed — the bar must never reach a phase's
        // end before that phase's real marker arrives.
        double tau = phase.medianMs / 2.0;
        double fraction = 1.0 - Math.exp(-elapsed / tau);
        int filled = Math.min(width - 1, (int) Math.floor(width * fraction));
        return phase.startPercent + filled;
    }
}
