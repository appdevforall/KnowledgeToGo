package org.appdevforall.k2go.env.domain;

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
 *
 * <p>ADFA-5365 adds the third: a boot and a flap are both "alive, not answering", and one threshold
 * cannot serve both. The boot cases below are keyed on whether the boot is still <em>moving</em>,
 * because a legitimate boot takes as long as the device and its content need — device-measured at
 * 37 s, which the 20 s flap grace was killing.
 */
public class EnvironmentEnsureTest {

    private static final long GRACE = 20_000L;
    private static final long BOOT_SILENCE = 60_000L;

    /** No progress signal to read — an orphan this process did not launch. */
    private static final long NO_SIGNAL = -1L;

    private static EnvironmentEnsure.Action flap(long servicesDownMs) {
        return EnvironmentEnsure.decide(true, false, false, servicesDownMs, GRACE,
                NO_SIGNAL, BOOT_SILENCE);
    }

    private static EnvironmentEnsure.Action booting(long servicesDownMs, long silentMs) {
        return EnvironmentEnsure.decide(true, false, true, servicesDownMs, GRACE,
                silentMs, BOOT_SILENCE);
    }

    // ---------------------------------------------------------------- unchanged behaviour

    @Test
    public void nothingRunningLaunches() {
        assertEquals(EnvironmentEnsure.Action.LAUNCH,
                EnvironmentEnsure.decide(false, false, false, NO_SIGNAL, GRACE,
                        NO_SIGNAL, BOOT_SILENCE));
    }

    @Test
    public void aliveAndAnsweringIsANoOp() {
        // The redundant-call case: five of the six callers fire "ensure it is up" when it already is.
        // servicesAlive short-circuits, so every clock argument is irrelevant.
        assertEquals(EnvironmentEnsure.Action.NOOP_HEALTHY,
                EnvironmentEnsure.decide(true, true, true, NO_SIGNAL, GRACE, 0L, BOOT_SILENCE));
    }

    @Test
    public void aBootingProotIsLeftToFinish() {
        // 3.5 s in, services not up yet — exactly the proot the earlier wiring killed.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, flap(3_500L));
    }

    @Test
    public void aMatureProotWhoseServiceJustDroppedWaitsForRespawn() {
        // The ADFA-5336 fix: a 2 s flap, well under the grace, so pdsm gets its ~3 s to respawn.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, flap(2_000L));
    }

    @Test
    public void anUnknownDowntimeIsNeverKilled() {
        // "Cannot confirm it is stuck" falls on the side of waiting.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, flap(NO_SIGNAL));
    }

    @Test
    public void servicesDownPastTheGraceIsAStuckEnvironmentAndIsRelaunched() {
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH, flap(120_000L));
    }

    @Test
    public void theGraceBoundaryKillsAtOrAfterIt() {
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, flap(GRACE - 1));
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH, flap(GRACE));
    }

    // ---------------------------------------------------------------- ADFA-5365

    @Test
    public void aSlowBootPastTheFlapGraceIsNotKilledWhileItIsStillTalking() {
        // THE BUG. A device-measured boot: 37 s to its services, last output 2 s ago. Down for 37 s is
        // far past the 20 s flap grace, and that is precisely what killed it at ~24 s, forever.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, booting(37_000L, 2_000L));
    }

    @Test
    public void aBootIsNotKilledHoweverLongItTakesWhileItKeepsMoving() {
        // Total boot time grows with the content installed, so it is not a thing to cap. Ten minutes
        // in and still talking is a slow device doing real work, not a stuck one.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, booting(600_000L, 5_000L));
    }

    @Test
    public void aBootThatHasGoneQuietPastTheSilenceGraceIsStuck() {
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH, booting(90_000L, 90_000L));
    }

    @Test
    public void theSilenceBoundaryKillsAtOrAfterIt() {
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, booting(999_000L, BOOT_SILENCE - 1));
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH, booting(999_000L, BOOT_SILENCE));
    }

    @Test
    public void theWorstMeasuredQuietStepIsComfortablyInsideTheSilenceGrace() {
        // The longest gap between two guest lines during a real boot was 13 s on a slow device.
        // If this ever fails, the grace is too tight for the devices we ship to.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, booting(30_000L, 13_000L));
    }

    @Test
    public void aMatureEnvironmentIsNeverJudgedOnSilence() {
        // A served environment produces no output at all — it is sitting in `tail -f /dev/null` — so
        // its silence is unbounded and meaningless. Judging it on silence would kill every healthy
        // box on the first blip. Only `booting` keeps that apart.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, false, false, 2_000L, GRACE,
                        3_600_000L, BOOT_SILENCE));
    }

    @Test
    public void aBootWithNoProgressSignalFallsBackToTheDowntimeRule() {
        // Cold start onto an orphaned proot: it looks like a boot (never seen answering) but this
        // process never launched it, so there is no stream to watch. Orphan recovery must still work.
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH, booting(120_000L, NO_SIGNAL));
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE, booting(2_000L, NO_SIGNAL));
    }

    @Test
    public void theDefaultsAreTheOnesTheActuatorsGet() {
        // Both actuators call the short form; a copy of these thresholds living anywhere else is how
        // they came to disagree in the first place.
        assertEquals(EnvironmentEnsure.Action.WAIT_BOOT_GRACE,
                EnvironmentEnsure.decide(true, false, true, 37_000L, 2_000L));
        assertEquals(EnvironmentEnsure.Action.KILL_AND_RELAUNCH,
                EnvironmentEnsure.decide(true, false, false, 120_000L, NO_SIGNAL));
    }
}
