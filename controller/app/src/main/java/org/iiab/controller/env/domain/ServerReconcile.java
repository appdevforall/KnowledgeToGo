/*
 * ============================================================================
 * Name        : ServerReconcile.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5343. The two pure decisions the server-lifecycle reconciler
 *               makes: (1) desired — should the server be up? — a pure function of
 *               facts that already have owners, and (2) intent — given desired vs
 *               the observed liveness phase, which direction would the owner move.
 *               No android.*, so both are unit-tested on the JVM like
 *               EnvironmentEnsure and Freshness.
 * ============================================================================
 */
package org.iiab.controller.env.domain;

import org.iiab.controller.system.domain.Operation;

/**
 * "Should the server be up, and what would we do about it" — decided in one place.
 *
 * <p>{@link #desired} is the process-scoped truth the app was missing: one owner of "the server
 * should be up," derived from facts that already have owners (installed/healthy from the fact reader,
 * the persisted user intent, and which operation holds the environment). It creates no new source of
 * truth. {@link #intent} is the coarse direction the reconciler would move given that desire and the
 * observed {@link ServerLiveness.Phase}.
 */
public final class ServerReconcile {

    private ServerReconcile() {
    }

    /** The direction the reconciler would move on a tick. Log-only in Phase 1; the actuator acts on it
     *  in a later phase, refining a {@code START} into the safe how (launch / wait-grace / kill-orphan)
     *  via {@link EnvironmentEnsure} — so this gates the direction, that decides the mechanics. */
    public enum Intent {
        /** desired up, nothing running — would launch. */
        START,
        /** desired down, something running — would stop. */
        STOP,
        /** desired up, still coming up — leave it to finish. */
        WAIT,
        /** liveness not yet trustworthy (UNKNOWN) — never act on an unobserved/stale snapshot. */
        HOLD,
        /** actual already matches desired — nothing to do. */
        NOOP
    }

    /**
     * Should the server be up? Up <b>iff</b> the system is present and whole, the user wants it on, and
     * no STOPPED-class holder is forcing it down. LIVE-class holders (a live download, a dashboard
     * self-update) run <em>against</em> the live server, so they leave desired UP; only STOPPED holders
     * (clone/backup/restore/install, which pdsm-stop the box) pull it down.
     *
     * @param installed   a rootfs is present and no install is running over it.
     * @param healthy     the last install was not left half-finished.
     * @param userWantsOn the persisted user intent (today {@code Preferences.WatchdogEnable}).
     * @param holderClass the execution class of the current environment holder
     *                    ({@code EnvironmentLock.currentHolder().executionClass}); {@code NONE} is LIVE.
     */
    public static boolean desired(boolean installed, boolean healthy, boolean userWantsOn,
                                  Operation.ExecutionClass holderClass) {
        return installed && healthy && userWantsOn
                && holderClass != Operation.ExecutionClass.STOPPED;
    }

    /**
     * The coarse direction to move, given desired vs the observed phase. An {@code UNKNOWN} phase always
     * yields {@link Intent#HOLD}: a never-observed or stale snapshot is not a fact to act on.
     */
    public static Intent intent(boolean desiredUp, ServerLiveness.Phase actual) {
        if (actual == ServerLiveness.Phase.UNKNOWN) {
            return Intent.HOLD;
        }
        if (desiredUp) {
            switch (actual) {
                case UP:
                    return Intent.NOOP;
                case STARTING:
                    return Intent.WAIT;
                case DOWN:
                default:
                    return Intent.START;
            }
        }
        // desired down
        switch (actual) {
            case DOWN:
                return Intent.NOOP;
            case UP:
            case STARTING:
            default:
                return Intent.STOP;
        }
    }

    /**
     * Whether an {@link Intent} means "drive the server up now". {@code START} (down) and {@code WAIT}
     * (still coming up, or a stuck flap) both do — the reconciler routes both to the idempotent,
     * self-gating boot, which decides launch / leave-in-grace / relaunch-stuck. {@code NOOP} / {@code
     * STOP} / {@code HOLD} do not (STOP is owned elsewhere until a later phase; HOLD never acts on an
     * unobserved snapshot).
     */
    public static boolean ensuresUp(Intent intent) {
        return intent == Intent.START || intent == Intent.WAIT;
    }

    /**
     * Whether the reconciler should drive the server up on this tick: the intent says "up"
     * ({@link #ensuresUp}) AND the current holder does not restart the server itself. A self-restarting
     * holder (DASHBOARD's live blue-green rebuild, ADR-5343a) owns the restart, so actuating would fight
     * it mid-swap — defer until it releases (the release is bounded; see {@code Holder.selfRestartsServer}).
     */
    public static boolean shouldEnsureUp(Intent intent, boolean holderSelfRestartsServer) {
        return ensuresUp(intent) && !holderSelfRestartsServer;
    }

    /**
     * Whether the reconciler should STOP the server now: the intent is {@link Intent#STOP} (desired down,
     * box still up) AND <b>no holder</b> is in force. The {@code holderIsNone} gate is what keeps the
     * reconciler from double-stopping a deep op: clone/backup/restore/install already quiesced the box
     * synchronously and hold a STOPPED-class lock, so the reconciler must NOT also stop — it stops only a
     * plain user turn-off (holder NONE, userWantsOn false). LIVE holders (DOWNLOAD/DASHBOARD) keep
     * desired UP, so they never produce a STOP intent in the first place.
     */
    public static boolean shouldStop(Intent intent, boolean holderIsNone) {
        return intent == Intent.STOP && holderIsNone;
    }
}
