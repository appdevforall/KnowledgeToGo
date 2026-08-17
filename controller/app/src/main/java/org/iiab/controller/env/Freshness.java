/*
 * ============================================================================
 * Name        : Freshness.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5146. One definition of "is this in-memory session still alive?" — a
 *               last-progress heartbeat compared against a staleness window. A live download
 *               refreshes its timestamp every poll; a service killed before a terminal state
 *               lets it go cold, so EnvironmentLock.isBusyNow() can stop treating a dead
 *               session as work in flight without the app being force-stopped. Pure JVM (no
 *               android.*): the caller passes a monotonic 'now' (SystemClock.elapsedRealtime),
 *               which keeps this unit-testable and immune to wall-clock changes.
 * ============================================================================
 */
package org.iiab.controller.env;

public final class Freshness {

    private Freshness() {
    }

    /** Shared staleness window for the content-download sources (they poll ~1s, so 30s is well
     *  above the cadence and normal hiccups, and well below a user's patience). Generous on
     *  purpose: a too-short window could treat a live-but-slow download as dead and let a deep
     *  operation start on top of it. ADFA-5146. */
    public static final long STALE_MS = 30_000L;

    /** True when {@code lastAtMs} sits within {@code thresholdMs} of {@code nowMs}. A never-stamped
     *  session (0) is not fresh. {@code nowMs} must come from a monotonic clock
     *  (SystemClock.elapsedRealtime), the same source the stamp was taken from. */
    public static boolean fresh(long lastAtMs, long nowMs, long thresholdMs) {
        if (lastAtMs <= 0L) return false;
        return nowMs - lastAtMs <= thresholdMs;
    }
}
