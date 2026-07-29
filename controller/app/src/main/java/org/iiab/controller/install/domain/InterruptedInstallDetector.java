/*
 * ============================================================================
 * Name        : InterruptedInstallDetector.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4919 (2c-ii). Pure verdict for "was a proot install left damaged by a forced
 *               kill?" — decided from three on-launch signals so it can be unit-tested without a
 *               device.
 *
 *               Background: a proot (Ansible/runrole) install runs on the LIVE rootfs. InstallGuard
 *               writes a durable marker at the start of every proot pipeline and clears it only on a
 *               clean terminal, so a process killed mid-install leaves the marker set. On the next
 *               cold launch (the in-memory queues are IDLE, i.e. the installer is truly gone), we do
 *               NOT try to resume — an interrupted runrole can leave the base unbootable. Instead we
 *               judge whether the system is usable at all.
 *
 *               Verdict (conservative — only declares damage when we can neither confirm success nor
 *               reach the server):
 *                 - no marker                          -> OK (normal launch)
 *                 - marker set, but base install done  -> OK (stale marker; caller clears it)
 *                   (flag_install_ready on disk) OR the server came up within the timeout
 *                 - marker set, no success marker, and server did not come up in the timeout
 *                                                      -> DAMAGED_REINSTALL
 *
 *               Why this shape: a properly installed base always has flag_install_ready on disk, so
 *               a slow-but-healthy boot is never mis-declared damaged; only a base install that never
 *               reached success AND whose server won't start trips the damaged verdict. A killed
 *               *module* runrole over an already-good base (flag present / server up) reads as OK —
 *               the base still boots; the missing module is not a reinstall-everything situation.
 * ============================================================================
 */
package org.iiab.controller.install.domain;

public final class InterruptedInstallDetector {

    public enum Verdict { OK, DAMAGED_REINSTALL }

    private InterruptedInstallDetector() {}

    /**
     * @param markerPresent        InstallGuard.inProgress() — a proot pipeline started and never
     *                             reached a clean terminal.
     * @param baseInstallSucceeded the in-container success marker (flag_install_ready) exists on disk.
     * @param serverReachable      the server responded within the recovery timeout.
     */
    public static Verdict evaluate(boolean markerPresent, boolean baseInstallSucceeded, boolean serverReachable) {
        if (!markerPresent) return Verdict.OK;                       // nothing was mid-flight
        if (baseInstallSucceeded || serverReachable) return Verdict.OK;  // usable system; marker is stale
        return Verdict.DAMAGED_REINSTALL;                            // never finished and won't come up
    }
}
