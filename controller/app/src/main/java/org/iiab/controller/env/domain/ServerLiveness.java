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
    private final long servicesDownSinceMs;
    private final boolean booting;

    private ServerLiveness(boolean processPresent, boolean servicesAnswering, long observedAtMs,
                           long servicesDownSinceMs, boolean booting) {
        this.processPresent = processPresent;
        this.servicesAnswering = servicesAnswering;
        this.observedAtMs = observedAtMs;
        this.servicesDownSinceMs = servicesDownSinceMs;
        this.booting = booting;
    }

    /**
     * A history-less snapshot. {@code servicesDownSinceMs} is seeded from this one observation
     * (down "since now" when the proot is present but its services do not answer), so a lone snapshot
     * reports ~0 downtime. The continuous-downtime clock is threaded by {@link #next}; prefer it on the
     * poll path so a flap is measured across ticks rather than re-started every tick.
     *
     * @param processPresent    our environment proot is alive (from {@code /proc}).
     * @param servicesAnswering dash-node answers {@code /k2go-api}.
     * @param observedAtMs      when these were read, from a monotonic clock
     *                          ({@code SystemClock.elapsedRealtime}); {@code 0} means "never".
     */
    public static ServerLiveness of(boolean processPresent, boolean servicesAnswering,
                                    long observedAtMs) {
        long downSince = (!servicesAnswering && processPresent && observedAtMs > 0) ? observedAtMs : 0L;
        return new ServerLiveness(processPresent, servicesAnswering, observedAtMs, downSince,
                processPresent && !servicesAnswering);
    }

    /**
     * ADFA-5343 (delta ADR-5343a, D1): the next snapshot in a continuous poll stream, carrying how long
     * the services have been down <b>while the proot stayed present</b> — the clock that tells a flap
     * (dash-node briefly gone, pdsm about to respawn it) from a genuinely stuck environment. This
     * replaces keying the kill decision on proot age (which fires instantly on a mature proot; ADFA-5336
     * regression): downtime is measured from the service drop, not the proot's birth.
     *
     * <p><b>Guardrail — observed-continuous, not wall-calendar.</b> The clock is carried forward only
     * across a <em>fresh</em> previous observation. If the poll stopped feeding us (the app was
     * backgrounded, {@code prev} is stale by {@link Freshness}), the streak is broken and the clock
     * <b>resets to {@code nowMs}</b> — otherwise a long background gap would read as long downtime and
     * re-drive the kill loop the moment the app returns.
     *
     * @param prev              the previous snapshot, or {@code null} on the first tick.
     * @param processPresent    our environment proot is alive now.
     * @param servicesAnswering dash-node answers {@code /k2go-api} now.
     * @param nowMs             the monotonic clock these were read at.
     * @param freshnessMs       how long {@code prev} stays trustworthy for carrying the streak.
     */
    public static ServerLiveness next(ServerLiveness prev, boolean processPresent,
                                      boolean servicesAnswering, long nowMs, long freshnessMs) {
        long downSince;
        if (servicesAnswering || !processPresent) {
            downSince = 0L;   // up, or the proot is gone (that is DOWN → LAUNCH, not a downtime to time)
        } else {
            boolean continuousStreak = prev != null
                    && prev.processPresent && !prev.servicesAnswering
                    && prev.servicesDownSinceMs > 0L
                    && Freshness.fresh(prev.observedAtMs, nowMs, freshnessMs);   // no observation gap
            downSince = continuousStreak ? prev.servicesDownSinceMs : nowMs;
        }

        // ADFA-5365: is this proot still coming up, or one that served and stopped? Carried the same
        // way as the downtime clock and answered from the same observations, because it is the same
        // kind of fact: what this stream has seen since this proot appeared.
        //   - no previous snapshot -> a proot we just launched (the actuators null this on launch)
        //   - previous says the proot was gone -> this is a new one, so it has not served yet
        //   - otherwise carry the answer forward, but only across a fresh streak: after an
        //     observation gap we cannot claim it never served, so it falls back to "not booting"
        //     and the caller keeps today's flap rule.
        // That fallback is deliberately sticky for the life of this proot: once a gap has cost us the
        // answer, no later tick can honestly recover it, so it stays false until the proot is replaced.
        // The same gap also resets the downtime clock, so the flap rule starts its grace from scratch.
        boolean stillBooting = processPresent && !servicesAnswering
                && (prev == null
                    || !prev.processPresent
                    || (Freshness.fresh(prev.observedAtMs, nowMs, freshnessMs) && prev.booting));

        return new ServerLiveness(processPresent, servicesAnswering, nowMs, downSince, stillBooting);
    }

    public boolean processPresent() { return processPresent; }
    public boolean servicesAnswering() { return servicesAnswering; }
    public long observedAtMs() { return observedAtMs; }

    /**
     * ADFA-5365: true while this proot has never answered since it appeared — it is coming up, not
     * flapping. The distinction the escalation needs: a boot and a flap both read as "alive, not
     * answering", but a boot legitimately takes as long as the device needs (37 s measured on a slow
     * device, and longer as content grows) while a flap should be back in about 3 s.
     */
    public boolean booting() { return booting; }

    /**
     * How long the services have been continuously observed down (proot present), or {@code -1} when
     * that is not a fact to act on: the snapshot itself is stale (a background gap — never time a kill
     * off a reading the poll has not refreshed), or the services are up / the proot is gone. A caller
     * treats {@code -1} as "wait, do not kill", the same fail-safe as an unknown proot age was.
     *
     * @param nowMs       the same monotonic clock the snapshot was stamped from.
     * @param freshnessMs how long the snapshot stays trustworthy (a stale one reports {@code -1}).
     */
    public long servicesDownMs(long nowMs, long freshnessMs) {
        if (!Freshness.fresh(observedAtMs, nowMs, freshnessMs)) {
            return -1L;
        }
        if (servicesDownSinceMs <= 0L) {
            return -1L;
        }
        return Math.max(0L, nowMs - servicesDownSinceMs);
    }

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
