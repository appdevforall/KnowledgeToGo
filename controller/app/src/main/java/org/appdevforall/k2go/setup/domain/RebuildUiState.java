/*
 * ============================================================================
 * Name        : RebuildUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-434. The dashboard-rebuild sub-mode's state -> view decision as a pure rule:
 *               which phase the rebuild is in and which bottom controls to show. Semantic enums only
 *               (no Android), so renderRebuild() maps the phase to the row, the status line and the
 *               controls, and this stays unit-testable on a plain JVM. Slice 5 of
 *               controller/docs/ADR-434-setupprogress-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.setup.domain;

public final class RebuildUiState {

    /** BUILDING: the rebuild is compiling. SERVER_WAIT: rebuilt, waiting for the REST core. DONE: up.
     *  ERROR: the rebuild failed, or the server never came up. */
    public enum Phase { BUILDING, SERVER_WAIT, DONE, ERROR }

    public enum Controls { REDIRECT, FINISH_SUCCESS, FINISH_FAILURE, GATED }

    public final Phase phase;
    /** Meaningful only when phase == ERROR: the rebuild itself failed (true) vs the services never
     *  came up after a successful rebuild (false). */
    public final boolean errorIsRebuildFailure;
    public final Controls controls;

    private RebuildUiState(Phase phase, boolean errorIsRebuildFailure, Controls controls) {
        this.phase = phase;
        this.errorIsRebuildFailure = errorIsRebuildFailure;
        this.controls = controls;
    }

    public static RebuildUiState from(boolean rebuiltOk, boolean rebuildFailed,
                                      boolean rebuildServerUp, boolean rebuildServerFailed,
                                      boolean redirectCancelled) {
        boolean serverWait = rebuiltOk && !rebuildServerUp && !rebuildServerFailed;
        boolean done = rebuiltOk && rebuildServerUp;
        boolean error = rebuildFailed || (rebuiltOk && rebuildServerFailed);

        Phase phase;
        if (error) phase = Phase.ERROR;
        else if (done) phase = Phase.DONE;
        else if (serverWait) phase = Phase.SERVER_WAIT;
        else phase = Phase.BUILDING;

        Controls controls;
        if (done && !redirectCancelled) controls = Controls.REDIRECT;
        else if (done) controls = Controls.FINISH_SUCCESS;      // countdown cancelled by the user
        else if (error) controls = Controls.FINISH_FAILURE;
        else controls = Controls.GATED;                          // building/waiting: the screen is the gate

        return new RebuildUiState(phase, rebuildFailed, controls);
    }
}
