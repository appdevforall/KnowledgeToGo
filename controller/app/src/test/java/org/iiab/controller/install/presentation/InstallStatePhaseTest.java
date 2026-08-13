package org.iiab.controller.install.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.iiab.controller.install.presentation.InstallState.Phase;
import org.junit.Test;

/**
 * Unit tests for the phases ADFA-5119 adds and, more to the point, for how the two predicates
 * classify them. Pure JVM.
 *
 * <p>{@code isRunning()} is read by twenty-seven call sites, and one of them decides whether to
 * offer the user a reinstall. Getting a new phase on the wrong side of it is not a cosmetic
 * mistake, so both sides are pinned here rather than left to the reader of the enum.
 */
public class InstallStatePhaseTest {

    // ---- the one that would have been a disaster ----------------------------

    /**
     * PAUSED must count as running. `LibraryActivity`'s recovery predicate reads
     * "marker set AND not running" as "a proot install was killed" and shows the damaged-system
     * dialog; the boot gate's own `installing` flag reads the same method. If a deliberate pause
     * left this false, pausing a download would offer to reinstall the system and lift the gate
     * onto nothing.
     */
    @Test
    public void pausedCountsAsRunningBecauseTheRecoveryPredicateReadsIt() {
        assertTrue(InstallState.paused(20).isRunning());
        assertFalse("a pause has not finished", InstallState.paused(20).isTerminal());
    }

    /**
     * CANCELLED must count as terminal. The gate lifts on a terminal, so leaving it out would hold
     * the gate on a transfer that no longer exists — the trap this ticket exists to close.
     */
    @Test
    public void cancelledIsTerminalSoTheGateLifts() {
        assertTrue(InstallState.cancelled().isTerminal());
        assertFalse(InstallState.cancelled().isRunning());
    }

    // ---- the two are not the same thing ------------------------------------

    /**
     * The distinction is the reason both exist: a pause keeps the partial file, its control file
     * and the tier and wishlist decision. A cancellation discards all of them.
     */
    @Test
    public void pausedAndCancelledAreDistinct() {
        assertTrue(InstallState.paused(20).isPaused());
        assertFalse(InstallState.cancelled().isPaused());
        assertFalse(InstallState.downloading(20, "5MiB/s").isPaused());
        assertEquals(Phase.PAUSED, InstallState.paused(20).phase);
        assertEquals(Phase.CANCELLED, InstallState.cancelled().phase);
    }

    /** A cancellation is not a failure: one is what the user chose, the other is what happened. */
    @Test
    public void aCancellationIsNotAFailure() {
        assertEquals(Phase.CANCELLED, InstallState.cancelled().phase);
        assertEquals(Phase.FAILED, InstallState.failed("boom").phase);
    }

    // ---- what a paused state carries, and what it must not ------------------

    /**
     * A pause keeps the percentage so the bar holds its position, and drops the rate and the
     * estimate. Nothing is moving, so both would be figures that are no longer true — and the
     * screen's own rule is that a line with two things uses the two-column row.
     */
    @Test
    public void aPausedStateKeepsThePercentAndNothingElse() {
        InstallState p = InstallState.paused(37);
        assertEquals(37, p.percent);
        assertTrue("no rate while nothing moves", p.speed.isEmpty());
        assertTrue("no estimate while nothing moves", p.eta.isEmpty());
    }

    // ---- the predicates still agree on everything that existed before ------

    @Test
    public void theOlderPhasesAreClassifiedExactlyAsBefore() {
        assertTrue(InstallState.downloading(1, "").isRunning());
        assertTrue(InstallState.verifying(1, "", "").isRunning());
        assertTrue(InstallState.extracting(1, "").isRunning());
        assertTrue(InstallState.provisioning("").isRunning());
        assertTrue(InstallState.success().isTerminal());
        assertTrue(InstallState.failed("x").isTerminal());
        assertFalse(InstallState.idle().isRunning());
        assertFalse(InstallState.idle().isTerminal());
    }

    /** Every phase is on exactly one side, or on neither — never on both. */
    @Test
    public void noPhaseIsBothRunningAndTerminal() {
        for (Phase ph : Phase.values()) {
            InstallState s = of(ph);
            assertFalse(ph.name(), s.isRunning() && s.isTerminal());
        }
    }

    /** IDLE is the only phase that is neither, and it should stay that way. */
    @Test
    public void idleIsTheOnlyPhaseThatIsNeither() {
        for (Phase ph : Phase.values()) {
            InstallState s = of(ph);
            boolean neither = !s.isRunning() && !s.isTerminal();
            assertEquals(ph.name(), ph == Phase.IDLE, neither);
        }
    }

    private static InstallState of(Phase ph) {
        switch (ph) {
            case DOWNLOADING:  return InstallState.downloading(1, "");
            case VERIFYING:    return InstallState.verifying(1, "", "");
            case EXTRACTING:   return InstallState.extracting(1, "");
            case PROVISIONING: return InstallState.provisioning("");
            case SUCCESS:      return InstallState.success();
            case FAILED:       return InstallState.failed("x");
            case PAUSED:       return InstallState.paused(1);
            case CANCELLED:    return InstallState.cancelled();
            case IDLE:
            default:           return InstallState.idle();
        }
    }
}
