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

    /**
     * ADFA-5343a (D1): the default service-downtime grace — how long "proot alive but services not
     * answering" is read as "still coming up / pdsm will respawn it" before {@link Action#KILL_AND_RELAUNCH}.
     * The one canonical value both actuators use (ServerController's foreground boot and the app-scoped
     * reconciler tick), so the threshold has a single source.
     */
    public static final long DEFAULT_SERVICE_DOWN_GRACE_MS = 20_000L;

    /**
     * ADFA-5365: how long a <b>boot</b> may show no sign of life before it counts as stalled.
     *
     * <p>Deliberately a cap on <em>silence</em>, not on how long a boot takes. Device measurement:
     * a slow device reaches its services in 37 s, and the longest gap between two guest output lines
     * during that boot is 13 s. The two numbers age differently — total boot time grows with the
     * content installed, so any cap on it is outgrown by the next device or the next library, which
     * is how {@link #DEFAULT_SERVICE_DOWN_GRACE_MS} came to kill healthy boots at 20 s. The longest
     * single quiet step does not grow with content, so a cap on it keeps holding. 60 s is 4.6x the
     * worst gap measured, leaving room for a considerably slower device.
     */
    public static final long DEFAULT_BOOT_SILENCE_GRACE_MS = 60_000L;

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
    public static Action decide(boolean envAlive, boolean servicesAlive, boolean booting,
                                long servicesDownMs, long silentMs) {
        return decide(envAlive, servicesAlive, booting, servicesDownMs, DEFAULT_SERVICE_DOWN_GRACE_MS,
                silentMs, DEFAULT_BOOT_SILENCE_GRACE_MS);
    }

    /**
     * ADFA-5365: the same escalation, with the thresholds injected so they can be varied in tests.
     *
     * @param booting            this proot has never answered since it appeared ({@link
     *                           ServerLiveness#booting()}) — it is coming up, not flapping.
     * @param silentMs           how long the launch has shown no sign of life, or negative when
     *                           there is no such signal (an orphan this process did not launch).
     * @param bootSilenceGraceMs how long a boot may be silent before it counts as stalled.
     */
    public static Action decide(boolean envAlive, boolean servicesAlive, boolean booting,
                                long servicesDownMs, long serviceDownGraceMs,
                                long silentMs, long bootSilenceGraceMs) {
        if (!envAlive) {
            return Action.LAUNCH;
        }
        if (servicesAlive) {
            return Action.NOOP_HEALTHY;
        }
        // Alive, services down — but that is two different situations wearing the same face, and one
        // threshold cannot answer both. A boot has been down since the proot started, so its downtime
        // IS its boot time; judging it against the flap grace is what killed healthy 37 s boots at 20 s.
        // Judge a boot on whether it is still moving instead: the guest keeps talking while it comes up.
        if (booting && silentMs >= 0) {
            return silentMs < bootSilenceGraceMs ? Action.WAIT_BOOT_GRACE : Action.KILL_AND_RELAUNCH;
        }
        // It answered once and stopped (a flap), or there is no progress signal to read (an orphan we
        // did not launch). Both are the case this grace was written for, unchanged. Kill only when we
        // can be sure the services stayed down past it — never on an unknown downtime, because
        // "cannot confirm it is stuck" must fall on the side of waiting (pdsm may still be respawning).
        if (servicesDownMs < 0 || servicesDownMs < serviceDownGraceMs) {
            return Action.WAIT_BOOT_GRACE;
        }
        return Action.KILL_AND_RELAUNCH;
    }
}
