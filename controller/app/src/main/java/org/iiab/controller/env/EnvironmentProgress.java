/*
 * ============================================================================
 * Name        : EnvironmentProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5365. When did the environment last show a sign of life?
 * ============================================================================
 */
package org.iiab.controller.env;

import android.os.SystemClock;

/**
 * The environment's last sign of life, in one place.
 *
 * <p><b>Why this is not held by the engine.</b> The fact belongs to <em>the environment</em>, not to
 * whichever actuator happens to hold a reference to the {@code PRootEngine} that started it. There
 * are two launch paths — the foreground {@code ServerController} and the app-scoped reconciler — and
 * they hold different engines. Reading the stamp off "my engine" means a boot started in the
 * foreground and then backgrounded mid-way looks silent to the reconciler, which falls back to the
 * downtime rule and kills the boot at 20 s: precisely the bug ADFA-5365 exists to remove. One fact,
 * one owner, both actuators reading the same answer regardless of who launched.
 *
 * <p><b>Only the environment writes here.</b> The stamp is fed from the environment launches' own
 * output listeners, not from {@code PRootEngine} itself, so a module install streaming through the
 * same engine class cannot make a stalled environment look alive.
 *
 * <p>App-scoped and static, mirroring {@code ServerStateRepository}: the environment outlives every
 * Activity, so the fact about it has to as well. Its lifetime is the process — after a restart there
 * is no stamp, which reads as "no signal" and returns the caller to the downtime rule, the same
 * fail-safe an orphaned proot already gets.
 */
public final class EnvironmentProgress {

    /** Monotonic stamp of the last sign of life, or {@code 0} for "no signal". */
    private static volatile long lastSignOfLifeMs = 0L;

    private EnvironmentProgress() {
    }

    /**
     * A fresh environment launch. The launch itself counts as the first sign of life, so an
     * environment that never says anything is still measured from a real start rather than reading
     * as silent since the beginning of time.
     */
    public static void launched() {
        lastSignOfLifeMs = SystemClock.elapsedRealtime();
    }

    /** The environment produced output: it is still moving. */
    public static void alive() {
        lastSignOfLifeMs = SystemClock.elapsedRealtime();
    }

    /**
     * The environment is gone (a stop, or a teardown). Drops the stamp so the next decision has no
     * stale progress to trust — a signal from a proot that no longer exists is not a signal.
     */
    public static void cleared() {
        lastSignOfLifeMs = 0L;
    }

    /**
     * How long the environment has shown no sign of life, or {@code -1} when there is no signal —
     * nothing was launched in this process, or it has been cleared. Callers treat {@code -1} as
     * "no progress fact here" and fall back to their downtime rule.
     *
     * @param nowMs the same monotonic clock the stamps come from
     *              ({@code SystemClock.elapsedRealtime}).
     */
    public static long silentMs(long nowMs) {
        final long last = lastSignOfLifeMs;
        return last <= 0L ? -1L : Math.max(0L, nowMs - last);
    }
}
