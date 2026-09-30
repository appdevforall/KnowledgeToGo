/*
 * ============================================================================
 * Name        : SetupUiState.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-434. The setup screen's derived view state as a pure rule: the status dot tone,
 *               the status message, whether to animate the waiting ellipsis, the bottom-controls mode
 *               and whether "Run in background" shows. Semantic enums only (no Android resource ids),
 *               so the mapping to R.color/R.string and to show()/schedule/cancel stays in the Activity
 *               and this stays unit-testable on a plain JVM. Slice 3 of
 *               controller/docs/ADR-434-setupprogress-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.setup.domain;

public final class SetupUiState {

    /** The status dot tone: a calm run vs a run that is waiting/working. */
    public enum StatusTone { NEUTRAL, WAITING }

    /** The status line message (mapped to a string resource by the Activity). */
    public enum StatusMessage { STARTING, SLOW, ADDING, INSTALLING }

    /** The bottom-controls mode (mapped to show()/scheduleRedirect()/cancelRedirect() by the Activity). */
    public enum Controls { REDIRECT, FINISH_SUCCESS, FINISH_FAILURE, RUNNING }

    public final StatusTone tone;
    public final StatusMessage message;
    public final boolean animate;
    public final Controls controls;
    public final boolean runInBackgroundVisible;

    private SetupUiState(StatusTone tone, StatusMessage message, boolean animate,
                         Controls controls, boolean runInBackgroundVisible) {
        this.tone = tone;
        this.message = message;
        this.animate = animate;
        this.controls = controls;
        this.runInBackgroundVisible = runInBackgroundVisible;
    }

    public static SetupUiState from(Inputs in) {
        // Amber "working" while a module runs, its post-batch restart is pending, or the batch ended
        // with a failed module; never while the server is slow (that is a terminal failure).
        boolean amberWaiting = !in.batchServerSlow
                && (in.moduleFailed || (in.moduleFlow ? !in.batchServerUp : !in.servicesReady));
        StatusTone tone = (amberWaiting || in.batchServerSlow) ? StatusTone.WAITING : StatusTone.NEUTRAL;

        StatusMessage message;
        if (in.batchServerSlow) message = StatusMessage.SLOW;                       // could not bring services up in time
        else if (in.batchServerSettling) message = StatusMessage.STARTING;          // reconciler is (re)starting the server
        else if (in.moduleFlow && !in.batchServerUp) message = StatusMessage.INSTALLING;   // runroles in flight
        else if (in.moduleFailed) message = StatusMessage.INSTALLING;               // keep the amber install header on a failed batch
        else if (in.moduleFlow) message = StatusMessage.ADDING;                     // module done + server up
        else if (!in.servicesReady) message = in.slowByPolls ? StatusMessage.SLOW : StatusMessage.STARTING;
        else message = StatusMessage.ADDING;

        Controls controls;
        if (in.success && !in.redirectCancelled) controls = Controls.REDIRECT;
        else if (in.success) controls = Controls.FINISH_SUCCESS;                    // success, countdown cancelled by the user
        else if (in.failure) controls = Controls.FINISH_FAILURE;
        else controls = Controls.RUNNING;

        return new SetupUiState(tone, message, amberWaiting, controls, in.runInBackgroundEnabled);
    }

    /** The inputs the rule reads, gathered by the Activity from its live sources. */
    public static final class Inputs {
        boolean batchServerSlow, batchServerSettling, batchServerUp, moduleFlow, moduleFailed;
        boolean servicesReady, slowByPolls, success, failure, redirectCancelled, runInBackgroundEnabled;

        public Inputs batchServerSlow(boolean v) { this.batchServerSlow = v; return this; }
        public Inputs batchServerSettling(boolean v) { this.batchServerSettling = v; return this; }
        public Inputs batchServerUp(boolean v) { this.batchServerUp = v; return this; }
        public Inputs moduleFlow(boolean v) { this.moduleFlow = v; return this; }
        public Inputs moduleFailed(boolean v) { this.moduleFailed = v; return this; }
        public Inputs servicesReady(boolean v) { this.servicesReady = v; return this; }
        public Inputs slowByPolls(boolean v) { this.slowByPolls = v; return this; }
        public Inputs success(boolean v) { this.success = v; return this; }
        public Inputs failure(boolean v) { this.failure = v; return this; }
        public Inputs redirectCancelled(boolean v) { this.redirectCancelled = v; return this; }
        public Inputs runInBackgroundEnabled(boolean v) { this.runInBackgroundEnabled = v; return this; }
        public SetupUiState build() { return SetupUiState.from(this); }
    }
}
