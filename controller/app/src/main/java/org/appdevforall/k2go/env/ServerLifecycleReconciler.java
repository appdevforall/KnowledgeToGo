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
package org.appdevforall.k2go.env;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.PRootEngine;
import org.appdevforall.k2go.Preferences;
import org.appdevforall.k2go.ServerState;
import org.appdevforall.k2go.ServerStateRepository;
import org.appdevforall.k2go.SystemStateEvaluator;
import org.appdevforall.k2go.WatchdogService;
import org.appdevforall.k2go.env.domain.EnvironmentEnsure;
import org.appdevforall.k2go.env.domain.ServerLiveness;
import org.appdevforall.k2go.env.domain.ServerReconcile;
import org.appdevforall.k2go.system.data.SystemFactsReader;
import org.appdevforall.k2go.system.domain.SystemFacts;
import org.appdevforall.k2go.util.AppExecutors;

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

    private volatile boolean lastDesiredUp;
    private long serverUpSinceMs;   // ADFA-5343 (Phase 4d-2): wall-clock, for the server-uptime analytics
                                    // migrated off the deleted ServerController poll.

    // ADFA-5343 (Phase 4a/4d-2): the app-scoped tick — the reconciler's SINGLE driver, foreground AND
    // background (4d-2 removed the ServerController poll + the foreground bridge, so there is no
    // stand-down: this is the one liveness capture + publisher + actuator). It captures liveness, publishes
    // ServerStateRepository, and actuates OFF-UI via EnvironmentControl. Runs while the process is alive;
    // WatchdogService (START_STICKY, up while the box is up) keeps the process alive in the background so a
    // flap is re-driven with no Activity. (ServerController's own boot survives only for the recovery path
    // :394 — Phase 5.)
    private static final long TICK_MS = 3000L;
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private volatile boolean tickStarted;
    private volatile Context appContext;
    private volatile ServerLiveness lastTickLiveness;   // threaded across ticks for the service-downtime clock
    private PRootEngine appScopedEngine;                 // the off-UI boot handle this owner holds (Phase 4a)
    // ADFA-5343 (Phase 4c): serialises the reconciler's async STOP so it fires once per turn-off, not
    // every tick during the ~40s graceful pdsm stop. Subsumes ServerController.stopping (deleted in 4d).
    private volatile boolean reconcilerStopping;

    private ServerLifecycleReconciler() {
    }

    /**
     * ADFA-5343 (Phase 4a): start the app-scoped background tick. Idempotent; call once from
     * {@link org.appdevforall.k2go.IIABApplication}. The tick fires every {@link #TICK_MS} while the process
     * is alive; each fire hops to an IO thread (the liveness probe can block ~2.5s) and stands down unless
     * the app is backgrounded (no foreground actuator).
     */
    public void startBackgroundTick(Context appCtx) {
        if (appCtx == null || tickStarted) {
            return;
        }
        this.appContext = appCtx.getApplicationContext();
        tickStarted = true;
        tickHandler.post(tickRunnable);
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            AppExecutors.get().io().execute(ServerLifecycleReconciler.this::backgroundTick);
            tickHandler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * One tick (IO thread) — the single driver, foreground and background (Phase 4d-2). Captures one
     * liveness snapshot, publishes {@link ServerStateRepository} (the one publisher now the poll is gone),
     * and reconciles: promote/teardown the watchdog, and actuate OFF-UI (up or stop) per desired.
     */
    private void backgroundTick() {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        ServerLiveness live = ServerLiveness.next(
                lastTickLiveness,
                EnvironmentProcess.isRunning(ctx),
                org.appdevforall.k2go.redesign.RestReadiness.apiReady(),
                now, ServerLiveness.DEFAULT_FRESH_MS);
        lastTickLiveness = live;
        ServerLiveness.Phase actual = live.phase(now);

        // Publish liveness for the UI (the sole publisher now the ServerController poll is deleted) and
        // fire the server-uptime analytics on an alive transition (migrated off that poll).
        boolean alive = actual == ServerLiveness.Phase.UP;
        boolean wasAlive = ServerStateRepository.get().current().alive;
        if (alive && !wasAlive) {
            serverUpSinceMs = System.currentTimeMillis();
            org.appdevforall.k2go.analytics.AnalyticsClient.with(ctx).logServerStarted();
        } else if (!alive && wasAlive) {
            long uptime = serverUpSinceMs > 0L ? System.currentTimeMillis() - serverUpSinceMs : -1L;
            serverUpSinceMs = 0L;
            org.appdevforall.k2go.analytics.AnalyticsClient.with(ctx).logServerStopped(uptime);
        }
        ServerStateRepository.get().post(ServerState.of(alive, SystemStateEvaluator.evaluate(ctx, alive)));

        synchronized (this) {
            SystemFacts facts = SystemFactsReader.read(ctx);
            boolean userWantsOn = new Preferences(ctx).getWatchdogEnable();
            EnvironmentLock.Holder holder = EnvironmentLock.currentHolder(ctx);
            // ADFA-5343 (Phase 5a): desired no longer reads facts.isHealthy() — an interrupted-but-maybe-fine
            // base must be tried, not blocked by its own unknown health (see ServerReconcile.desired).
            boolean desiredUp = ServerReconcile.desired(
                    facts.isInstalled(), userWantsOn, holder.executionClass);
            this.lastDesiredUp = desiredUp;   // the header's "Starting" reads this (isServerStarting)
            ServerReconcile.Intent intent = ServerReconcile.intent(desiredUp, actual);

            // ADFA-5343 (Phase 4b): the one WatchdogService promoter, driven by desired — in the
            // background too, so the process stays alive to keep the box up / re-drive a flap under Doze.
            promoteOrTeardownWatchdog(ctx, desiredUp);

            boolean ensureUp = ACTUATES && ServerReconcile.shouldEnsureUp(intent, holder.selfRestartsServer);
            boolean doStop = ACTUATES && ServerReconcile.shouldStop(intent, holder == EnvironmentLock.Holder.NONE);

            Log.i(TAG, "ADFA-5343 tick: desired=" + (desiredUp ? "UP" : "DOWN")
                    + " actual=" + actual + " intent=" + intent
                    + (ensureUp ? " -> off-UI ensureServerUp" : doStop ? " -> off-UI STOP" : "")
                    + "  [holder=" + holder + " userWantsOn=" + userWantsOn + "]");

            if (ensureUp) {
                offUiEnsureUp(ctx, live, now);
            } else if (doStop) {
                actuateStop(ctx);
            }
        }
    }

    /**
     * The OFF-UI actuation (Phase 4a): the same {@link EnvironmentEnsure} decision the foreground boot
     * uses, launching the app-scoped {@link EnvironmentControl#start} engine this owner holds — no
     * Activity. EnvironmentEnsure keeps it idempotent (LAUNCH only when nothing runs; relaunch only a
     * stuck past-grace proot), so it never stacks a second proot. Resets the tick's downtime clock after a
     * launch so the fresh proot gets its full grace. Caller holds {@code this} monitor.
     */
    /**
     * ADFA-5343 (Phase 4c): the user button's set-desired API. Turn-on/off write the one persisted intent
     * ({@code WatchdogEnable}); the reconciler does the rest (boot on desired=UP, stop on desired=DOWN).
     * Replaces the toggle's start-XOR-stop-on-a-cache.
     */
    public void setUserWantsOn(Context ctx, boolean on) {
        new Preferences(ctx).setWatchdogEnable(on);
    }

    /**
     * ADFA-5343 (Phase 4d): act NOW instead of on the next tick — for the user's last-defense Retry, after
     * {@link #setUserWantsOn}(true). Runs an immediate reconcile (the single tick is the one actuator now).
     */
    public void requestReconcileNow() {
        tickHandler.post(tickRunnable);
    }

    /**
     * ADFA-5343 (Phase 4c): the reconciler's STOP — a graceful {@code pdsm stop} then kill the proot so
     * the box reaches phase DOWN (a user turn-off, honored by the owner instead of a bespoke inline stop).
     * Serialised by {@link #reconcilerStopping} so it fires once, not every tick through the ~40s stop.
     * Only reached when {@link ServerReconcile#shouldStop} is true (desired down, box up, holder NONE) —
     * a deep op holds a STOPPED-class lock and quiesces itself, so this never double-stops it.
     */
    private void actuateStop(Context ctx) {
        if (reconcilerStopping) {
            return;
        }
        reconcilerStopping = true;
        Log.i(TAG, "ADFA-5343 reconcile: STOP (desired=DOWN, no holder) — graceful pdsm stop + teardown");
        EnvironmentControl.stop(ctx, line -> Log.i(TAG, line), () -> {
            PRootEngine e = appScopedEngine;
            appScopedEngine = null;
            if (e != null) {
                e.killProcess();
            }
            EnvironmentProcess.killOrphan(ctx);   // ensure the proot is gone (whoever launched it) -> phase DOWN
            lastTickLiveness = null;              // fresh liveness clock after the teardown
            reconcilerStopping = false;
        });
    }

    /**
     * ADFA-5343 (Phase 4b): the reconciler is the ONE promoter of {@link WatchdogService} — up while the
     * server should be up (the foreground service + wakelock keep the process alive so the tick survives
     * Doze and re-drives a flap), down otherwise (deep ops run their own foreground services; a user-off /
     * uninstalled box needs no keeper). Edge-detected via the service's own {@link WatchdogService#isRunning}
     * state — no reconciler-side flag — so it is safe to call on every reconcile and self-corrects a
     * START_STICKY-revived service. At targetSdk 28 the app may start a foreground service from the
     * background (the API-31 restriction does not apply); revisit if the targetSdk is raised.
     */
    private void promoteOrTeardownWatchdog(Context ctx, boolean desiredUp) {
        Context app = ctx.getApplicationContext();
        if (desiredUp && !WatchdogService.isRunning()) {
            ContextCompat.startForegroundService(app,
                    new Intent(app, WatchdogService.class).setAction(WatchdogService.ACTION_START));
        } else if (!desiredUp && WatchdogService.isRunning()) {
            app.startService(new Intent(app, WatchdogService.class).setAction(WatchdogService.ACTION_STOP));
        }
    }

    private void offUiEnsureUp(Context ctx, ServerLiveness live, long now) {
        long downMs = live.servicesDownMs(now, ServerLiveness.DEFAULT_FRESH_MS);
        // ADFA-5365: a boot is judged on movement, not elapsed time. The progress fact belongs to the
        // environment, so a boot started by the foreground actuator and then backgrounded is still
        // seen as moving here; with no signal (an orphan we did not launch, or a fresh process)
        // decide() falls back to the downtime rule that already recovers orphans.
        long silentMs = EnvironmentProgress.silentMs(now);
        EnvironmentEnsure.Action action = EnvironmentEnsure.decide(
                live.processPresent(), live.servicesAnswering(), live.booting(), downMs, silentMs);
        if (action == EnvironmentEnsure.Action.KILL_AND_RELAUNCH) {
            EnvironmentProcess.killOrphan(ctx);
        } else if (action != EnvironmentEnsure.Action.LAUNCH) {
            return;   // NOOP_HEALTHY / WAIT_BOOT_GRACE — leave it
        }
        if (appScopedEngine != null) {
            appScopedEngine.killProcess();
        }
        appScopedEngine = EnvironmentControl.start(ctx, line -> Log.i(TAG, line));
        lastTickLiveness = null;   // restart the downtime clock so the fresh proot gets its full grace
    }

    /** The last desired verdict — the header's "Starting" reads this (LibraryActivity.isServerStarting). */
    public boolean lastDesiredUp() {
        return lastDesiredUp;
    }
}
