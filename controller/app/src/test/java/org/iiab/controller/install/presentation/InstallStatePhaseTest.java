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

    /**
     * SOFTFAILED must count as running for the same reason PAUSED does, and for one more: it is
     * reached from a FAILURE, so it is the state most likely to be classified as an ending by
     * reflex. If it were terminal the gate would lift on a dropped transfer and land on a library
     * with no system — which is the exact dead end this ticket exists to close, arrived at by the
     * scenic route.
     */
    @Test
    public void softFailedIsRunningBecauseTheOperationHasNotEnded() {
        assertTrue(InstallState.softFailed(43, "Connection lost").isRunning());
        assertFalse("a stop that can continue has not finished",
                InstallState.softFailed(43, "Connection lost").isTerminal());
    }

    /** The distinction from FAILED is the whole point: one can continue, the other cannot. */
    @Test
    public void softFailedAndFailedAreNotTheSameEnding() {
        assertEquals(Phase.SOFTFAILED, InstallState.softFailed(1, "x").phase);
        assertEquals(Phase.FAILED, InstallState.failed("x").phase);
        assertFalse(InstallState.failed("x").isSoftFailed());
        assertTrue(InstallState.failed("x").isTerminal());
    }

    /**
     * Both held states answer isHeld(), and that is what the controls read. A pause and a dropped
     * transfer resume through the same call, so a screen asking "can this continue?" must not have
     * to name both phases — the day a third one appears, whoever adds it would be relying on every
     * call site remembering to grow.
     */
    @Test
    public void heldMeansStoppedAndAbleToContinue() {
        assertTrue(InstallState.paused(20).isHeld());
        assertTrue(InstallState.softFailed(20, "x").isHeld());
        assertFalse(InstallState.downloading(20, "5MiB/s").isHeld());
        assertFalse(InstallState.failed("x").isHeld());
        assertFalse(InstallState.cancelled().isHeld());
    }

    /** A stop nobody chose has to say so; a pause does not, because the user did it. */
    @Test
    public void aSoftFailureCarriesItsReasonAndItsPosition() {
        InstallState s = InstallState.softFailed(43, "Connection lost");
        assertEquals(43, s.percent);
        assertEquals("Connection lost", s.message);
        assertTrue("no rate while nothing moves", s.speed.isEmpty());
        assertTrue("no estimate while nothing moves", s.eta.isEmpty());
        assertTrue("a pause needs no explanation", InstallState.paused(43).message.isEmpty());
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
            case SOFTFAILED:   return InstallState.softFailed(1, "x");
            case IDLE:
            default:           return InstallState.idle();
        }
    }
}
