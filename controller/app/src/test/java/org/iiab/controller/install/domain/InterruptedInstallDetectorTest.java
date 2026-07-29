package org.iiab.controller.install.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import org.iiab.controller.install.domain.InterruptedInstallDetector.Verdict;

public class InterruptedInstallDetectorTest {

    @Test public void noMarker_isOk() {
        // Normal launch — nothing was in progress, regardless of the other signals.
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(false, false, false));
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(false, true, true));
    }

    @Test public void markerButBaseSucceeded_isOk_staleMarker() {
        // Crash after success but before the guard was cleared: flag_install_ready is on disk.
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(true, true, false));
    }

    @Test public void markerButServerReachable_isOk() {
        // System boots (server responded within the timeout) — marker is stale, not damage.
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(true, false, true));
    }

    @Test public void markerNoSuccessNoServer_isDamaged() {
        // Killed mid-install: never reached success and the server won't come up -> reinstall.
        assertEquals(Verdict.DAMAGED_REINSTALL, InterruptedInstallDetector.evaluate(true, false, false));
    }

    @Test public void damageVerdictNeedsAllThreeSignals() {
        // Any single "usable" signal (success marker OR reachable) rescues a slow-but-healthy boot.
        assertEquals(Verdict.OK, InterruptedInstallDetector.evaluate(true, true, true));
    }
}
