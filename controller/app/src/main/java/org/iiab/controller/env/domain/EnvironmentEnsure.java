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
        /** Alive, services not answering yet, but still inside the grace — leave it (pdsm respawns). */
        WAIT_BOOT_GRACE,
        /** Alive, services down past the grace — a genuinely stuck environment; end it and relaunch. */
        KILL_AND_RELAUNCH
    }

    /**
     * ADFA-5343 (delta ADR-5343a, D1): the escalation clock is <b>service downtime</b>, not proot age.
     * Keying on age fired {@code KILL_AND_RELAUNCH} on the first tick after a mature proot's dash-node
     * blipped — before pdsm's ~3 s respawn — turning a self-healing flap into an unrecoverable loop
     * (ADFA-5336, device-confirmed). Timed from the service drop instead, a flap stays inside the grace
     * and self-heals via {@code WAIT_BOOT_GRACE} → {@code NOOP_HEALTHY}; only a service that stays down
     * past the grace (pdsm could not bring it back) is a stuck environment worth relaunching.
     *
     * <p>The single case that got the first ADFA-5103 attempt reverted — a proot killed 3.5 s into its
     * own boot — is still protected: during a boot the services have been down since the proot started,
     * so {@code servicesDownMs} is that same small elapsed time and stays under the grace. An unknown
     * downtime ({@code < 0}: a stale/never-observed snapshot) is never killed, the same fail-safe.
     *
     * @param envAlive           whether an environment proot of ours is running (from {@code /proc}).
     * @param servicesAlive      whether the box's services answer ({@code /k2go-api}, fresh).
     * @param servicesDownMs     how long the services have been continuously observed down while the
     *                           proot stayed present ({@link ServerLiveness#servicesDownMs}), or a
     *                           negative value when that is not a trustworthy fact.
     * @param serviceDownGraceMs how long "alive but not answering" is read as "still coming up / pdsm
     *                           will respawn it" rather than "stuck".
     */
    public static Action decide(boolean envAlive, boolean servicesAlive, long servicesDownMs,
                                long serviceDownGraceMs) {
        if (!envAlive) {
            return Action.LAUNCH;
        }
        if (servicesAlive) {
            return Action.NOOP_HEALTHY;
        }
        // Alive, services down. Kill only when we can be sure they have stayed down past the grace —
        // never on an unknown downtime, because "cannot confirm it is stuck" must fall on the side of
        // waiting (pdsm may still be respawning the service).
        if (servicesDownMs < 0 || servicesDownMs < serviceDownGraceMs) {
            return Action.WAIT_BOOT_GRACE;
        }
        return Action.KILL_AND_RELAUNCH;
    }
}
