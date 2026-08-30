/*
 * ============================================================================
 * Name        : ServerLifecycleReconciler.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5343 (Phase 1). The app-scoped owner of the server lifecycle,
 *               introduced first as a LOG-ONLY observer. Each tick it computes the
 *               desired state from facts that already have owners and logs the action
 *               it WOULD take against the observed liveness — it does NOT actuate.
 *               Actuation, its own tick, and WatchdogService promotion arrive in
 *               later phases; this phase only proves the desired-vs-actual reasoning
 *               matches reality on every flow, at zero risk.
 * ============================================================================
 */
package org.iiab.controller.env;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.iiab.controller.Preferences;
import org.iiab.controller.env.domain.ServerLiveness;
import org.iiab.controller.env.domain.ServerReconcile;
import org.iiab.controller.system.data.SystemFactsReader;
import org.iiab.controller.system.domain.Operation;
import org.iiab.controller.system.domain.SystemFacts;

/**
 * One process-scoped owner of "the server should be up," observing only (Phase 1).
 *
 * <p>It is fed the single {@link ServerLiveness} snapshot the status poll already builds (Phase 0),
 * so there is no second liveness source; it reads the desired-state inputs itself — all facts that
 * already have owners — and logs {@code desired vs actual → wouldDo} each tick. Nothing downstream
 * depends on it, so the whole phase reverts by deleting this class and its two call sites (the poll
 * seam and the IIABApplication warm-up).
 */
public final class ServerLifecycleReconciler {

    private static final String TAG = "K2Go-Reconciler";

    private static final ServerLifecycleReconciler INSTANCE = new ServerLifecycleReconciler();

    public static ServerLifecycleReconciler get() {
        return INSTANCE;
    }

    /**
     * ADFA-5343 (Phase 2): master switch for actuation. {@code true} = the reconciler drives the server
     * up through the foreground actuator; {@code false} = log-only (Phase 1) and the hand-off boots
     * itself. The rollback lever for the first actuation — flip to {@code false} to revert behavior
     * without reverting code.
     */
    public static final boolean ACTUATES = true;

    /**
     * ADFA-5343 (Phase 2): the single actuator the reconciler drives — the foregrounded
     * {@code ServerController}, which registers on resume. The reconciler owns the DECISION (desired);
     * the boot MECHANISM stays in the one existing, tested path
     * ({@code ServerController.startEnvironment} via {@code EnvironmentEnsure}) until Phase 4 relocates
     * it off-UI and deletes the toggle. No second boot path is introduced.
     */
    public interface Actuator {
        /** Ensure the server is up. Idempotent and self-gating: a no-op if already up or still inside
         *  its boot grace, a relaunch only for a stuck past-grace proot. */
        void ensureServerUp();
    }

    private volatile ServerLiveness lastLiveness;
    private volatile boolean lastDesiredUp;
    private volatile Actuator actuator;

    private ServerLifecycleReconciler() {
    }

    /** The foregrounded {@code ServerController} registers here in {@code onResume}. */
    public synchronized void setActuator(Actuator a) {
        this.actuator = a;
    }

    /**
     * Cleared in {@code onPause} — but only if {@code a} is still the current one, so a resume/pause
     * overlap (the next Activity registered before the previous paused) does not clear the live
     * registration. Idempotent.
     */
    public synchronized void clearActuator(Actuator a) {
        if (this.actuator == a) {
            this.actuator = null;
        }
    }

    /**
     * A tick: compute desired, compare to the observed liveness, and log the action that would follow.
     * <b>No actuation in Phase 1.</b> Called on the poll's worker thread; {@code synchronized} keeps the
     * "single-threaded tick" invariant if two polls ever overlap.
     *
     * @param ctx      any context (the poll's Activity today).
     * @param liveness the snapshot the poll just read — reused, not re-probed.
     */
    public synchronized void observe(Context ctx, ServerLiveness liveness) {
        if (ctx == null || liveness == null) {
            return;
        }
        ServerLiveness.Phase actual = liveness.phase(SystemClock.elapsedRealtime());

        SystemFacts facts = SystemFactsReader.read(ctx);
        boolean userWantsOn = new Preferences(ctx).getWatchdogEnable();
        EnvironmentLock.Holder holder = EnvironmentLock.currentHolder(ctx);
        Operation.ExecutionClass holderClass = holder.executionClass;

        boolean desiredUp = ServerReconcile.desired(
                facts.isInstalled(), facts.isHealthy(), userWantsOn, holderClass);
        ServerReconcile.Intent wouldDo = ServerReconcile.intent(desiredUp, actual);

        this.lastLiveness = liveness;
        this.lastDesiredUp = desiredUp;

        // ADFA-5343 (Phase 2): actuate only the "bring it up" direction. START (down) and WAIT (still
        // coming up, or a stuck flap) both route to ensureServerUp(); its EnvironmentEnsure decides
        // launch / leave-in-grace / relaunch-stuck, so a healthy boot is never disturbed and a
        // past-grace flap is re-driven wherever the app is foregrounded (the 5336 fix). STOP stays with
        // the toggle / deep-ops until Phase 4; and desired=DOWN yields neither START nor WAIT, so the
        // reconciler can never fight a legitimate stop.
        // ADFA-5343a (Phase 3B): but DEFER entirely while a self-restarting holder (DASHBOARD's live
        // rebuild) holds — it owns the restart, so any relaunch mid-swap would fight it. The defer is
        // bounded (Holder.selfRestartsServer): the lock releases on every terminal + a stall backstop.
        boolean wouldEnsure = ServerReconcile.ensuresUp(wouldDo);
        boolean ensureUp = ACTUATES
                && ServerReconcile.shouldEnsureUp(wouldDo, holder.selfRestartsServer);
        Actuator a = ensureUp ? this.actuator : null;

        String actNote = !ACTUATES ? ""
                : (wouldEnsure && holder.selfRestartsServer)
                        ? " -> (deferred: " + holder + " self-restarts the server)"
                        : ensureUp ? (a != null ? " -> ensureServerUp()" : " -> (no foreground actuator)")
                        : "";
        Log.i(TAG, "ADFA-5343 reconcile: desired=" + (desiredUp ? "UP" : "DOWN")
                + " actual=" + actual + " wouldDo=" + wouldDo + actNote
                + "  [installed=" + facts.isInstalled() + " healthy=" + facts.isHealthy()
                + " userWantsOn=" + userWantsOn + " holder=" + holder + "/" + holderClass + "]");

        if (a != null) {
            a.ensureServerUp();
        }
    }

    /** The last snapshot observed, or null before the first tick. For later phases / diagnostics. */
    public ServerLiveness lastLiveness() {
        return lastLiveness;
    }

    /** The last desired verdict. For later phases / diagnostics. */
    public boolean lastDesiredUp() {
        return lastDesiredUp;
    }
}
