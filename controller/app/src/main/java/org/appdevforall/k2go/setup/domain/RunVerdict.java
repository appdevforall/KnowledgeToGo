/*
 * ============================================================================
 * Name        : RunVerdict.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-434. The run verdict rule, extracted from SetupProgressActivity.render() so it
 *               is one named, unit-tested place instead of inline boolean algebra. Pure: it reads
 *               only a RunSnapshot. Slice 1 of
 *               controller/docs/ADR-434-setupprogress-decomposition.md.
 *
 *               Two completion rules by run shape: a proot-only run (no live REST content) finishes
 *               when its queue is terminal and, for a module batch, the server has settled; a run
 *               with REST content also waits for every live stream to drain. A pending Forgejo seed
 *               blocks completion in both. Once complete, the run is a success only with zero
 *               failures and no slow server restart.
 * ============================================================================
 */
package org.appdevforall.k2go.setup.domain;

public final class RunVerdict {

    public enum State { WORKING, SUCCESS, FAILURE }

    private final boolean allComplete;
    private final int failedTotal;
    private final State state;

    private RunVerdict(boolean allComplete, int failedTotal, State state) {
        this.allComplete = allComplete;
        this.failedTotal = failedTotal;
        this.state = state;
    }

    public static RunVerdict of(RunSnapshot s) {
        boolean allComplete;
        if (s.noRest && s.prootShown) {
            allComplete = s.queueTerminalNotRunning
                    && (!s.moduleShown || s.moduleServerSettled)
                    && !s.seedPendingRun;
        } else {
            allComplete = s.drained
                    && s.zim.settledForCompletion()
                    && s.books.settledForCompletion()
                    && s.kolibri.settledForCompletion()
                    && (!s.moduleShown || s.moduleServerSettled)
                    && !s.seedPendingRun;
        }
        int failedTotal = s.zim.failed + s.books.failed + s.kolibri.failed
                + s.prootFailed + (s.forgejoSeedFailed ? 1 : 0);
        State state;
        if (allComplete && failedTotal == 0 && !s.batchServerSlow) state = State.SUCCESS;
        else if (allComplete && (failedTotal > 0 || s.batchServerSlow)) state = State.FAILURE;
        else state = State.WORKING;
        return new RunVerdict(allComplete, failedTotal, state);
    }

    public boolean allComplete() { return allComplete; }
    public int failedTotal() { return failedTotal; }
    public boolean success() { return state == State.SUCCESS; }
    public boolean failure() { return state == State.FAILURE; }
    public State state() { return state; }
}
