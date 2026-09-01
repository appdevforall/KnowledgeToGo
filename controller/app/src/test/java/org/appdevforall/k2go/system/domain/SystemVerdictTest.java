package org.appdevforall.k2go.system.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import org.appdevforall.k2go.system.domain.SystemVerdict.State;

/**
 * ADFA-5312: the truth table for the shared display verdict. Pure, so the rule the five UI screens
 * now switch on is locked here — including the case the bug was about (a running proot module reads
 * INSTALLING, never NO_SYSTEM). Args order mirrors
 * {@link SystemVerdict#evaluate}: rootfs, marker, installer, moduleQueue, deepOp, cloneRx, cloneTx,
 * serverUp, serverKnown.
 */
public class SystemVerdictTest {

    private static State v(boolean rootfs, boolean marker, boolean inst, boolean mod, boolean deep,
                           boolean cloneRx, boolean cloneTx, boolean serverUp, boolean known) {
        return SystemVerdict.evaluate(rootfs, marker, inst, mod, deep, cloneRx, cloneTx, serverUp, known);
    }

    @Test public void readySystem() {
        assertEquals(State.READY, v(true, false, false, false, false, false, false, true, true));
    }

    @Test public void staleMarkerButServerUp_isReady() {
        // A rootfs that boots (server up) with a leftover marker is ready, not damaged.
        assertEquals(State.READY, v(true, true, false, false, false, false, false, true, true));
    }

    @Test public void moduleRunning_isInstalling_notNoSystem() {
        // ADFA-5312 core: a proot module holds the marker and stops the server -> INSTALLING, never the
        // false "no system / Recover" that shipped before.
        assertEquals(State.INSTALLING, v(true, true, false, true, false, false, false, false, true));
    }

    @Test public void rootfsInstallRunning_isInstalling() {
        assertEquals(State.INSTALLING, v(false, true, true, false, false, false, false, false, true));
    }

    @Test public void deepOp_isInstalling() {
        // A backup/restore legitimately holds the lock -> busy, not absent or damaged.
        assertEquals(State.INSTALLING, v(true, true, false, false, true, false, false, false, true));
    }

    @Test public void noRootfsAndIdle_isNoSystem() {
        assertEquals(State.NO_SYSTEM, v(false, false, false, false, false, false, false, false, true));
    }

    @Test public void killedInstall_serverKnownDown_isDamaged() {
        // Marker set, nothing running, server observed down -> a killed install that won't boot.
        assertEquals(State.DAMAGED, v(true, true, false, false, false, false, false, false, true));
    }

    @Test public void killedInstall_serverUnknown_holdsReady_notDamaged() {
        // Before the first server observation the answer is "unknown", not "down" — don't flash DAMAGED.
        assertEquals(State.READY, v(true, true, false, false, false, false, false, false, false));
    }

    @Test public void cloneReceiving_outranksEverything() {
        assertEquals(State.CLONE_RECEIVING, v(true, true, true, true, true, true, false, false, true));
    }

    @Test public void cloneSharing_outranksInstallAndDamaged() {
        assertEquals(State.CLONE_SHARING, v(true, true, false, false, false, false, true, false, true));
    }

    @Test public void installing_outranksNoSystemAndDamaged() {
        // A running op wins even with no rootfs yet and the server down (a fresh install in flight).
        assertEquals(State.INSTALLING, v(false, true, true, false, false, false, false, false, true));
    }
}
