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

    private volatile ServerLiveness lastLiveness;
    private volatile boolean lastDesiredUp;

    private ServerLifecycleReconciler() {
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

        Log.i(TAG, "ADFA-5343 reconcile (log-only): desired=" + (desiredUp ? "UP" : "DOWN")
                + " actual=" + actual + " wouldDo=" + wouldDo
                + "  [installed=" + facts.isInstalled() + " healthy=" + facts.isHealthy()
                + " userWantsOn=" + userWantsOn + " holder=" + holder + "/" + holderClass + "]");
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
