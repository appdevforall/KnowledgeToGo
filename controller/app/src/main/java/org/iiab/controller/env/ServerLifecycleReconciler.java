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
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.content.ContextCompat;

import org.iiab.controller.PRootEngine;
import org.iiab.controller.Preferences;
import org.iiab.controller.ServerState;
import org.iiab.controller.ServerStateRepository;
import org.iiab.controller.SystemStateEvaluator;
import org.iiab.controller.WatchdogService;
import org.iiab.controller.env.domain.EnvironmentEnsure;
import org.iiab.controller.env.domain.ServerLiveness;
import org.iiab.controller.env.domain.ServerReconcile;
import org.iiab.controller.system.data.SystemFactsReader;
import org.iiab.controller.system.domain.Operation;
import org.iiab.controller.system.domain.SystemFacts;
import org.iiab.controller.util.AppExecutors;

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

    // ADFA-5343 (Phase 4a): the app-scoped background tick — the reconciler's OWN driver, so it acts
    // without a foreground Activity (removing the Phase-2/3 "box returns needs a foreground Activity"
    // limit). It STANDS DOWN while an Activity is foregrounded (actuator != null) — the Activity poll +
    // bridge drive then — and only takes over when actuator == null (backgrounded), where it captures
    // liveness itself and actuates OFF-UI via EnvironmentControl. Runs while the process is alive;
    // WatchdogService (START_STICKY, up while the box is up) keeps the process alive in the background so
    // a flap is re-driven. The foreground boot path (ServerController.doLaunchEnvironment) is untouched
    // in 4a — the two are mutually exclusive on actuator==null, so there is no double-boot.
    private static final long TICK_MS = 3000L;
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private volatile boolean tickStarted;
    private volatile Context appContext;
    private volatile ServerLiveness lastTickLiveness;   // threaded across ticks for the service-downtime clock
    private PRootEngine appScopedEngine;                 // the off-UI boot handle this owner holds (Phase 4a)

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
     * ADFA-5343 (Phase 4a): start the app-scoped background tick. Idempotent; call once from
     * {@link org.iiab.controller.IIABApplication}. The tick fires every {@link #TICK_MS} while the process
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
     * One background tick (IO thread). No-op while an Activity is foregrounded — the Activity poll feeds
     * {@link #observe} and the bridge actuates, exactly as in Phases 2–3. Only when there is NO foreground
     * actuator does the reconciler capture liveness itself, publish it, and actuate OFF-UI.
     */
    private void backgroundTick() {
        Context ctx = appContext;
        if (ctx == null || actuator != null) {
            return;   // foreground owns it; the tick stands down
        }
        long now = SystemClock.elapsedRealtime();
        ServerLiveness live = ServerLiveness.next(
                lastTickLiveness,
                EnvironmentProcess.isRunning(ctx),
                org.iiab.controller.redesign.RestReadiness.apiReady(),
                now, ServerLiveness.DEFAULT_FRESH_MS);
        lastTickLiveness = live;
        ServerLiveness.Phase actual = live.phase(now);

        // Publish for any UI that foregrounds mid-flap — the same fact the Activity poll publishes.
        boolean alive = actual == ServerLiveness.Phase.UP;
        ServerStateRepository.get().post(ServerState.of(alive, SystemStateEvaluator.evaluate(ctx, alive)));

        synchronized (this) {
            if (actuator != null) {
                return;   // re-check under the lock: an Activity may have resumed during the probe
            }
            SystemFacts facts = SystemFactsReader.read(ctx);
            boolean userWantsOn = new Preferences(ctx).getWatchdogEnable();
            EnvironmentLock.Holder holder = EnvironmentLock.currentHolder(ctx);
            boolean desiredUp = ServerReconcile.desired(
                    facts.isInstalled(), facts.isHealthy(), userWantsOn, holder.executionClass);
            ServerReconcile.Intent intent = ServerReconcile.intent(desiredUp, actual);

            // ADFA-5343 (Phase 4b): the one WatchdogService promoter, driven by desired — in the
            // background too, so the process stays alive to keep the box up / re-drive a flap under Doze.
            promoteOrTeardownWatchdog(ctx, desiredUp);

            boolean ensureUp = ACTUATES && ServerReconcile.shouldEnsureUp(intent, holder.selfRestartsServer);

            Log.i(TAG, "ADFA-5343 tick(bg): desired=" + (desiredUp ? "UP" : "DOWN")
                    + " actual=" + actual + " intent=" + intent
                    + (ensureUp ? " -> off-UI ensureServerUp" : "")
                    + "  [holder=" + holder + " userWantsOn=" + userWantsOn + "]");

            if (ensureUp) {
                offUiEnsureUp(ctx, live, now);
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
        EnvironmentEnsure.Action action = EnvironmentEnsure.decide(
                live.processPresent(), live.servicesAnswering(), downMs,
                EnvironmentEnsure.DEFAULT_SERVICE_DOWN_GRACE_MS);
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

        // ADFA-5343 (Phase 4b): the reconciler is the one WatchdogService promoter, driven by desired.
        promoteOrTeardownWatchdog(ctx, desiredUp);

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
