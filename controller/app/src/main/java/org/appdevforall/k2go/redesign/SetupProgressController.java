/*
 * ============================================================================
 * Name        : SetupProgressController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-434 (slice 3b). The provisioning pipeline loop carved out of
 *               SetupProgressActivity: the readiness/orchestration Runnable, the serialized
 *               drain (orchestrateStep) and the pipeline latch state. Activity-scoped (holds the
 *               Activity through a narrow Host, cleared in onDestroy), so it keeps the same
 *               lifecycle the fields had (the Activity itself survives config-changes, so no
 *               ViewModel is needed here). Behavior-preserving: the loop logic is moved verbatim,
 *               with the shared latches read back by the Activity through getters. See
 *               controller/docs/ADR-434-setupprogress-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.appdevforall.k2go.install.presentation.InstallProgressRepository;
import org.appdevforall.k2go.install.presentation.InstallState;
import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;
import org.appdevforall.k2go.install.presentation.ModuleQueueState;
import org.appdevforall.k2go.kolibri.presentation.KolibriProvisioner;
import org.appdevforall.k2go.kolibri.presentation.KolibriSeedRepository;
import org.appdevforall.k2go.system.domain.OperationDispatcher;
import org.appdevforall.k2go.util.AppExecutors;

/**
 * The Activity provides only what the loop cannot do itself: the view refresh, the finishing check,
 * the run-scope predicates (backed by RunScope + the repositories), the environment boot, and a
 * Context for the provisioner drains.
 */
public final class SetupProgressController {

    public interface Host {
        Context context();
        boolean isFinishing();
        void render();
        boolean rebuildInSession();
        boolean moduleInSession();
        boolean serverObservedUp();
        boolean forgejoSeedActive();
        /** ADFA-4842/5011: boot the environment persistently (pdsm start), i.e. serverController.startEnvironment(). */
        void startEnvironmentBoot();
    }

    static final long READY_POLL_MS = 2000L;
    // ADFA-4900: if the maps module queue never reports RUNNING/DONE this long after hand-off, treat
    // it as a start failure so the pipeline can't hang forever waiting on a stage that never began.
    static final long MAPS_START_TIMEOUT_MS = 30000L;
    // ADFA-4842: cap the post-module server-restart wait so the index can never trap the user forever
    // if the server won't come back up; on timeout we proceed to the Library (which owns recovery).
    static final long SERVER_UP_TIMEOUT_MS = 45000L;
    // ADFA-4874: after this many failed readiness polls (~30s at 2s each) the status line switches
    // to a soft "taking longer than expected" message, so a stuck engine doesn't look frozen.
    static final int SLOW_AFTER_POLLS = 15;

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean probing = false;
    private boolean servicesReady = false;
    private boolean drained = false;
    private int readyPolls = 0;   // ADFA-4874: failed readiness polls so far (slow-start message)
    private boolean mapsLaunched = false;   // ADFA-4900: maps (proot) stage has been handed to the queue
    private long mapsLaunchedAt = 0L;       // ADFA-4900: elapsedRealtime when maps was handed off
    private boolean mapsStartFailed = false; // ADFA-4900: queue never started within the timeout
    private boolean moduleLaunched = false;  // ADFA-4842: same shape as the maps stage tracking
    private long moduleLaunchedAt = 0L;
    private boolean moduleStartFailed = false;
    private boolean rebuildRunningSeen = false;   // ADFA-5011: latched once THIS rebuild is seen running
    private boolean rebuildStartKicked = false;   // ADFA-5011: index booted the environment once (actuator)
    private boolean rebuildServerUp = false;      // REST core answered after the rebuild
    private boolean rebuildServerFailed = false;  // services didn't answer within the timeout
    private long rebuildServerAt = 0L;            // elapsedRealtime when the post-success wait began
    private long moduleServerWaitAt = 0L;         // elapsedRealtime when the post-batch wait began (0 = not yet)

    public SetupProgressController(Host host) {
        this.host = host;
    }

    // ---- lifecycle (posted in onResume, cleared in onPause / onDestroy) ----
    /** ADFA-5074: (re)arm the loop. Removing the callback first keeps this to one chain, since the
     *  runnable re-arms itself. */
    public void resume() {
        main.removeCallbacks(readyPoll);
        main.post(readyPoll);
    }

    public void pause() {
        main.removeCallbacks(readyPoll);
    }

    /** Kick the loop once (used after a retry re-banks work). */
    public void kick() {
        main.post(readyPoll);
    }

    // ---- readiness gate + serialized install pipeline (ADFA-4900) ----
    // Once the REST engine is up, run the install tasks as an ORDERED, serialized pipeline:
    // maps (proot / runrole) exclusively first, then ZIM, then Books, auto-continuing between
    // stages HERE (never dropping to Home/Library mid-sequence). Keep polling until every stage
    // has been started and finished; render() then auto-advances (or shows Finish on failure).
    private final Runnable readyPoll = new Runnable() {
        @Override public void run() {
            if (probing) return;
            if (host.isFinishing()) return;
            // ADFA-5011: a dashboard rebuild owns the rootfs (the service does pdsm stop -> build -> swap and
            // leaves the box STOPPED). Skip the normal install readiness/orchestrate path entirely: it has
            // nothing to drain and would see the server still up in the first seconds, declare "nothing to
            // do" and redirect (the original bug). Instead: re-render from the rebuild state; and once the
            // rebuild is terminal, the INDEX boots the environment persistently and waits for it (below).
            if (host.rebuildInSession()) {
                InstallState cur = InstallProgressRepository.get().current();
                boolean rebuiltOk = rebuildRunningSeen && cur.phase == InstallState.Phase.SUCCESS;
                boolean rebuiltFail = rebuildRunningSeen && cur.phase == InstallState.Phase.FAILED;
                // Rebuild done -> the INDEX is the actuator that boots the environment PERSISTENTLY
                // (startEnvironment = 'pdsm start && tail -f /dev/null'), exactly like the module flow.
                // The rebuild service left the box stopped (its transient proots would kill any service
                // they started via --kill-on-exit), so nothing else brings it up. Kick it exactly once.
                if ((rebuiltOk || rebuiltFail) && !rebuildStartKicked) {
                    rebuildStartKicked = true;
                    rebuildServerAt = SystemClock.elapsedRealtime();
                    host.startEnvironmentBoot();
                }
                host.render();   // sets rebuildRunningSeen once the running state is observed
                // On success, probe the REST core (read-only) until it answers or the wait times out, so
                // we redirect only once services are truly up, never onto a dead Home. Reschedule from
                // INSIDE the probe callback (not below): apiReady() can block up to ~5s while the server
                // boots, longer than READY_POLL_MS, and the top `if (probing) return` would otherwise
                // strand the loop if we also scheduled here.
                if (rebuiltOk && !rebuildServerUp && !rebuildServerFailed) {
                    probing = true;
                    AppExecutors.get().io().execute(() -> {
                        final boolean up = RestReadiness.apiReady();
                        main.post(() -> {
                            probing = false;
                            if (host.isFinishing()) return;
                            if (up) rebuildServerUp = true;
                            else if (SystemClock.elapsedRealtime() - rebuildServerAt > SERVER_UP_TIMEOUT_MS) rebuildServerFailed = true;
                            host.render();
                            if (!rebuildServerUp && !rebuildServerFailed) main.postDelayed(readyPoll, READY_POLL_MS);
                        });
                    });
                    return;
                }
                // Building, or terminal-and-settled. Keep polling only while still building.
                boolean settled = rebuiltFail || (rebuiltOk && (rebuildServerUp || rebuildServerFailed));
                if (!settled) main.postDelayed(readyPoll, READY_POLL_MS);
                return;
            }
            // ADFA-4842: a MODULE (solo-proot) install stops the server and runs its OWN proot: there is
            // no REST engine to wait for, and we must NEVER try to "start services" (a second proot) mid-
            // runrole. Skip the REST readiness gate entirely: the runrole queue drives progress, and the
            // server is (re)started only AFTER the queue is DONE, now by the reconciler (desired=UP),
            // observed here via render(). REST and REST+proot (mixed) keep their serialized apiReady path
            // below, untouched.
            if (host.moduleInSession()) {
                orchestrateStep();   // drains on first entry; harmless no-op once the queue is running
                host.render();
                // K2GO-423: keep polling past server-up while a Forgejo seed is pending/running: it
                // starts only once the server is observed up (a live dash-node), and must be driven to
                // completion here, not left for a silent Home drain.
                if (!host.serverObservedUp() || host.forgejoSeedActive()) main.postDelayed(readyPoll, READY_POLL_MS);
                return;
            }
            // ADFA-5074: nothing to start means nothing to wait for. The readiness probe exists so we
            // never POST a job before the engine answers: it is a gate on STARTING work. When every
            // stream is already in flight there is no job to post, and the probe stops being free (an
            // HTTP request to a server busy serving that very download). So: if no provisioner has
            // anything pending, the pipeline has nothing to launch and can advance on what it can see.
            if (!servicesReady && nothingToStart()) servicesReady = true;

            // Once the engine is confirmed up, advance the pipeline on the main thread without
            // re-checking apiReady() over HTTP every tick (the build can run for hours). ADFA-4900/#6.
            if (servicesReady) {
                boolean moreWork = orchestrateStep();
                host.render();
                if (moreWork) main.postDelayed(readyPoll, READY_POLL_MS);
                return;
            }
            probing = true;
            AppExecutors.get().io().execute(() -> {
                final boolean ready = RestReadiness.apiReady();
                main.post(() -> {
                    probing = false;
                    if (host.isFinishing()) return;
                    if (!ready) {
                        readyPolls++;   // ADFA-4874: feeds the slow-start message in render()
                        host.render();
                        main.postDelayed(readyPoll, READY_POLL_MS);
                        return;
                    }
                    servicesReady = true;
                    boolean moreWork = orchestrateStep();
                    host.render();
                    if (moreWork) main.postDelayed(readyPoll, READY_POLL_MS);
                });
            });
        }
    };

    /**
     * ADFA-5074: no stage has anything left to launch. Exactly the "hasPending" questions
     * orchestrateStep() asks before it starts anything, asked in one place so the two cannot drift.
     */
    private boolean nothingToStart() {
        Context c = host.context();
        return !MapsProvisioner.hasPending(c)
                && !ModuleProvisioner.hasPending(c)
                && !ZimProvisioner.hasPending(c)
                && !BooksProvisioner.hasPending(c)
                && !KolibriProvisioner.hasPending(c)
                && !host.forgejoSeedActive();   // K2GO-423: a banked/running Forgejo seed is work to finish
    }

    /** ADFA-5061: whether a drain actually handed work over (see the original for the three outcomes). */
    private static boolean launched(OperationDispatcher.Dispatch verdict) {
        return verdict != null && OperationDispatcher.mayRunStopped(verdict);
    }

    /**
     * ADFA-4900: one step of the serialized install pipeline. Starts the next stage only when the
     * previous one has finished; proot (maps) runs exclusively before any REST download. Returns true
     * while work remains (keep polling), false once every stage has been started and is complete.
     */
    private boolean orchestrateStep() {
        Context c = host.context();
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean queueRunning = ModuleQueueRepository.get().isRunning();

        // Stage 1: proot (maps and/or module management), exclusive of all REST work.
        if (!mapsStartFailed && MapsProvisioner.hasPending(c)) {
            if (!queueRunning) {
                if (launched(MapsProvisioner.drain(c))) {
                    mapsLaunched = true; mapsLaunchedAt = SystemClock.elapsedRealtime();
                } else {
                    mapsStartFailed = true;
                }
            }
            return true;
        }
        if (!moduleStartFailed && ModuleProvisioner.hasPending(c)) {   // ADFA-4842: module management batch
            if (!queueRunning) {
                if (launched(ModuleProvisioner.drain(c))) {
                    moduleLaunched = true; moduleLaunchedAt = SystemClock.elapsedRealtime();
                } else {
                    moduleStartFailed = true;
                }
            }
            return true;
        }
        if (queueRunning) return true;                                      // a runrole in flight
        if (mapsLaunched && !mapsStartFailed && mq.phase != ModuleQueueState.Phase.DONE) {
            // Launched but the queue hasn't reported RUNNING/DONE yet. Wait, but fail closed if it
            // never starts (ADFA-4900/#1) so the pipeline can't hang on a stage that never began.
            if (SystemClock.elapsedRealtime() - mapsLaunchedAt > MAPS_START_TIMEOUT_MS) mapsStartFailed = true;
            else return true;
        }
        if (moduleLaunched && !moduleStartFailed && mq.phase != ModuleQueueState.Phase.DONE) {
            if (SystemClock.elapsedRealtime() - moduleLaunchedAt > MAPS_START_TIMEOUT_MS) moduleStartFailed = true;
            else return true;
        }

        // Stage 2: REST. ADFA-4954: the three live streams serialize against each other (each defers
        // while another holds a session); calling them in a fixed order means the first one starts and
        // the rest retry on a later pass. Keep polling until all are complete.
        if (ZimProvisioner.hasPending(c)) ZimProvisioner.drain(c);
        if (BooksProvisioner.hasPending(c)) BooksProvisioner.drain(c);
        if (KolibriProvisioner.hasPending(c)) KolibriProvisioner.drain(c);
        // K2GO-423: the Forgejo seed is a chained REST-stage sibling. It starts only when the forgejo
        // runrole SUCCEEDED (queue DONE, forgejo not failed), the module server is observed up, and no
        // session is open yet. This replaces the old silent Home drain (ForgejoSeedProvisioner).
        if (org.appdevforall.k2go.forgejo.data.ForgejoInstallPrefs.isSeedPending(c)
                && mq.phase == ModuleQueueState.Phase.DONE && !mq.didFail("forgejo")
                && host.serverObservedUp()
                && !org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository.get().hasSession()) {
            org.appdevforall.k2go.forgejo.presentation.ForgejoSeedService.start(c);
        }
        KolibriSeedRepository kolibri = KolibriSeedRepository.get();
        boolean restBusy = (ZimDownloadService.hasSession() && !ZimDownloadService.isComplete())
                || (BooksDownloadService.hasSession() && !BooksDownloadService.isComplete())
                || (kolibri.hasSession() && !kolibri.isComplete())
                || ZimProvisioner.hasPending(c) || BooksProvisioner.hasPending(c)
                || KolibriProvisioner.hasPending(c)
                || host.forgejoSeedActive();   // K2GO-423: keep the pipeline busy until the seed is terminal
        if (restBusy) return true;

        // Every stage has been started and is complete.
        drained = true;
        return false;
    }

    // ---- state read back by the Activity's render()/predicates (loop is the sole writer) ----
    public boolean servicesReady() { return servicesReady; }
    public boolean drained() { return drained; }
    public int readyPolls() { return readyPolls; }
    public boolean mapsLaunched() { return mapsLaunched; }
    public boolean mapsStartFailed() { return mapsStartFailed; }
    public boolean moduleLaunched() { return moduleLaunched; }
    public boolean moduleStartFailed() { return moduleStartFailed; }
    public boolean rebuildServerUp() { return rebuildServerUp; }
    public boolean rebuildServerFailed() { return rebuildServerFailed; }
    public long moduleServerWaitAt() { return moduleServerWaitAt; }

    /** ADFA-5011: renderRebuild() latches this once it observes the running state. */
    public void markRebuildRunningSeen() { rebuildRunningSeen = true; }
    public boolean rebuildRunningSeen() { return rebuildRunningSeen; }

    /**
     * ADFA-5343 (Phase 2): a module batch stopped the server for its runroles; when the batch is
     * terminal the server should come back. Record the wait anchor once (also the "taking longer"
     * timeout anchor). The caller (Activity) sets desired=UP and, in the reconciler-rollback path,
     * boots here. Returns true the first time (so the caller performs its one-time side effects).
     */
    public boolean beginModuleServerWait() {
        if (moduleServerWaitAt != 0L) return false;
        moduleServerWaitAt = SystemClock.elapsedRealtime();
        return true;
    }
}
