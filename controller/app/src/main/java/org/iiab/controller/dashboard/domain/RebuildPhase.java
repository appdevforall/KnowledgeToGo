/*
 * ============================================================================
 * Name        : RebuildPhase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-95 (Phase 2). The ordered phases of a dash-node self-rebuild, each with the log
 *               marker that announces it and its share of a 0-100 progress bar. The weights and median
 *               durations come from on-device profiling of tools/rebuild-dashboard.sh (staging/yarn
 *               install dominates at ~79%); they are a static MODEL — like the module-size table — not a
 *               live fact, so the determinate bar can be derived from the rebuild log the app already
 *               polls, with no new server signal. Pure JVM (no android.*), unit-tested off device.
 * ============================================================================
 */
package org.iiab.controller.dashboard.domain;

/**
 * A phase of {@code tools/rebuild-dashboard.sh}, in run order, carrying:
 * <ul>
 *   <li>{@link #marker} — a substring the script's own {@code log()} line prints when the phase
 *       begins (e.g. {@code "staging build"}). {@link RebuildProgress#phaseOf} matches on it.</li>
 *   <li>{@link #startPercent}/{@link #endPercent} — the phase's slice of the 0-100 bar. Boundaries are
 *       cumulative and non-overlapping, so the whole run climbs monotonically.</li>
 *   <li>{@link #medianMs} — the profiled median wall-clock duration, used only to set the
 *       interpolation rate <em>inside</em> the phase (see {@link RebuildProgress#percentFor}).</li>
 * </ul>
 *
 * <p>Terminal outcomes (done / error / canceled) are intentionally absent: they are owned by the
 * {@code /rebuild/status} endpoint the presentation also polls (one source per fact). This enum only
 * describes the <em>running</em> phases, so {@link #FINALIZE} asymptotes toward 100 and the final snap
 * to 100 is {@code status=done}, not a log line.
 *
 * <p>Weights (profile, core-only): git 1, staging 79, smoke 2, promote 1, restart 4, verify 1,
 * finalize 12 = 100.
 */
public enum RebuildPhase {
    /** No recognized marker yet (empty/partial log): the bar is indeterminate. */
    NONE("", 0, 0, 0L),
    GIT("rebuild start", 0, 1, 2_000L),
    STAGING("staging build", 1, 80, 146_000L),
    SMOKE("smoke test staged build", 80, 82, 3_000L),
    PROMOTE("promoting", 82, 83, 2_000L),
    RESTART("restart dash-node", 83, 87, 8_000L),
    VERIFY("verifying live", 87, 88, 2_000L),
    FINALIZE("finalizing", 88, 100, 19_000L);

    final String marker;
    final int startPercent;
    final int endPercent;
    final long medianMs;

    RebuildPhase(String marker, int startPercent, int endPercent, long medianMs) {
        this.marker = marker;
        this.startPercent = startPercent;
        this.endPercent = endPercent;
        this.medianMs = medianMs;
    }

    /** The phase's lower bound on the 0-100 bar (its slice begins here). */
    public int startPercent() { return startPercent; }

    /** The phase's upper bound on the 0-100 bar (the next phase begins here). */
    public int endPercent() { return endPercent; }
}
