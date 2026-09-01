/*
 * ============================================================================
 * Name        : EtaSmoother.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5118. Debounces the install ETA so its text stops flickering.
 *               The raw ETA (derived from a live rate) wobbles as different files
 *               decompress at different speeds, so near a boundary the label bounces
 *               between "1 min" and "almost done". This holds a bucket change until
 *               the new value has persisted for a dwell window, so the text changes
 *               at most once per window instead of on every tick. Pure JVM (the
 *               caller supplies the clock) => unit-testable.
 * ============================================================================
 */
package org.appdevforall.k2go.install.domain;

public final class EtaSmoother {

    /** No honest estimate yet (no rate). Kept distinct from a real bucket (>= 0). */
    public static final int UNKNOWN = -1;

    private final long dwellMs;
    private int shown = UNKNOWN;
    private int pending = UNKNOWN;
    private long pendingSinceMs = 0L;

    public EtaSmoother(long dwellMs) {
        this.dwellMs = dwellMs;
    }

    /**
     * Bucket for an ETA in seconds: {@link #UNKNOWN} when negative, {@code 0} ("almost done")
     * under a minute, else whole minutes remaining (rounded up). Pure.
     */
    public static int bucketOf(long seconds) {
        if (seconds < 0L) return UNKNOWN;
        if (seconds < 60L) return 0;
        return (int) ((seconds + 59L) / 60L);
    }

    /**
     * Debounce bucket changes: a new bucket must persist for {@code dwellMs} before it replaces
     * the shown one, so a value flickering across a boundary (e.g. 1 min &lt;-&gt; almost done)
     * does not bounce. The first real bucket is adopted immediately; an {@link #UNKNOWN} input
     * keeps the last shown bucket (a momentary rate gap must not blank a good estimate).
     */
    public int smooth(int bucket, long nowMs) {
        if (bucket == UNKNOWN) return shown;                 // rate gap: keep what we had
        if (shown == UNKNOWN) { shown = bucket; pending = bucket; return shown; }  // first real value
        if (bucket == shown) { pending = shown; return shown; }                    // stable
        if (bucket != pending) { pending = bucket; pendingSinceMs = nowMs; return shown; }  // new candidate
        if (nowMs - pendingSinceMs >= dwellMs) { shown = bucket; return shown; }    // held long enough
        return shown;                                        // still inside the dwell window
    }
}
