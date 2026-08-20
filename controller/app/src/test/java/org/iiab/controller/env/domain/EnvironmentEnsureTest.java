package org.iiab.controller.env.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link EnvironmentEnsure}.
 *
 * <p>The stakes are the same lopsided ones as the matcher's: the wrong verdict here either stacks a
 * second proot over a live one or SIGKILLs one mid-boot. The single case that got the first attempt
 * reverted — a proot killed 3.5 s into its own start — is {@link #aYoungUnansweringProotIsLeftToBoot}
 * and {@link #anUnknownAgeIsNeverKilled}.
 */
public class EnvironmentEnsureTest {

    private static final long GRACE = 20_000L;

    @Test
    public void nothingRunningLaunches() {
        assertEquals(EnvironmentEnsure.Action.LAUNCH,
                EnvironmentEnsure.decide(false, -1L, false, GRACE));
    }

    @Test
    public void aliveAndAnsweringIsANoOp() {
        // The redundant-call case: five of the six callers fire "ensure it is up" when it already is.
        assertEquals(EnvironmentEnsure.Action.NOOP_HEALTHY,
                EnvironmentEnsure.decide(true, 90_000L, true, GRACE));
    }

    @Test
    public void aYoungUnansweringProotIsLeftToBoot() {
        // 3.5 s in, services not up yet — exactly the proot the earlier wiring killed. Never touch it.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, 3_500L, false, GRACE));
    }

    @Test
    public void anUnknownAgeIsNeverKilled() {
        // If /proc/<pid>/stat could not be read, we cannot prove the proot is past its boot, so we
        // must not kill it — "cannot confirm it is old" falls on the side of waiting.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, -1L, false, GRACE));
    }

    @Test
    public void anOldUnansweringProotIsAStuckOrphanAndIsRelaunched() {
        // Alive, services down, well past the grace — pdsm stopped it or it hung. A proot cannot be
        // re-entered, so recovery is to end it and bring up a fresh one.
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH,
                EnvironmentEnsure.decide(true, 120_000L, false, GRACE));
    }

    @Test
    public void theGraceBoundaryKillsAtOrAfterIt() {
        // Just under the grace waits; at the grace it is considered booted.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, GRACE - 1, false, GRACE));
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH,
                EnvironmentEnsure.decide(true, GRACE, false, GRACE));
    }
}
