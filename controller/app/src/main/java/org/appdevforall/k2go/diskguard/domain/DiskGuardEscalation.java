/*
 * ============================================================================
 * Name        : DiskGuardEscalation.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-386 (Layer 3). The pure escalation rule for the app-side
 *               disk-fill backstop. It decides, on one guard tick, what to do
 *               from whether the disk is critical now plus the recent-trip state.
 *               No android.*, so it is unit-tested on a plain JVM. See ADR-386.
 * ============================================================================
 */
package org.appdevforall.k2go.diskguard.domain;

/**
 * A trip is a confirmed-critical tick. Trips count only while they stay CONSECUTIVE: a non-critical tick
 * ends the spell and resets the count, and a long gap (over the window) resets it too. The default action
 * is CONTAIN (reap, then let the box restart). If the disk stays critical for {@code escalateAfter} trips
 * in a row, the restart is not fixing it, so the action becomes ESCALATE (stop and stay down).
 */
public final class DiskGuardEscalation {

    public enum Action { NONE, CONTAIN, ESCALATE }

    /** The new trip state plus the action to take. Immutable. */
    public static final class Verdict {
        public final Action action;
        public final int tripCount;       // new consecutive-trip count; 0 when not critical
        public final long lastElapsedMs;  // new last-trip time
        public final boolean firstOfSpell; // tripCount == 1; used to report once per spell

        public Verdict(Action action, int tripCount, long lastElapsedMs, boolean firstOfSpell) {
            this.action = action;
            this.tripCount = tripCount;
            this.lastElapsedMs = lastElapsedMs;
            this.firstOfSpell = firstOfSpell;
        }
    }

    private DiskGuardEscalation() {}

    /**
     * @param critical      whether the disk is critical on this tick (already confirmed).
     * @param nowMs         a monotonic clock reading (SystemClock.elapsedRealtime).
     * @param lastElapsedMs the previous trip's clock reading, or negative if none.
     * @param prevCount     the previous consecutive-trip count.
     * @param windowMs      a gap longer than this starts a new spell.
     * @param escalateAfter the trip number at which to escalate.
     */
    public static Verdict next(boolean critical, long nowMs, long lastElapsedMs, int prevCount,
                               long windowMs, int escalateAfter) {
        if (!critical) {
            // The disk recovered. End the spell so a later, separate fill starts fresh.
            return new Verdict(Action.NONE, 0, lastElapsedMs, false);
        }
        int count;
        if (prevCount <= 0 || lastElapsedMs < 0L || nowMs - lastElapsedMs > windowMs) {
            count = 1; // first trip of a new spell
        } else {
            count = prevCount + 1;
        }
        Action action = count >= escalateAfter ? Action.ESCALATE : Action.CONTAIN;
        return new Verdict(action, count, nowMs, count == 1);
    }
}
