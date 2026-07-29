/*
 * ============================================================================
 * Name        : InterruptedInstallDetector.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4919 (2c-ii). Pure verdict for "was a proot install left damaged by a forced
 *               kill?" — decided from two APP-CONTROLLED / probe signals so it can be unit-tested
 *               without a device.
 *
 *               Design fix: the earlier version leaned on the in-container flag_install_ready, which
 *               is GLOBAL and PERSISTENT (written once by a successful base install, never cleared by
 *               the app) — so any interrupted install on a device that was ever installed read as
 *               "OK". Dropped. A log-based "clean finish" was also considered but is unreliable here
 *               (IIAB emits several intermediate Ansible PLAY RECAPs, so an interrupted run can still
 *               show a clean sub-recap); it needs a dedicated end-of-install marker before it can gate
 *               the verdict, so it is only logged as an observation for now, not used here.
 *
 *               Signals:
 *                 - markerPresent  = InstallGuard's ".install_in_progress": WE plant it at pipeline
 *                                    start and clear it only on a clean terminal, so a killed install
 *                                    leaves it set. (Proot modules run serialized -> one marker suffices.)
 *                 - serverReachable = the server came up within the recovery timeout (it boots -> usable).
 *
 *               Verdict (conservative): DAMAGED only when the marker is set AND the server did not come
 *               up within the timeout — it never finished and won't start. A healthy system's server
 *               comes up inside the window (and the caller clears a stale marker then), so it reads OK.
 * ============================================================================
 */
package org.iiab.controller.install.domain;

public final class InterruptedInstallDetector {

    public enum Verdict { OK, DAMAGED_REINSTALL }

    private InterruptedInstallDetector() {}

    public static Verdict evaluate(boolean markerPresent, boolean serverReachable) {
        if (!markerPresent) return Verdict.OK;      // nothing was mid-flight
        if (serverReachable) return Verdict.OK;     // system boots -> usable; the stale marker is cleared by the caller
        return Verdict.DAMAGED_REINSTALL;           // never finished and won't come up
    }
}
