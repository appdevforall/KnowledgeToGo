/*
 * ============================================================================
 * Name        : EnvironmentEnsure.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5103. The pure decision behind "ensure the environment is
 *               up": given whether our proot is alive, how old it is, and
 *               whether its services answer, say what to do — launch, leave it,
 *               wait out its boot grace, or kill the orphan and relaunch. No
 *               android.*, so it is unit-tested on the JVM like
 *               EnvironmentProcessMatcher.
 * ============================================================================
 */
package org.iiab.controller.env.domain;

/**
 * "Ensure it is up", decided in one place instead of at six call sites.
 *
 * <p>The problem this exists for: {@code pdsm stop} stops the services inside the proot but leaves
 * the proot itself running, and the app's only runtime signal — an HTTP ping — cannot tell "services
 * down, environment alive" from "everything down". Both read as offline, so a start stacks a second
 * proot over a live one. A proot cannot be entered once started, so the only way to restore services
 * to a live-but-serviceless environment is to end it and bring up a fresh one.
 *
 * <p>The trap the earlier attempt fell into: it killed a proot 3.5 s into its own boot, because
 * "alive but not answering" and "alive and still starting" are the same observation for the first
 * seconds. The guard is a <b>boot grace</b> measured from the proot's own age (from {@code /proc}),
 * not from when this process launched it — so a young proot is protected whether we started it or a
 * force-closed predecessor did (the observed 3.5 s double-boot after an Activity-stack restore).
 */
public final class EnvironmentEnsure {

    private EnvironmentEnsure() {
    }

    public enum Action {
        /** Nothing of ours is running — start it. */
        LAUNCH,
        /** Alive and its services answer — a redundant start is a no-op. */
        NOOP_HEALTHY,
        /** Alive, services not answering yet, but still inside its boot grace — leave it to finish. */
        WAIT_BOOT_GRACE,
        /** Alive, services not answering, past its boot grace — a stuck orphan; end it and relaunch. */
        KILL_AND_RELAUNCH
    }

    /**
     * @param envAlive      whether an environment proot of ours is running (from {@code /proc}).
     * @param envAgeMs      the running proot's age in ms, or a negative value when it is unknown
     *                      (no proot, or its start time could not be read).
     * @param servicesAlive whether the box's services answer (the cached HTTP-ping fact).
     * @param bootGraceMs   how long "alive but not answering" is read as "still starting" rather
     *                      than "stuck".
     */
    public static Action decide(boolean envAlive, long envAgeMs, boolean servicesAlive,
                                long bootGraceMs) {
        if (!envAlive) {
            return Action.LAUNCH;
        }
        if (servicesAlive) {
            return Action.NOOP_HEALTHY;
        }
        // Alive, services down. Kill only a proot we can be sure is past its boot — never on an
        // unknown age, because the mistake that got the first attempt reverted was killing one
        // mid-boot, and "cannot confirm it is old" must fall on the side of not killing.
        if (envAgeMs < 0 || envAgeMs < bootGraceMs) {
            return Action.WAIT_BOOT_GRACE;
        }
        return Action.KILL_AND_RELAUNCH;
    }
}
