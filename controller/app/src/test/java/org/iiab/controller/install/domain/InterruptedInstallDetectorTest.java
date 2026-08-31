package org.iiab.controller.install.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import org.iiab.controller.install.domain.InterruptedInstallDetector.Verdict;

/**
 * ADFA-5343 (Phase 5a): the verdict now takes two signals. The first is {@code interrupted} —
 * "a marker left by a dead process launch" ({@code InstallGuard.isInterrupted}) — not a bare
 * "marker present". A live install reads {@code isLive}, so it is never passed here as interrupted;
 * that is why the three former running/holder preconditions are gone (the token subsumed them).
 */
public class InterruptedInstallDetectorTest {

    @Test public void notInterrupted_isOk() {
        // Absent, or a live install in this process — work in progress is not damage.
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(false, false));
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(false, true));
    }

    @Test public void interruptedButServerReachable_isOk() {
        // The base boots (server responded within the timeout) -> the marker was stale over a fine
        // system (a killed module install), not damage. This is the ADFA-5330 false-Recover fix.
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(true, true));
    }

    @Test public void interruptedAndServerNotReachable_isDamaged() {
        // Killed mid-install and the base won't come up -> genuinely damaged, reinstall.
        assertEquals(Verdict.DAMAGED_REINSTALL, InterruptedInstallDetector.evaluate(true, false));
    }
}
