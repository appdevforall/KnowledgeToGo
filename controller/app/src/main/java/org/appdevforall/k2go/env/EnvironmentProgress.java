/*
 * ============================================================================
 * Name        : EnvironmentProgress.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5365. When did the environment last show a sign of life?
 * ============================================================================
 */
package org.appdevforall.k2go.env;

import android.os.SystemClock;

import org.appdevforall.k2go.boxidentity.domain.BoxIdentityLine;

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

    /**
     * K2GO-549: this launch printed the box identity "begin" line, so this boot makes the per-box
     * values. Held here because the reconciler can launch the box before any screen listens. Same
     * lifetime as the stamp: a new launch or a stop clears it, and process death drops it.
     */
    private static volatile boolean preparingLaunch = false;

    private EnvironmentProgress() {
    }

    /**
     * A fresh environment launch. The launch itself counts as the first sign of life, so an
     * environment that never says anything is still measured from a real start rather than reading
     * as silent since the beginning of time.
     */
    public static void launched() {
        lastSignOfLifeMs = SystemClock.elapsedRealtime();
        preparingLaunch = false;
    }

    /** The environment produced output: it is still moving. */
    public static void alive() {
        lastSignOfLifeMs = SystemClock.elapsedRealtime();
    }

    /** Receives each line the environment prints, on the engine's output thread. */
    public interface LineListener {
        void onLine(String line);
    }

    /** A viewer of the boot output (the boot screen). Nothing is stored: a late viewer sees later lines only. */
    private static volatile LineListener lineListener;

    public static void setLineListener(LineListener listener) {
        lineListener = listener;
    }

    /** Clears the viewer only if it is still {@code listener}, so one screen never unhooks another. */
    public static void clearLineListener(LineListener listener) {
        if (lineListener == listener) {
            lineListener = null;
        }
    }

    /** The environment printed {@code line}: a sign of life, also passed to the viewer if one is attached. */
    public static void alive(String line) {
        alive();
        if (BoxIdentityLine.isBegin(line)) {
            preparingLaunch = true;
        }
        line(line);
    }

    /** Passes {@code line} to the viewer only. Stop output uses this: a stopping box is not a sign of life. */
    public static void line(String line) {
        final LineListener l = lineListener;
        if (l != null) {
            l.onLine(line);
        }
    }

    /**
     * The environment is gone (a stop, or a teardown). Drops the stamp so the next decision has no
     * stale progress to trust — a signal from a proot that no longer exists is not a signal.
     */
    public static void cleared() {
        lastSignOfLifeMs = 0L;
        preparingLaunch = false;
    }

    /** K2GO-549: true when the current launch printed the box identity "begin" line. */
    public static boolean preparingLaunch() {
        return preparingLaunch;
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
