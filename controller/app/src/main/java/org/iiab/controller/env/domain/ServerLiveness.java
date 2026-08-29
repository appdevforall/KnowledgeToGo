/*
 * ============================================================================
 * Name        : ServerLiveness.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5343 (Phase 0). One honest snapshot of "is the server up?",
 *               replacing the four scattered liveness sources with a single
 *               freshness-windowed reading. Pure JVM (no android.*, no HTTP): it
 *               holds the two observed facts + when they were observed, and
 *               derives one phase. The impure probes (/proc, /k2go-api) are read
 *               by the caller and passed in, so this stays unit-testable on the
 *               JVM like EnvironmentEnsure and Freshness.
 * ============================================================================
 */
package org.iiab.controller.env.domain;

import org.iiab.controller.env.Freshness;

/**
 * "Is the server up?", answered once, from two facts read one way.
 *
 * <p>The app used to answer this four different ways (a cached {@code /home} ping, a "have we polled
 * yet?" bool, a fresh {@code /k2go-api} probe, and a {@code /proc} read), and reading {@code /home}
 * as "up" is the root of the post-install flap: nginx answers {@code /home} before its dash-node
 * upstream is ready, so a restarting engine still reads "up" (ADFA-5336). Here there is one snapshot:
 *
 * <ul>
 *   <li>{@code servicesAnswering} — the honest "usable" signal: dash-node {@code /k2go-api} answers,
 *       not merely nginx {@code /home}.</li>
 *   <li>{@code processPresent} — our environment proot is alive in {@code /proc}. The discriminator
 *       that tells "services down, environment alive" (still starting) from "environment gone"
 *       (down), the reason {@code EnvironmentProcess} exists (ADFA-5061).</li>
 *   <li>{@code observedAtMs} — a monotonic stamp ({@code SystemClock.elapsedRealtime}). A snapshot
 *       nobody has refreshed is not trusted as fact; it ages back to {@code UNKNOWN}.</li>
 * </ul>
 *
 * <p>{@code UNKNOWN} absorbs the old "have we polled yet?" bool: a never-observed ({@code
 * observedAtMs <= 0}) or a stale snapshot is {@code UNKNOWN}, not a false {@code DOWN}.
 */
public final class ServerLiveness {

    /**
     * How long a snapshot is trusted before it ages back to {@code UNKNOWN}. The status poll refreshes
     * every ~3 s (ServerController.CHECK_INTERVAL_MS), so three intervals means "the poll stopped
     * feeding us" rather than a normal gap — long enough not to flap on one slow probe, short enough
     * that a genuinely stale reading is not mistaken for a live one.
     */
    public static final long DEFAULT_FRESH_MS = 9_000L;

    /** The derived state. One bit of "desired" is a separate concern (the reconciler); this is only
     *  the observed "actual". */
    public enum Phase {
        /** No trustworthy observation: never polled, or the last snapshot has gone stale. */
        UNKNOWN,
        /** Neither the proot nor the services are present. */
        DOWN,
        /** The proot is up but the services do not answer yet (booting, within grace elsewhere). */
        STARTING,
        /** The services answer {@code /k2go-api} — genuinely usable. */
        UP
    }

    private final boolean processPresent;
    private final boolean servicesAnswering;
    private final long observedAtMs;

    private ServerLiveness(boolean processPresent, boolean servicesAnswering, long observedAtMs) {
        this.processPresent = processPresent;
        this.servicesAnswering = servicesAnswering;
        this.observedAtMs = observedAtMs;
    }

    /**
     * @param processPresent    our environment proot is alive (from {@code /proc}).
     * @param servicesAnswering dash-node answers {@code /k2go-api}.
     * @param observedAtMs      when these were read, from a monotonic clock
     *                          ({@code SystemClock.elapsedRealtime}); {@code 0} means "never".
     */
    public static ServerLiveness of(boolean processPresent, boolean servicesAnswering,
                                    long observedAtMs) {
        return new ServerLiveness(processPresent, servicesAnswering, observedAtMs);
    }

    public boolean processPresent() { return processPresent; }
    public boolean servicesAnswering() { return servicesAnswering; }
    public long observedAtMs() { return observedAtMs; }

    /** The phase using the default freshness window. */
    public Phase phase(long nowMs) {
        return phase(nowMs, DEFAULT_FRESH_MS);
    }

    /**
     * The phase at {@code nowMs}. A never-observed ({@code observedAtMs <= 0}) or stale snapshot is
     * {@code UNKNOWN} — {@link Freshness#fresh} is the one definition of "still trustworthy" (0 is
     * never fresh, so both fold here). Otherwise {@code servicesAnswering} wins ({@code UP});
     * else a present proot is still {@code STARTING}; else {@code DOWN}.
     *
     * @param nowMs       the same monotonic clock {@code observedAtMs} was stamped from.
     * @param freshnessMs how long the snapshot stays trustworthy.
     */
    public Phase phase(long nowMs, long freshnessMs) {
        if (!Freshness.fresh(observedAtMs, nowMs, freshnessMs)) {
            return Phase.UNKNOWN;
        }
        if (servicesAnswering) {
            return Phase.UP;
        }
        if (processPresent) {
            return Phase.STARTING;
        }
        return Phase.DOWN;
    }
}
