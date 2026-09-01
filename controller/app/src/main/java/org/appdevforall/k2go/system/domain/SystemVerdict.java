/*
 * ============================================================================
 * Name        : SystemVerdict.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5312. One pure verdict for "what state is the system in", so UI screens switch
 *               on it instead of each re-deriving it from SystemStateEvaluator.isSystemInstalled()
 *               plus an ad-hoc grab-bag of running flags. That ad-hoc pattern is the bug this closes:
 *               a running proot module (or any system op) holds the install marker, so
 *               isSystemInstalled() reads false, and a screen that branches on it alone shows a false
 *               "no system / Recover" over a system that is actually there and busy installing.
 *
 *               Booleans in, enum out, unit-tested. This is the DISPLAY verdict only — the boot gate,
 *               the server-start gate and the recovery/reinstall dialog keep their own strict checks
 *               (SystemStateEvaluator / InterruptedInstallDetector directly); this does not feed them.
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

import org.appdevforall.k2go.install.domain.InterruptedInstallDetector;

public final class SystemVerdict {

    /** The states a UI screen needs to tell apart. Server sub-states (starting/failed/empty) and the
     *  content-download axis stay local to the screens that care (Home); they are not system states. */
    public enum State { READY, INSTALLING, NO_SYSTEM, DAMAGED, CLONE_RECEIVING, CLONE_SHARING }

    private SystemVerdict() {}

    /**
     * @param rootfsPresent      a rootfs exists on disk (SystemStateEvaluator.rootfsPresent)
     * @param interrupted        an install was interrupted — InstallGuard.isInterrupted() (a marker left
     *                           by a dead process launch); a LIVE install reads via the running flags below
     * @param installerRunning   a rootfs install pipeline is alive in this process
     * @param moduleQueueRunning a proot module queue is alive in this process
     * @param deepOpHoldsLock    a backup/restore/clone holds the environment lock
     * @param cloneReceiving     a clone receive is active
     * @param cloneSharing       a clone send is active
     * @param serverUp           the server answered
     * @param serverStateKnown   the server has been observed at least once (else "unknown", not "down")
     */
    public static State evaluate(boolean rootfsPresent,
                                 boolean interrupted,
                                 boolean installerRunning,
                                 boolean moduleQueueRunning,
                                 boolean deepOpHoldsLock,
                                 boolean cloneReceiving,
                                 boolean cloneSharing,
                                 boolean serverUp,
                                 boolean serverStateKnown) {
        // A clone in flight outranks everything: a whole system is arriving or leaving.
        if (cloneReceiving) return State.CLONE_RECEIVING;
        if (cloneSharing) return State.CLONE_SHARING;
        // Any system-level operation legitimately holds the marker/lock — a rootfs install, a proot
        // module runrole, or a backup/restore. Work in progress is not an absent or damaged system,
        // and must never be offered Recover/Install-a-system (ADFA-5312).
        if (installerRunning || moduleQueueRunning || deepOpHoldsLock) return State.INSTALLING;
        // Nothing running and nothing on disk: the first-run install is the only path.
        if (!rootfsPresent) return State.NO_SYSTEM;
        // A rootfs exists and nothing is running. If a marker is still set and the server won't come
        // up, a prior install was killed and left it damaged — deferred to the existing DAMAGED oracle.
        // Before the first server observation the answer is "unknown", not "down", so hold READY until
        // the poll reports rather than flashing a false DAMAGED.
        if (serverStateKnown
                && InterruptedInstallDetector.evaluate(interrupted, serverUp)
                        == InterruptedInstallDetector.Verdict.DAMAGED_REINSTALL) {
            return State.DAMAGED;
        }
        return State.READY;
    }
}
