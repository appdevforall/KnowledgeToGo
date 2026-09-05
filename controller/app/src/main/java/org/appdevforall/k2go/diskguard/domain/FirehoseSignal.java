/*
 * ============================================================================
 * Name        : FirehoseSignal.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-386 (Layer 3). The app's parsed view of the dash-node live
 *               firehose signal (GET /system/disk-guard/firehose). Pure value
 *               object, no android.*, so the freshness rule is unit-tested on a
 *               plain JVM. See ADR-386 §6.
 * ============================================================================
 */
package org.appdevforall.k2go.diskguard.domain;

/**
 * A recurring firehose means the in-box guard truncated a runaway log on several consecutive ticks:
 * an off-proot orphan the box cannot stop. This signal is the app's ALERT to look; it is NOT a command
 * to reap. The app re-probes live log growth before it acts (ADR-386 §6, confirm before acting).
 *
 * <p>{@code nowMs} and {@code lastTruncatedAtMs} are both the dash-node wall-clock, so freshness is
 * judged in the server's own time frame -- no app-vs-server clock skew.
 */
public final class FirehoseSignal {

    public final boolean recurring;
    public final int maxStreak;
    public final long lastTruncatedAtMs; // server wall-clock of the last truncation, or 0 if never
    public final long nowMs;             // server wall-clock when it answered

    public FirehoseSignal(boolean recurring, int maxStreak, long lastTruncatedAtMs, long nowMs) {
        this.recurring = recurring;
        this.maxStreak = maxStreak;
        this.lastTruncatedAtMs = lastTruncatedAtMs;
        this.nowMs = nowMs;
    }

    /**
     * True when the signal is worth acting on: it reports a recurring firehose AND the last truncation
     * is recent (within {@code freshWindowMs} of the server's now). A stale signal -- the guard has not
     * truncated anything lately -- is ignored, so a firehose that already resolved never triggers a reap.
     */
    public boolean isFresh(long freshWindowMs) {
        if (!recurring || lastTruncatedAtMs <= 0L) return false;
        long age = nowMs - lastTruncatedAtMs;
        return age >= 0L && age <= freshWindowMs;
    }
}
