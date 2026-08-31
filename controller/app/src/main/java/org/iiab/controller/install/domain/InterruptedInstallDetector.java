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
 *               ADFA-5343 (Phase 5a): the rule now takes two signals, not five. The three former
 *               "is the planter still running" preconditions (installerRunning / moduleQueueRunning /
 *               deepOpHoldsLock) existed only to tell a live install from a killed one, and they reset
 *               on the very kill they had to catch. InstallGuard's session token answers that from the
 *               marker itself: "interrupted" IS "a marker left by a dead process", so a live install is
 *               never passed here as interrupted in the first place.
 *
 *               Signals:
 *                 - interrupted     = InstallGuard.isInterrupted(): a ".install_in_progress" marker left
 *                                     by a now-dead process launch (its token no longer matches). A live
 *                                     install in this process reads isLive, not interrupted, so "work in
 *                                     progress is not damage" holds by construction.
 *                 - serverReachable = the server came up within the recovery timeout (it boots -> usable).
 *
 *               Verdict (conservative): DAMAGED only when an install was interrupted AND the server did
 *               not come up within the timeout — it never finished and won't start. A fine base boots
 *               inside the window (and the caller clears the stale marker then), so it reads OK.
 * ============================================================================
 */
package org.iiab.controller.install.domain;

public final class InterruptedInstallDetector {

    public enum Verdict { OK, DAMAGED_REINSTALL }

    private InterruptedInstallDetector() {}

    /**
     * @param interrupted     an install was interrupted — a marker left by a dead process launch
     *                        ({@code InstallGuard.isInterrupted}). A live install (this process) is not
     *                        interrupted, so it never reaches the DAMAGED branch.
     * @param serverReachable the server answered within the caller's window (it boots -> usable).
     */
    public static Verdict evaluate(boolean interrupted, boolean serverReachable) {
        if (!interrupted) return Verdict.OK;        // absent, or a live install — work in progress ≠ damage
        if (serverReachable) return Verdict.OK;     // base boots -> usable; the stale marker is cleared by the caller
        return Verdict.DAMAGED_REINSTALL;           // interrupted and won't come up
    }
}
