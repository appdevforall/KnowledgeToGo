package org.iiab.controller.env.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.system.domain.Operation;

import org.junit.Test;

/**
 * Unit tests for {@link ServerReconcile}.
 *
 * <p>Two tables. {@code desired} is the process-scoped "should it be up?" — the case that matters is
 * the holder-class dimension (ADFA-5343): a LIVE holder (download / dashboard self-update) must leave
 * desired UP, only a STOPPED holder pulls it down. {@code intent} is the coarse direction, whose one
 * rule worth stating is that an UNKNOWN phase never provokes an action.
 */
public class ServerReconcileTest {

    private static final Operation.ExecutionClass LIVE = Operation.ExecutionClass.LIVE;
    private static final Operation.ExecutionClass STOPPED = Operation.ExecutionClass.STOPPED;

    // --- desired -----------------------------------------------------------------

    @Test
    public void desiredUpWhenUsableWantedAndNoStoppedHolder() {
        assertTrue(ServerReconcile.desired(true, true, true, LIVE));
    }

    @Test
    public void aStoppedHolderForcesDesiredDown() {
        // clone / backup / restore / install pdsm-stop the box: desired must be DOWN even if wanted on.
        assertFalse(ServerReconcile.desired(true, true, true, STOPPED));
    }

    @Test
    public void aLiveHolderLeavesDesiredUp() {
        // The Task-1 fix: a live download / dashboard self-update runs against the live server, so it
        // must NOT force the server down (the old currentHolder == NONE predicate got this wrong).
        assertTrue(ServerReconcile.desired(true, true, true, LIVE));
    }

    @Test
    public void notInstalledIsNeverDesiredUp() {
        assertFalse(ServerReconcile.desired(false, true, true, LIVE));
    }

    @Test
    public void unhealthyIsNeverDesiredUp() {
        // Installed but half-finished: the recovery dialog's world, nothing should run against it.
        assertFalse(ServerReconcile.desired(true, false, true, LIVE));
    }

    @Test
    public void userOffIsNeverDesiredUp() {
        assertFalse(ServerReconcile.desired(true, true, false, LIVE));
    }

    // --- intent ------------------------------------------------------------------

    @Test
    public void unknownPhaseAlwaysHolds() {
        assertEquals(ServerReconcile.Intent.HOLD,
                ServerReconcile.intent(true, ServerLiveness.Phase.UNKNOWN));
        assertEquals(ServerReconcile.Intent.HOLD,
                ServerReconcile.intent(false, ServerLiveness.Phase.UNKNOWN));
    }

    @Test
    public void desiredUpStartsWhenDown() {
        assertEquals(ServerReconcile.Intent.START,
                ServerReconcile.intent(true, ServerLiveness.Phase.DOWN));
    }

    @Test
    public void desiredUpWaitsWhileStarting() {
        assertEquals(ServerReconcile.Intent.WAIT,
                ServerReconcile.intent(true, ServerLiveness.Phase.STARTING));
    }

    @Test
    public void desiredUpIsNoopWhenUp() {
        assertEquals(ServerReconcile.Intent.NOOP,
                ServerReconcile.intent(true, ServerLiveness.Phase.UP));
    }

    @Test
    public void desiredDownStopsWhenUp() {
        assertEquals(ServerReconcile.Intent.STOP,
                ServerReconcile.intent(false, ServerLiveness.Phase.UP));
    }

    @Test
    public void desiredDownStopsWhileStarting() {
        // Caught mid-boot but no longer wanted (a holder just acquired): still STOP.
        assertEquals(ServerReconcile.Intent.STOP,
                ServerReconcile.intent(false, ServerLiveness.Phase.STARTING));
    }

    @Test
    public void desiredDownIsNoopWhenDown() {
        assertEquals(ServerReconcile.Intent.NOOP,
                ServerReconcile.intent(false, ServerLiveness.Phase.DOWN));
    }

    // --- ensuresUp (which intents drive a boot) ----------------------------------

    @Test
    public void startAndWaitEnsureUp() {
        // START (down) and WAIT (coming up / stuck flap) both route to the idempotent ensureServerUp.
        assertTrue(ServerReconcile.ensuresUp(ServerReconcile.Intent.START));
        assertTrue(ServerReconcile.ensuresUp(ServerReconcile.Intent.WAIT));
    }

    @Test
    public void noopStopHoldDoNotEnsureUp() {
        assertFalse(ServerReconcile.ensuresUp(ServerReconcile.Intent.NOOP));
        assertFalse(ServerReconcile.ensuresUp(ServerReconcile.Intent.STOP));
        assertFalse(ServerReconcile.ensuresUp(ServerReconcile.Intent.HOLD));
    }

    // --- shouldEnsureUp (self-restarting holder defer, ADR-5343a Phase 3B) -------

    @Test
    public void aSelfRestartingHolderSuppressesActuation() {
        // DASHBOARD's live rebuild owns the restart: even a START/WAIT intent must NOT actuate while it
        // holds — a mid-swap relaunch would fight it.
        assertFalse(ServerReconcile.shouldEnsureUp(ServerReconcile.Intent.START, true));
        assertFalse(ServerReconcile.shouldEnsureUp(ServerReconcile.Intent.WAIT, true));
    }

    @Test
    public void aNonSelfRestartingHolderActuatesNormally() {
        // No self-restarting holder (NONE / STOPPED holders): START/WAIT drive up as in Phase 2.
        assertTrue(ServerReconcile.shouldEnsureUp(ServerReconcile.Intent.START, false));
        assertTrue(ServerReconcile.shouldEnsureUp(ServerReconcile.Intent.WAIT, false));
    }

    @Test
    public void shouldEnsureUpNeverActsOnNonUpIntents() {
        // The self-restart gate only ever narrows ensuresUp; NOOP/STOP/HOLD never actuate regardless.
        assertFalse(ServerReconcile.shouldEnsureUp(ServerReconcile.Intent.NOOP, false));
        assertFalse(ServerReconcile.shouldEnsureUp(ServerReconcile.Intent.STOP, false));
        assertFalse(ServerReconcile.shouldEnsureUp(ServerReconcile.Intent.HOLD, false));
        assertFalse(ServerReconcile.shouldEnsureUp(ServerReconcile.Intent.HOLD, true));
    }
}
