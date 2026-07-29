package org.iiab.controller.install.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import org.iiab.controller.install.domain.InterruptedInstallDetector.Verdict;

public class InterruptedInstallDetectorTest {

    @Test public void noMarker_isOk() {
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(false, false));
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(false, true));
    }

    @Test public void markerButServerReachable_isOk() {
        // System boots (server responded within the timeout) -> marker is stale, not damage.
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(true, true));
    }

    @Test public void markerAndServerNotReachable_isDamaged() {
        // Killed mid-install: never finished and the server won't come up -> reinstall.
        assertEquals(Verdict.DAMAGED_REINSTALL, InterruptedInstallDetector.evaluate(true, false));
    }
}
