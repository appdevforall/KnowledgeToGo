package org.iiab.controller.env.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link EnvironmentEnsure}.
 *
 * <p>The stakes are the same lopsided ones as the matcher's: the wrong verdict here either stacks a
 * second proot over a live one or SIGKILLs one that pdsm would have healed. Two cases anchor the
 * decision: a proot 3.5 s into its own boot must be left alone ({@link #aBootingProotIsLeftToFinish},
 * {@link #anUnknownDowntimeIsNeverKilled}), and — the ADFA-5336 regression this delta fixes — a mature
 * proot whose dash-node just blipped must WAIT for pdsm's respawn, not be killed
 * ({@link #aMatureProotWhoseServiceJustDroppedWaitsForRespawn}).
 */
public class EnvironmentEnsureTest {

    private static final long GRACE = 20_000L;

    @Test
    public void nothingRunningLaunches() {
        assertEquals(EnvironmentEnsure.Action.LAUNCH,
                EnvironmentEnsure.decide(false, false, -1L, GRACE));
    }

    @Test
    public void aliveAndAnsweringIsANoOp() {
        // The redundant-call case: five of the six callers fire "ensure it is up" when it already is.
        // servicesAlive short-circuits, so the downtime argument is irrelevant.
        assertEquals(EnvironmentEnsure.Action.NOOP_HEALTHY,
                EnvironmentEnsure.decide(true, true, -1L, GRACE));
    }

    @Test
    public void aBootingProotIsLeftToFinish() {
        // 3.5 s in, services not up yet — services have been down since the proot started, which is
        // exactly the proot the earlier wiring killed. Under the grace → never touch it.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, false, 3_500L, GRACE));
    }

    @Test
    public void aMatureProotWhoseServiceJustDroppedWaitsForRespawn() {
        // The ADFA-5336 fix: a long-lived (mature) proot whose dash-node just died. Keyed on proot age
        // this was an instant KILL_AND_RELAUNCH (age >> grace); keyed on SERVICE downtime it is a 2 s
        // flap, well under the grace, so pdsm gets its ~3 s to respawn it. WAIT, do not kill.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, false, 2_000L, GRACE));
    }

    @Test
    public void anUnknownDowntimeIsNeverKilled() {
        // A stale or never-observed snapshot reports downtime < 0: we cannot prove the services have
        // stayed down, so we must not kill — "cannot confirm it is stuck" falls on the side of waiting.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, false, -1L, GRACE));
    }

    @Test
    public void servicesDownPastTheGraceIsAStuckEnvironmentAndIsRelaunched() {
        // Alive, services down far past the grace — pdsm could not bring them back. A proot cannot be
        // re-entered, so recovery is to end it and bring up a fresh one.
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH,
                EnvironmentEnsure.decide(true, false, 120_000L, GRACE));
    }

    @Test
    public void theGraceBoundaryKillsAtOrAfterIt() {
        // Just under the grace waits; at the grace the services are considered stuck.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, false, GRACE - 1, GRACE));
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH,
                EnvironmentEnsure.decide(true, false, GRACE, GRACE));
    }
}
