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

    // ---- ADFA-5061: the preconditions that make the marker mean "killed" ----

    @Test public void aRunningInstallHoldsTheMarker_isOk() {
        // Work in progress is not damage. Without this the two-argument form would
        // call an install that is running right now a damaged system.
        assertEquals(Verdict.OK,
                InterruptedInstallDetector.evaluate(true, true, false, false, false));
    }

    @Test public void aRunningModuleQueueHoldsTheMarker_isOk() {
        assertEquals(Verdict.OK,
                InterruptedInstallDetector.evaluate(true, false, true, false, false));
    }

    @Test public void aLiveBackupOrRestoreHoldsTheMarker_isOk() {
        // ADFA-4971: a deep env op legitimately holds InstallGuard. Reading the
        // marker bare produced a false "reinstall" dialog in the middle of a backup.
        assertEquals(Verdict.OK,
                InterruptedInstallDetector.evaluate(true, false, false, true, false));
    }

    @Test public void markerWithNoOwnerAndNoServer_isStillDamaged() {
        assertEquals(Verdict.DAMAGED_REINSTALL,
                InterruptedInstallDetector.evaluate(true, false, false, false, false));
    }

    @Test public void theTwoArgumentFormMeansNoOwnerWasChecked() {
        // The boot check establishes the owners itself before calling, so the short
        // form must keep behaving exactly as it did.
        for (boolean marker : new boolean[]{true, false}) {
            for (boolean server : new boolean[]{true, false}) {
                assertEquals(InterruptedInstallDetector.evaluate(marker, server),
                        InterruptedInstallDetector.evaluate(marker, false, false, false, server));
            }
        }
    }
}
