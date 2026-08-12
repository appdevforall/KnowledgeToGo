/*
 * ============================================================================
 * Name        : SetupProgressActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. "Finishing setup" — the visible post-install provisioning screen.
 *               A status dot (like Library's) shows "Starting services…" until the REST engine is
 *               up; only then does the drain start (honest: no spinner spins before the system can
 *               download). One row per stream (Wikipedia/ZIM, Books) shown from the start: waiting
 *               dot → active spinner → green check (done) / amber alert (failed). Tapping a row
 *               opens that stream's REAL detail card. When everything succeeds it auto-redirects to
 *               the library after a short countdown (Cancel keeps you here and reveals Finish); on
 *               failure it shows Finish plus a note pointing to the (future) background jobs monitor.
 *               Leaving via Run in background lets the Library keep the provisioning going.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;
import org.iiab.controller.install.presentation.InstallProgressRepository;
import org.iiab.controller.install.presentation.InstallState;
import org.iiab.controller.install.presentation.ModuleQueueRepository;
import org.iiab.controller.install.presentation.ModuleQueueState;
import org.iiab.controller.kolibri.presentation.KolibriProvisioner;
import org.iiab.controller.kolibri.presentation.KolibriSeedRepository;
import org.iiab.controller.kolibri.presentation.KolibriSeedService;
import org.iiab.controller.kolibri.presentation.KolibriSeedState;
import org.iiab.controller.kolibri.presentation.KolibriSeedingFragment;
import org.iiab.controller.system.data.PendingContent;
import org.iiab.controller.system.domain.OperationDispatcher;
import org.iiab.controller.system.domain.ContentType;
import org.iiab.controller.util.Snackbars;
import org.iiab.controller.util.AppExecutors;

public class SetupProgressActivity extends AppCompatActivity implements org.iiab.controller.ServerController.Host {

    // ADFA-5074: two extras used to live here — EXTRA_OPEN_STREAM (ADFA-4987, a notification
    // deep-linking to a stream's detail) and EXTRA_HINT_STREAM (ADFA-4988, a confirm asking for
    // its detail if it was the only stream running). Both are gone, and with them the question
    // "where does this download land?", which used to have three answers depending on state the
    // user could not see.
    //
    // This screen is the router. It is the only surface that can end a run — Finish, the
    // countdown to the Library, Run in background — and the real usage is set-and-forget: the
    // downloads take hours, so the user starts one, leaves, and comes back to ask "is it going
    // well?". One destination answers that whatever brought them here. A detail is a deliberate
    // zoom-in, reached by tapping a row, never somewhere you arrive.

    /** ADFA-5011: this screen is driving a dash-node REST-core rebuild (not an install/content drain).
     *  Latched so the screen stays on the animation and blocks leaving until the rebuild is SUCCESS/FAILED. */
    public static final String EXTRA_REBUILD = "rebuild";

    private static final long READY_POLL_MS = 2000L;
    private static final long REDIRECT_MS = 3000L;
    // ADFA-4900: if the maps module queue never reports RUNNING/DONE this long after hand-off, treat
    // it as a start failure so the pipeline can't hang forever waiting on a stage that never began.
    private static final long MAPS_START_TIMEOUT_MS = 30000L;
    // ADFA-4842: cap the post-module server-restart wait so the index can never trap the user forever
    // if the server won't come back up; on timeout we proceed to the Library (which owns recovery).
    private static final long SERVER_UP_TIMEOUT_MS = 45000L;
    // ADFA-4874: after this many failed readiness polls (~30s at 2s each) the status line switches
    // to a soft "taking longer than expected" message, so a stuck engine doesn't look frozen.
    private static final int SLOW_AFTER_POLLS = 15;

    private View dot;
    private TextView statusText, redirect, cancel, finishNote, contextText;
    private LinearLayout sections;
    private Button finishBtn, runBgBtn, detailRunBgBtn;
    private View detailRoot, indexScroll;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean servicesReady = false;
    private boolean drained = false;
    private boolean redirectCancelled = false;
    private boolean redirectScheduled = false;
    private boolean showingDetail = false;
    private boolean leaveWarned = false;   // ADFA-4919 (2c): captured the first exit-Back once
    private boolean probing = false;
    private boolean rebuildSeen = false;    // ADFA-5011: latched once this screen is a rebuild session
    private boolean rebuildRunningSeen = false;   // ADFA-5011: latched once we've seen THIS rebuild running,
    //  so a STALE terminal state from a previous rebuild can't trigger a premature done/redirect on entry
    // ADFA-5011: after the rebuild build+swap succeeds, WAIT for the REST core to actually answer before
    // redirecting — else we land on a dead Home while pdsm-started services are still coming up. We only
    // POLL apiReady() here (read-only); the service already did pdsm start, so we never toggle/stop.
    private boolean rebuildStartKicked = false;     // ADFA-5011: index booted the environment once (actuator)
    private boolean rebuildServerUp = false;        // REST core answered after the rebuild
    private boolean rebuildServerFailed = false;    // services didn't answer within the timeout
    private long rebuildServerAt = 0L;              // elapsedRealtime when the post-success wait began
    private boolean mapsLaunched = false;   // ADFA-4900: maps (proot) stage has been handed to the queue
    private long mapsLaunchedAt = 0L;       // ADFA-4900: elapsedRealtime when maps was handed off
    private boolean mapsStartFailed = false; // ADFA-4900: queue never started within the timeout
    private boolean mapsSeen = false;        // ADFA-4919: latched once the proot (maps) stage is seen
    // ADFA-4842: module management (non-maps proot modules) — same shape as the maps stage tracking.
    private boolean moduleLaunched = false;
    private long moduleLaunchedAt = 0L;
    private boolean moduleStartFailed = false;
    private boolean moduleSeen = false;      // latched once a non-maps proot batch is seen
    private int readyPolls = 0;   // ADFA-4874: failed readiness polls so far (slow-start message)
    // ADFA-4842: a real module batch stops the server (pdsm stop) for its runroles. When the queue is
    // DONE, the index restarts the server and WAITS here — showing "Starting services…" — until the REST
    // core answers, then completes/redirects to an already-live Library. Owns a ServerController (Host)
    // just to issue that start; the server proot is process-scoped so it survives into LibraryActivity.
    private org.iiab.controller.ServerController serverController;
    private Boolean targetServerState = null;         // ServerController.Host state
    private boolean moduleRestartKicked = false;      // handleServerLaunchClick issued once
    private boolean moduleServerUp = false;           // REST core answered after the restart
    private boolean moduleServerFailed = false;       // restart timed out — surface as failure, not silent success
    private long moduleRestartAt = 0L;                // elapsedRealtime when the restart was kicked
    private org.iiab.controller.util.EllipsisAnimator statusEllipsis;   // ADFA-4842: animated "…" on the amber wait line

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_k2go_setup_progress);

        dot = findViewById(R.id.k2go_sp_dot);
        statusText = findViewById(R.id.k2go_sp_status);
        statusEllipsis = new org.iiab.controller.util.EllipsisAnimator(statusText);
        sections = findViewById(R.id.k2go_sp_sections);
        redirect = findViewById(R.id.k2go_sp_redirect);
        cancel = findViewById(R.id.k2go_sp_cancel);
        finishBtn = findViewById(R.id.k2go_sp_finish);
        finishNote = findViewById(R.id.k2go_sp_finish_note);
        contextText = findViewById(R.id.k2go_sp_context);
        runBgBtn = findViewById(R.id.k2go_sp_runbg);
        indexScroll = findViewById(R.id.k2go_sp_index);
        detailRoot = findViewById(R.id.k2go_sp_detail);

        finishBtn.setOnClickListener(v -> goHome(true));
        runBgBtn.setOnClickListener(v -> finish());   // leave; the Library keeps provisioning going
        cancel.setOnClickListener(v -> { redirectCancelled = true; cancelRedirect(); render(); });

        Button back = findViewById(R.id.k2go_sp_back);
        back.setOnClickListener(v -> backToIndex());
        detailRunBgBtn = findViewById(R.id.k2go_sp_detail_finish);
        detailRunBgBtn.setText(R.string.k2go_zim_run_bg);   // in a detail, secondary = leave (never abort)
        detailRunBgBtn.setOnClickListener(v -> finish());

        // ADFA-4842: own a ServerController so the index can restart the server after a module batch
        // (it was pdsm-stopped for the runroles) and keep ServerStateRepository fresh so the start
        // toggle can't misfire. The server proot is process-scoped, so it survives into LibraryActivity.
        serverController = new org.iiab.controller.ServerController(this, this);
        serverController.start();

        // ADFA-4919: observe the maps (proot) queue so its RUNNING -> DONE transition always
        // re-renders the index. The REST streams have service listeners; the proot stage had none,
        // so a proot-only install could finish without the index ever updating to Finish/redirect.
        ModuleQueueRepository.get().state().observe(this, st -> render());
        // ADFA-4954: the Kolibri stream publishes state instead of pinging a listener,
        // so the index observes it here. Without this the row only refreshed while the
        // readiness poll happened to be ticking, and froze the moment it stopped.
        KolibriSeedRepository.get().state().observe(this, st -> render());

        // ADFA-5011: observe the rebuild pipeline so its running→terminal transitions re-render (and,
        // on SUCCESS, trigger the redirect). Guarded so it only acts while this is a rebuild session.
        InstallProgressRepository.get().state().observe(this, st -> { if (rebuildInSession()) render(); });

    }

    /**
     * ADFA-5074: a second start while this instance is alive.
     *
     * <p>The activity is {@code singleTask}, so tapping a download notification when the screen
     * already exists arrives here rather than in {@code onCreate}. There is no routing left to
     * do — every entry point lands on this screen — so this only keeps {@code getIntent()}
     * current, which {@code rebuildInSession()} and the rest read.
     *
     * <p>Deliberately does not force the index when a detail is open. The notification means
     * "take me back to my download", and someone who is inside a detail is already there, one
     * level deeper by their own choice; yanking them out would be the screen overruling them.
     *
     * <p>And deliberately does not render. This runs before {@code onStart}, so the
     * FragmentManager still has its state saved — and {@code render()} is no longer free of
     * transactions: it can call {@code backToIndex()}, whose {@code commitNow()} would throw
     * exactly the {@code IllegalStateException} the routing this replaced had to defer around.
     * {@code onResume} follows immediately and renders anyway, so there was nothing to gain.
     */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) {
            return;
        }
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (serverController != null) serverController.onResume();   // ADFA-4842: keep ServerState fresh
        // ADFA-5074: the pipeline runs whatever is on top. It used to be started only when the
        // index was showing, which was fine while a detail could only be opened by tapping a row
        // — you had already been on the index, so the loop was already going. The hint route
        // broke that: a Get More download now opens its detail during onCreate, so onResume found
        // showingDetail already true and never posted the poll at all. Nothing advanced, and the
        // run could not complete while the user watched it. Removing the callback first keeps
        // this to one chain, since the runnable re-arms itself.
        main.removeCallbacks(readyPoll);
        main.post(readyPoll);
        if (!showingDetail) {
            // The listeners are the one thing that IS about who is on screen: while a detail is
            // open the fragment owns the single listener slot (see backToIndex).
            ZimDownloadService.setListener(this::render);
            BooksDownloadService.setListener(this::render);
            render();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(readyPoll);
        main.removeCallbacks(serverUpPoll);   // ADFA-4842
        if (statusEllipsis != null) statusEllipsis.stop();   // ADFA-4842
        cancelRedirect();
        if (serverController != null) serverController.onPause();   // ADFA-4842: stop the status poll (not the server)
        if (!showingDetail) {
            ZimDownloadService.setListener(null);
            BooksDownloadService.setListener(null);
        }
    }

    @Override
    public void onBackPressed() {
        if (showingDetail) { backToIndex(); return; }
        // ADFA-5011: a rebuild owns the rootfs and can't be abandoned mid-run — same gate as proot. Block
        // while building AND through the post-success wait for services to come up (so we never drop the
        // user onto a Library showing a half-rebuilt / not-yet-started server). First Back reassures; a
        // second backgrounds the app (the rebuild keeps going and reopening resumes here).
        if (rebuildInSession()) {
            InstallState rst = InstallProgressRepository.get().current();
            boolean rebuiltOk = rebuildRunningSeen && rst.phase == InstallState.Phase.SUCCESS;
            boolean stillWorking = InstallProgressRepository.get().isRunning()
                    || (rebuiltOk && !rebuildServerUp && !rebuildServerFailed);
            if (stillWorking) {
                if (!leaveWarned) {
                    leaveWarned = true;
                    Snackbars.make(findViewById(android.R.id.content), R.string.k2go_setup_leave_hint).show();
                } else {
                    moveTaskToBack(true);
                }
                return;
            }
        }
        // ADFA-4919 (2c): the index is the LAST barrier for a proot install (runs on the live system,
        // can't be abandoned mid-run). No up-front confirm (that would spoil the friendly flow). The
        // FIRST Back reassures via a snackbar; every Back after that sends the whole app to the
        // background (home) -- we never walk back through the selection/hub steps into a half-built
        // flow. The install keeps running; reopening the app resumes here.
        // ADFA-4842: a module (solo-proot) install locks the index for its WHOLE session — moduleInSession()
        // is durable (survives reopen) and has no gaps, so Back can never walk back through the hub/Settings
        // steps into a half-built flow. prootActive() keeps covering maps/mixed as before.
        if (moduleInSession() || prootActive()) {
            if (!leaveWarned) {
                leaveWarned = true;
                Snackbars.make(findViewById(android.R.id.content), R.string.k2go_setup_leave_hint).show();
            } else {
                moveTaskToBack(true);
            }
            return;
        }
        super.onBackPressed();
    }

    /** ADFA-4919/4842: is a proot stage still blocking the index? True while a runrole is queued/running
     *  AND, for a module batch, through the post-DONE server restart — the user must not background or
     *  Back out (there is no "Run in background" for proot) until the server is confirmed back up
     *  (moduleServerUp) or the wait times out. */
    private boolean prootActive() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean mapsTerminal = mapsStartFailed || (mapsInSession() && mq.phase == ModuleQueueState.Phase.DONE);
        boolean mapsActive = mapsInSession() && !mapsTerminal;
        // A module session stays active from its runroles through the server restart that follows.
        boolean moduleActive = (moduleInSession() || moduleStartFailed) && !moduleServerUp;
        return mapsActive || moduleActive;
    }

    /** ADFA-4919: is the proot (maps) stage part of THIS install session? Latched from the DURABLE
     *  module queue (app-scoped) + wishlist, so a fresh index instance — e.g. reopened from the
     *  notification while the queue is still running — still shows the stage, hides "Run in
     *  background", and reaches completion, even though it never called drain() itself. (The queue
     *  runs proot modules one at a time; today that is only maps.) */
    private boolean mapsInSession() {
        // ADFA-4842: maps-SPECIFIC now (was any running queue). A non-maps module batch must not
        // render as the "Maps" stage, so latch only on the maps provisioner/launch or a running
        // queue whose current module is maps.
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        if (mapsLaunched || mapsStartFailed
                || MapsProvisioner.hasPending(this)
                || (ModuleQueueRepository.get().isRunning() && "maps".equals(mq.currentModule))) {
            mapsSeen = true;
        }
        return mapsSeen;
    }

    /** ADFA-4842: is a non-maps proot module batch part of THIS session? Latched from the durable
     *  batch (ModuleBatch) + provisioner + a running queue on a non-maps module, so a reopened index
     *  still renders the module rows and reaches completion. */
    private boolean moduleInSession() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        if (moduleLaunched || moduleStartFailed
                || ModuleProvisioner.hasPending(this)
                || ModuleBatch.has(this)
                || (ModuleQueueRepository.get().isRunning() && mq.currentModule != null && !"maps".equals(mq.currentModule))) {
            moduleSeen = true;
        }
        return moduleSeen;
    }

    /** ADFA-5011: is a dash-node rebuild the operation driving THIS screen? Latched from the launch
     *  extra (primary signal) or a LIVE REBUILD op in InstallProgressRepository (covers a reopen while
     *  the rebuild runs; a stale terminal REBUILD is excluded by the isRunning() check). Once latched it
     *  stays for the screen's life so the terminal result (done/failed) is shown, not skipped. */
    private boolean rebuildInSession() {
        if (rebuildSeen) return true;
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REBUILD, false)) { rebuildSeen = true; return true; }
        if (InstallProgressRepository.get().currentOp() == InstallState.Op.REBUILD
                && InstallProgressRepository.get().isRunning()) { rebuildSeen = true; return true; }
        return false;
    }

    // ---- readiness gate + serialized install pipeline (ADFA-4900) ----
    // Once the REST engine is up, run the install tasks as an ORDERED, serialized pipeline:
    // maps (proot / runrole) exclusively first, then ZIM, then Books, auto-continuing between
    // stages HERE (never dropping to Home/Library mid-sequence). Keep polling until every stage
    // has been started and finished; render() then auto-advances (or shows Finish on failure).
    private final Runnable readyPoll = new Runnable() {
        @Override public void run() {
            if (probing) return;
            if (isFinishing()) return;
            // ADFA-5011: a dashboard rebuild owns the rootfs (the service does pdsm stop → build → swap and
            // leaves the box STOPPED). Skip the normal install readiness/orchestrate path entirely — it has
            // nothing to drain and would see the server still up in the first seconds, declare "nothing to
            // do" and redirect (the original bug). Instead: re-render from the rebuild state; and once the
            // rebuild is terminal, the INDEX boots the environment persistently and waits for it (below).
            if (rebuildInSession()) {
                InstallState cur = InstallProgressRepository.get().current();
                boolean rebuiltOk = rebuildRunningSeen && cur.phase == InstallState.Phase.SUCCESS;
                boolean rebuiltFail = rebuildRunningSeen && cur.phase == InstallState.Phase.FAILED;
                // Rebuild done → the INDEX is the actuator that boots the environment PERSISTENTLY
                // (startEnvironment = 'pdsm start && tail -f /dev/null'), exactly like the module flow.
                // The rebuild service left the box stopped (its transient proots would kill any service
                // they started via --kill-on-exit), so nothing else brings it up. Kick it exactly once.
                if ((rebuiltOk || rebuiltFail) && !rebuildStartKicked) {
                    rebuildStartKicked = true;
                    rebuildServerAt = SystemClock.elapsedRealtime();
                    serverController.startEnvironment();
                }
                render();   // sets rebuildRunningSeen once the running state is observed
                // On success, probe the REST core (read-only) until it answers or the wait times out —
                // so we redirect only once services are truly up, never onto a dead Home. Reschedule from
                // INSIDE the probe callback (not below): apiReady() can block up to ~5s while the server
                // boots — longer than READY_POLL_MS — and the top `if (probing) return` would otherwise
                // strand the loop if we also scheduled here.
                if (rebuiltOk && !rebuildServerUp && !rebuildServerFailed) {
                    probing = true;
                    AppExecutors.get().io().execute(() -> {
                        final boolean up = RestReadiness.apiReady();
                        main.post(() -> {
                            probing = false;
                            if (isFinishing()) return;
                            if (up) rebuildServerUp = true;
                            else if (SystemClock.elapsedRealtime() - rebuildServerAt > SERVER_UP_TIMEOUT_MS) rebuildServerFailed = true;
                            render();
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
            // ADFA-4842: a MODULE (solo-proot) install stops the server and runs its OWN proot — there is
            // no REST engine to wait for, and we must NEVER try to "start services" (a second proot) mid-
            // runrole. Skip the REST readiness gate entirely: the runrole queue drives progress, and the
            // server is (re)started only AFTER the queue is DONE (ensureServerUpForModules, via render()).
            // REST and REST+proot (mixed) keep their serialized apiReady path below, untouched.
            if (moduleInSession()) {
                orchestrateStep();   // drains on first entry; harmless no-op once the queue is running
                render();
                if (!moduleServerUp) main.postDelayed(readyPoll, READY_POLL_MS);
                return;
            }
            // ADFA-5074: nothing to start means nothing to wait for. The readiness probe exists so
            // we never POST a job before the engine answers — it is a gate on STARTING work. When
            // every stream is already in flight there is no job to post, and the probe stops being
            // free: it is an HTTP request to a server that is busy serving the very download it is
            // being asked about. Observed on device with the network throttled — a finished ZIM and
            // a finished Courses run both sat under "Starting services." with a green Done row,
            // because apiReady() kept timing out at 2.5s, servicesReady stayed false, orchestrateStep
            // never ran, `drained` was never set, and the completion the screen was waiting for could
            // not be reached. Both redirected the moment the link freed up, minutes late.
            //
            // So: if no provisioner has anything pending, the pipeline has nothing to launch and can
            // advance on what it can already see. This is also the truthful answer — the box is
            // demonstrably up, it is downloading — and it stops the header claiming otherwise.
            if (!servicesReady && nothingToStart()) servicesReady = true;

            // Once the engine is confirmed up, advance the pipeline on the main thread without
            // re-checking apiReady() over HTTP every tick (the build can run for hours). ADFA-4900/#6.
            if (servicesReady) {
                boolean moreWork = orchestrateStep();
                render();
                if (moreWork) main.postDelayed(readyPoll, READY_POLL_MS);
                return;
            }
            probing = true;
            AppExecutors.get().io().execute(() -> {
                final boolean ready = RestReadiness.apiReady();
                main.post(() -> {
                    probing = false;
                    if (isFinishing()) return;
                    if (!ready) {
                        readyPolls++;   // ADFA-4874: feeds the slow-start message in render()
                        render();
                        main.postDelayed(readyPoll, READY_POLL_MS);
                        return;
                    }
                    servicesReady = true;
                    boolean moreWork = orchestrateStep();
                    render();
                    if (moreWork) main.postDelayed(readyPoll, READY_POLL_MS);
                });
            });
        }
    };

    /**
     * ADFA-5074: no stage has anything left to launch.
     *
     * <p>Exactly the set of "hasPending" questions {@link #orchestrateStep()} asks before it
     * starts anything, and asked in one place so the two cannot drift: if this is true, that
     * method can only observe. Deliberately not "is the run finished" — work already in flight
     * is not pending, which is the whole point. A Get More download reaches this screen with its
     * wishlist already drained by the door, so there is nothing to gate.
     */
    private boolean nothingToStart() {
        return !MapsProvisioner.hasPending(this)
                && !ModuleProvisioner.hasPending(this)
                && !ZimProvisioner.hasPending(this)
                && !BooksProvisioner.hasPending(this)
                && !KolibriProvisioner.hasPending(this);
    }

    /**
     * ADFA-5061: whether a drain actually handed work over.
     *
     * <p>Three outcomes, and the pipeline needs them apart. {@code null} means the drain was not
     * attempted — nothing banked, or a queue already running — and nothing has changed, so the
     * caller must not mark a stage either way. {@code RUN_STOPPED} means it went. Anything else
     * is the model refusing, and a refusal does not become a yes by asking again on the next
     * tick: the facts it read are the facts, and only a repair or an install changes them.
     */
    private static boolean launched(OperationDispatcher.Dispatch verdict) {
        return verdict != null && OperationDispatcher.mayRunStopped(verdict);
    }

    /**
     * ADFA-4900: one step of the serialized install pipeline. Starts the next stage only when the
     * previous one has finished; proot (maps) runs exclusively before any REST download, so Ansible's
     * background forks never overlap a live REST job. Returns true while work remains (keep polling),
     * false once every stage has been started and is complete.
     */
    private boolean orchestrateStep() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean queueRunning = ModuleQueueRepository.get().isRunning();

        // Stage 1 — proot (maps and/or module management), exclusive of all REST work (proot tasks
        // run serially via the queue). Maps (Get More) and modules (module management) are separate
        // entry points, so at most one has a pending batch in a given session.
        // ADFA-5061: `!mapsStartFailed` retires the stage. Until the drains could refuse, a
        // pending wishlist always became an empty one on the next pass, so "still pending" was a
        // safe reason to keep waiting. It is not any more: a refusal leaves the order banked on
        // purpose, and without this guard the stage would be re-offered every READY_POLL_MS
        // forever — a spinner with no explanation, on precisely the damaged system that needs one.
        if (!mapsStartFailed && MapsProvisioner.hasPending(this)) {
            if (!queueRunning) {
                if (launched(MapsProvisioner.drain(this))) {
                    mapsLaunched = true; mapsLaunchedAt = SystemClock.elapsedRealtime();
                } else {
                    // Same terminal state as a queue that never started: render() already treats
                    // it as a failed proot stage, which is the visible answer decision 4 asks for.
                    mapsStartFailed = true;
                }
            }
            return true;
        }
        if (!moduleStartFailed && ModuleProvisioner.hasPending(this)) {   // ADFA-4842: module management batch
            if (!queueRunning) {
                if (launched(ModuleProvisioner.drain(this))) {
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

        // Stage 2 — REST. ADFA-4954 (ADR-4954 D8): the three live streams now serialize against
        // each other as well. Each measures free space independently and at a different moment,
        // so all three could pass their own check and jointly fill the disk. Each provisioner
        // defers while another holds a session; calling them in a fixed order means the first
        // one starts and the rest retry on a later pass. Keep polling until all are complete.
        if (ZimProvisioner.hasPending(this)) ZimProvisioner.drain(this);
        if (BooksProvisioner.hasPending(this)) BooksProvisioner.drain(this);
        if (KolibriProvisioner.hasPending(this)) KolibriProvisioner.drain(this);
        KolibriSeedRepository kolibri = KolibriSeedRepository.get();
        boolean restBusy = (ZimDownloadService.hasSession() && !ZimDownloadService.isComplete())
                || (BooksDownloadService.hasSession() && !BooksDownloadService.isComplete())
                || (kolibri.hasSession() && !kolibri.isComplete())
                || ZimProvisioner.hasPending(this) || BooksProvisioner.hasPending(this)
                || KolibriProvisioner.hasPending(this);
        if (restBusy) return true;

        // Every stage has been started and is complete.
        drained = true;
        return false;
    }

    // ---- render ----
    private void render() {
        if (sections == null) return;

        // ADFA-5011: a rebuild has its own, simpler surface (one row + status), driven by
        // InstallProgressRepository — never the install/content completion logic below.
        // A rebuild never opens a detail, so it keeps the plain guard.
        if (rebuildInSession()) { if (!showingDetail) renderRebuild(); return; }

        boolean mapsShown = mapsInSession();   // ADFA-4900 / ADFA-4919 (durable across index instances)
        boolean moduleShown = moduleInSession();   // ADFA-4842: non-maps proot module batch
        // ADFA-4954: ONE reading of every content type for the whole pass. The wishlists
        // re-parse their JSON on every access and the session states are published from
        // service callbacks, so asking twice in one render can draw a row from one answer
        // and compute completion from another. render() runs about once a second while a
        // job is live, so this is also the difference between one parse and several.
        PendingContent.Snapshot content = PendingContent.read(this);
        boolean zimShown = content.inPlay(ContentType.ZIM);
        boolean booksShown = content.inPlay(ContentType.BOOKS);
        KolibriSeedState kolibriState = content.courses();
        int kolibriBanked = content.banked(ContentType.COURSES);
        boolean kolibriShown = content.inPlay(ContentType.COURSES);

        sections.removeAllViews();
        // ADFA-4900: maps (proot) runs first in the pipeline, so its row leads the list.
        if (mapsShown) sections.addView(mapsRow());
        // ADFA-4842: one row per module in the batch (proot), each tappable to its install detail.
        if (moduleShown) for (String k : ModuleBatch.keys(this)) sections.addView(moduleRow(k));
        // ADFA-4954: session and banked count come from the pass snapshot, so a row can no
        // longer be drawn from a different reading than the one that decided to show it.
        boolean zimSession = content.hasSession(ContentType.ZIM);
        boolean booksSession = content.hasSession(ContentType.BOOKS);
        // ADFA-5074: started above, waiting below.
        //
        // The order used to be fixed by content type, which expressed nothing and did no harm
        // while everything began at once. With a queue it reads as sequence, and it read wrong:
        // a ZIM order banked behind a running Courses download drew above it, so the first thing
        // on screen was the one that had not started.
        //
        // Two buckets only, with the type order kept inside each. A finer sort — done last, say
        // — would reshuffle the list every time something finished, which is the opposite of
        // what this screen is for: it is glanced at, minutes or hours apart, and rows that move
        // between glances have to be re-read. Started rows never change places; a queued one
        // moves up exactly once, when it starts, which is a real event worth showing.
        //
        // Each row also carries its transfer rate — the one thing that separates "slow" from
        // "stopped". Books reports none (its service tracks whole files, not bytes in flight),
        // so its row omits it: 0 means "say nothing".
        java.util.List<View> started = new java.util.ArrayList<>();
        java.util.List<View> waiting = new java.util.ArrayList<>();
        if (zimShown) (zimSession ? started : waiting).add(
                streamRow(getString(R.string.k2go_gm_wikipedia_title), "zim",
                        zimSession, ZimDownloadService.status(),
                        ZimDownloadService.DONE, ZimDownloadService.FAILED,
                        zimSession && ZimDownloadService.isComplete(), content.banked(ContentType.ZIM),
                        ZimDownloadService.speed()));
        if (booksShown) (booksSession ? started : waiting).add(
                streamRow(getString(R.string.k2go_gm_books_title), "books",
                        booksSession, BooksDownloadService.status(),
                        BooksDownloadService.DONE, BooksDownloadService.FAILED,
                        booksSession && BooksDownloadService.isComplete(), content.banked(ContentType.BOOKS),
                        0L));
        // ADFA-4954. Statuses come from an observable snapshot rather than static arrays, so the
        // ordinals are mapped to the checklist's PENDING=0 / doneVal / failedVal convention here.
        if (kolibriShown) (kolibriState.hasSession() ? started : waiting).add(
                streamRow(getString(R.string.k2go_gm_courses_title), "kolibri",
                        kolibriState.hasSession(), kolibriState.statusOrdinals(),
                        KolibriSeedState.Status.DONE.ordinal(), KolibriSeedState.Status.FAILED.ordinal(),
                        kolibriState.hasSession() && kolibriState.isComplete(), kolibriBanked,
                        kolibriState.speedBytesPerSec()));
        for (View v : started) sections.addView(v);
        for (View v : waiting) sections.addView(v);

        // Overall state. Completion is stage-based.
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        // ADFA-4919: a proot-only set (no REST content at all) must finish WITHOUT waiting on any
        // REST drain -- its completion is simply the maps (proot) stage going terminal. Otherwise the
        // index never reaches success/failure and can't show redirect/Cancel/Finish. The REST/mixed
        // path keeps its existing drain-based signal untouched.
        // ADFA-4954: read off the same snapshot as the rows above, because this list used to
        // be ZIM + Books only. A run mixing courses with a proot batch therefore counted as
        // "no REST content" and could declare itself complete on the queue alone while the
        // courses were still downloading.
        boolean noRest = !content.anyLive();
        // ADFA-4842: proot = maps OR a module batch. A proot-only run finishes when the queue is
        // terminal, without waiting on any REST drain.
        boolean prootShown = mapsShown || moduleShown;
        boolean prootTerminal = mapsStartFailed || moduleStartFailed
                || (prootShown && mq.phase == ModuleQueueState.Phase.DONE);
        // ADFA-4919: a proot module is queued/running (the gate is active).
        boolean prootActive = prootActive();
        if (contextText != null) contextText.setText(prootActive ? R.string.k2go_setup_context_proot : R.string.k2go_setup_context);
        // ADFA-4842: a terminal MODULE batch stopped the server for its runroles, so the server must be
        // brought back before we can finish. Kick that here for ANY terminal module session —
        // independent of the noRest/REST branch below — so ensureServerUpForModules() (and its 45s
        // safety timeout) always runs and moduleServerUp / prootActive() can never hang. Idempotent.
        boolean queueTerminalNotRunning = prootTerminal && !ModuleQueueRepository.get().isRunning();
        if (moduleShown && queueTerminalNotRunning) ensureServerUpForModules();

        boolean allComplete;
        boolean moduleServerSettled = moduleServerUp || moduleServerFailed;   // ADFA-4842: up, or gave up (failure)
        if (noRest && prootShown) {
            // proot-only: complete when the queue is terminal — plus, for a module batch, once the server
            // is back (up) or the restart has failed (a dead home that wakes up seconds later is exactly
            // what we're avoiding; a real failure is surfaced as Finish/error below, not a silent success).
            allComplete = queueTerminalNotRunning && (!moduleShown || moduleServerSettled);
        } else {
            allComplete = drained
                    && (!zimSession || ZimDownloadService.isComplete())
                    && (!booksSession || BooksDownloadService.isComplete())
                    && (!kolibriState.hasSession() || kolibriState.isComplete())   // ADFA-4954
                    && (!moduleShown || moduleServerSettled);   // ADFA-4842: also wait for the module server restart
        }
        // ADFA-4900/4842: failed proot runroles count as failures too (Finish, not a false success).
        // On DONE the queue's failedModules covers maps + modules; before DONE, a start-timeout counts.
        // ADFA-4954: ModuleQueueRepository is process-scoped, so a DONE phase left by an
        // earlier run kept reporting its failed modules to runs that launched no proot work
        // at all. A ZIM-only wizard run then showed Finish + "review failed tasks" over a
        // clean, finished download instead of redirecting to the library. Only read the
        // queue's verdict when it belongs to THIS run — the same three signals prootTerminal
        // already uses, so the two stay in agreement.
        boolean queueVerdictIsOurs = prootShown || mapsStartFailed || moduleStartFailed;
        int prootFailed = !queueVerdictIsOurs ? 0
                : (mq.phase == ModuleQueueState.Phase.DONE)
                        ? (mq.failedModules == null ? 0 : mq.failedModules.size())
                        : ((mapsStartFailed ? 1 : 0) + (moduleStartFailed ? 1 : 0));
        int failedTotal = failedCount(zimSession ? ZimDownloadService.status() : null, ZimDownloadService.FAILED)
                + failedCount(booksSession ? BooksDownloadService.status() : null, BooksDownloadService.FAILED)
                + kolibriState.failedCount()   // ADFA-4954
                + prootFailed;

        // Status dot + line. While waiting, a long-stuck engine shows a softer "taking longer"
        // message instead of "Starting services" so it doesn't look frozen (ADFA-4874). ADFA-4842: while
        // restarting the server after a module batch, show "starting services" (amber) too.
        // ADFA-4842: a module (solo-proot) install must never read as "Starting services" — that is REST
        // wording and would suggest we're bringing the server up while runroles own the rootfs. During the
        // runroles show "Modules are installing"; only the real post-DONE restart says "Starting services".
        boolean moduleFlow = moduleInSession();
        // Amber "working" while a module install runs or its post-DONE restart is pending — but NOT once it
        // failed (that shows the Finish/error controls, no animated wait).
        boolean amberWaiting = !moduleServerFailed && (moduleFlow ? !moduleServerUp : !servicesReady);
        tint(dot, (amberWaiting || moduleServerFailed) ? R.color.k2go_amber : R.color.k2go_leaf);
        int statusRes;
        if (moduleServerFailed) statusRes = R.string.k2go_setup_slow;                            // couldn't bring services online
        else if (moduleRestartKicked && !moduleServerUp) statusRes = R.string.k2go_setup_starting;   // (re)starting the server
        else if (moduleFlow && !moduleServerUp) statusRes = R.string.install_busy_modules;       // runroles in flight
        else if (moduleFlow) statusRes = R.string.k2go_setup_adding;                             // module done + server up
        else if (!servicesReady) statusRes = (readyPolls >= SLOW_AFTER_POLLS ? R.string.k2go_setup_slow : R.string.k2go_setup_starting);
        else statusRes = R.string.k2go_setup_adding;
        // Animate a "…" (dots appear/disappear) on the amber waiting line so it never looks frozen.
        if (amberWaiting) statusEllipsis.start(getString(statusRes));
        else { statusEllipsis.stop(); statusText.setText(statusRes); }

        // ADFA-5074: a detail card is covering the index. Everything above still had to be
        // computed — whether the run finished is a fact about the run, not about which screen is
        // in front — but the controls below are not on screen, so a run that completed here would
        // have nowhere to say so and would simply sit there. Step back to the index and let the
        // normal pass run: the user gets the summary, the countdown and its Cancel, rather than
        // being sent home from under a screen they were reading.
        //
        // Two conditions on that step-back, both from the review, both cases this got wrong:
        //
        // Armed once per opening. The check is on a level, not an edge, so it used to fire on
        // every pass while the run stayed complete — a user who cancelled the countdown and
        // tapped a finished row was thrown straight back out, and worst of all on a failed run,
        // where the detail is the only place the per-item retry lives. `bounceOnComplete` is set
        // when a detail is opened over work still in flight and cleared when it fires, so a
        // detail opened deliberately over a finished run is never taken away.
        //
        // Only while resumed. render() has callers that outlive onPause: the two poll callbacks
        // re-post from their own IO continuations, and onPause keeps the Zim/Books listeners
        // whenever a detail is open — which for a courses, maps or module detail means the index
        // still owns them. A publish() arriving then would run commitNow() on a stopped
        // FragmentManager. Deferring costs nothing: onResume posts the poll, which renders.
        lastAllComplete = allComplete;
        if (showingDetail) {
            if (allComplete && bounceOnComplete
                    && getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                bounceOnComplete = false;
                backToIndex();
            }
            return;
        }

        // Bottom controls. ADFA-4842: a failed post-module server restart counts as a failure (Finish +
        // note), never a silent success — so the user is told, not dropped on a dead Home.
        boolean success = allComplete && failedTotal == 0 && !moduleServerFailed;
        boolean failure = allComplete && (failedTotal > 0 || moduleServerFailed);
        if (success && !redirectCancelled) {
            show(redirect, true); show(cancel, true);
            show(finishBtn, false); show(finishNote, false); show(runBgBtn, false);
            scheduleRedirect();
        } else if (success) {   // cancelled by the user
            cancelRedirect();
            show(finishBtn, true); show(runBgBtn, false);
            show(redirect, false); show(cancel, false); show(finishNote, false);
        } else if (failure) {
            cancelRedirect();
            show(finishBtn, true); show(finishNote, true); show(runBgBtn, false);
            show(redirect, false); show(cancel, false);
        } else {   // starting or running
            cancelRedirect();
            // ADFA-4919: no "Run in background" while a proot module runs — the index is the gate.
            // ADFA-5074: and not before the run's shape is known either. prootActive() reads a latch
            // that only engages once the proot work registers — launched, batched, or reported by the
            // queue — so on entry it is false even for a run that is about to install a module, and
            // the screen offered an escape it was going to withdraw. Waiting for servicesReady costs
            // nothing: until the engine answers there is no live work to leave running anyway, and
            // the header already says "Starting services…".
            show(runBgBtn, servicesReady && !prootActive);
            show(finishBtn, false); show(finishNote, false); show(redirect, false); show(cancel, false);
        }
    }

    /** ADFA-5011: dedicated render for a dashboard rebuild — one row + a status line driven by
     *  InstallProgressRepository. While running the screen is the gate (no Run in background, Back is
     *  softened then backgrounds the app); on SUCCESS it redirects to a live Library; on FAILED it
     *  shows Finish + the note (never a silent success on a half-rebuilt server). */
    private void renderRebuild() {
        InstallState st = InstallProgressRepository.get().current();
        if (st.isRunning()) rebuildRunningSeen = true;
        // Only honor a terminal state once THIS rebuild has been seen running — otherwise a stale
        // SUCCESS/FAILED from a previous rebuild would flash on entry and trigger a premature redirect.
        boolean rebuiltOk = rebuildRunningSeen && st.phase == InstallState.Phase.SUCCESS;
        boolean rebuildFailed = rebuildRunningSeen && st.phase == InstallState.Phase.FAILED;

        // Note: the post-success wait for the REST core (apiReady poll) is driven by readyPoll, which is
        // lifecycle-managed (posted in onResume, cleared in onPause). This method only reflects state.
        boolean serverWait = rebuiltOk && !rebuildServerUp && !rebuildServerFailed;  // rebuilt, services coming up
        boolean done = rebuiltOk && rebuildServerUp;                                 // rebuilt + REST core answered
        boolean error = rebuildFailed || (rebuiltOk && rebuildServerFailed);         // rebuild failed, or services never came up
        boolean working = !done && !error;                                          // building OR waiting for services

        String sub;
        if (error) sub = rebuildFailed
                ? ((st.message != null && !st.message.isEmpty()) ? st.message : getString(R.string.k2go_dash_rebuild_failed))
                : getString(R.string.k2go_dash_services_failed);
        else if (done) sub = getString(R.string.k2go_setup_state_done);
        else if (serverWait) sub = getString(R.string.k2go_setup_starting);
        else sub = getString(R.string.k2go_dash_rebuild_building);

        sections.removeAllViews();
        sections.addView(rebuildRow(rebuiltOk && !error, error, sub));   // check once the build succeeded; alert on error

        if (contextText != null) contextText.setText(R.string.k2go_setup_context_proot);

        tint(dot, done ? R.color.k2go_leaf : R.color.k2go_amber);
        if (working) {
            statusEllipsis.start(getString(serverWait ? R.string.k2go_setup_starting : R.string.k2go_dash_rebuilding));
        } else {
            statusEllipsis.stop();
            statusText.setText(done ? R.string.k2go_setup_state_done
                    : (rebuildFailed ? R.string.k2go_dash_rebuild_failed : R.string.k2go_dash_services_failed));
        }

        if (done && !redirectCancelled) {
            redirect.setText(R.string.k2go_dash_redirect);   // rebuild-specific wording (not "Installation complete")
            show(redirect, true); show(cancel, true);
            show(finishBtn, false); show(finishNote, false); show(runBgBtn, false);
            scheduleRedirect();
        } else if (done) {   // cancelled by the user — stay, reveal Finish
            cancelRedirect();
            show(finishBtn, true); show(runBgBtn, false);
            show(redirect, false); show(cancel, false); show(finishNote, false);
        } else if (error) {
            cancelRedirect();
            show(finishBtn, true); show(finishNote, true); show(runBgBtn, false);
            show(redirect, false); show(cancel, false);
        } else {   // building or waiting for services — the screen is the gate; no leaving.
            cancelRedirect();
            show(runBgBtn, false); show(finishBtn, false); show(finishNote, false);
            show(redirect, false); show(cancel, false);
        }
    }

    /** ADFA-5011: the single dashboard-rebuild row: spinner while working → check (build done) / amber
     *  alert (failed), with the given status subtitle. */
    private View rebuildRow(boolean check, boolean alert, String subText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        LinearLayout slot = new LinearLayout(this);
        slot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(24), px(24));
        slotLp.rightMargin = px(10);
        slot.addView(indicator(true, check || alert, alert ? 1 : 0));
        row.addView(slot, slotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this);
        h.setText(R.string.k2go_dash_card_title);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(h);
        TextView sub = new TextView(this);
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        sub.setText(subText);
        sub.setTextColor(ContextCompat.getColor(this, alert ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static int failedCount(int[] status, int failedVal) {
        if (status == null) return 0;
        int n = 0; for (int st : status) if (st == failedVal) n++; return n;
    }

    private void show(View v, boolean vis) { v.setVisibility(vis ? View.VISIBLE : View.GONE); }
    private void tint(View v, int colorRes) {
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
    }

    /** A stream summary row: waiting dot / spinner / check / alert, title, "X of N", chevron. */
    /**
     * ADFA-5074: the rate, appended to a running row's subtitle.
     *
     * <p>The one thing the index was missing for the way these screens are actually used. The
     * downloads run for hours, so the user leaves and comes back to ask a single question: is
     * this still moving, or has it died? "2 of 5" cannot answer it — the count sits still for
     * an hour on a large item either way.
     *
     * <p>Only the rate, and only while running. The bytes, the per-item checklist and the
     * retries stay in the detail: the index is a control point, not a smaller copy of the card.
     * That distinction is the ticket's, and it is easy to erode one field at a time.
     */
    private String rateSuffix(long bytesPerSec) {
        if (bytesPerSec <= 0L) {
            return "";
        }
        return "  ·  " + org.iiab.controller.util.ByteFormatter.toHuman(bytesPerSec)
                + getString(R.string.k2go_rate_per_second);
    }

    private View streamRow(String heading, String key, boolean sess, int[] status, int doneVal, int failedVal,
                           boolean complete, int wishlistCount, long bytesPerSec) {
        int n = sess && status != null ? status.length : wishlistCount;
        int done = 0, failed = 0;
        if (sess && status != null) for (int st : status) { if (st == doneVal) done++; else if (st == failedVal) failed++; }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        // Indicator (fixed 24dp slot for alignment).
        LinearLayout slot = new LinearLayout(this);
        slot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(24), px(24));
        slotLp.rightMargin = px(10);
        slot.addView(indicator(sess, complete, failed));
        row.addView(slot, slotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this);
        h.setText(heading);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(h);
        TextView sub = new TextView(this);
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        // ADFA-5074: one row per content type, and the subtitle carries the mix of states inside
        // it — "1 Done · 1 Queued", "0 of 1 · 21 MiB/s · 1 Queued".
        //
        // There is one row per type, not one per order: the services hold a single session at a
        // time and the wishlist is a bag of items, so there is no order with an identity to draw.
        // That stayed invisible until the queue existed. With a session present this row was
        // built entirely from it and wishlistCount was dropped, so a second ZIM asked for while
        // the first was downloading left no trace at all — four queued orders drew two rows with
        // nothing to show for two of them.
        //
        // Grouping the index by state instead was considered and rejected: a type can be in two
        // states at once (courses done AND courses queued), so it would appear in two sections,
        // which is the confusion of a row-per-order with extra structure — and the list would
        // reflow on every transition, which this screen must not do. The type is the stable
        // anchor; the states are counts within it.
        //
        // Composed from existing labels plus numbers. The project has no <plurals> and
        // MissingTranslation is a hard error, so a new string costs 34 locale files.
        String state;
        if (!sess) {
            state = n + " " + getString(R.string.k2go_setup_state_queued);
        } else {
            if (complete && failed > 0) state = getString(R.string.k2go_setup_state_failed_fmt, failed);
            else if (complete) state = done + " " + getString(R.string.k2go_setup_state_done);
            else state = getString(R.string.k2go_setup_state_progress_fmt, done, n) + rateSuffix(bytesPerSec);
            if (wishlistCount > 0) {
                state += "  ·  " + wishlistCount + " " + getString(R.string.k2go_setup_state_queued);
            }
        }
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, (sess && failed > 0) ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        // ADFA-5074: no chevron on a row that does not open. It was always drawn, which was
        // harmless while a queued row was a brief state during an install; with a real queue a
        // row can sit "Queued" for an hour, offering to be tapped and doing nothing.
        chev.setVisibility(sess ? View.VISIBLE : View.INVISIBLE);
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));

        if (sess) row.setOnClickListener(v -> openDetail(key));   // detail only once there's a live session
        return row;
    }

    /** ADFA-4900/4901: summary row for the maps (proot) stage, driven by the module-queue state:
     *  queued -> spinner (runrole in flight) -> check (done) / amber alert (failed). Tappable once
     *  the stage has started; opens the maps Preparing card as its detail (ADFA-4901). */
    private View mapsRow() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean done = mapsStartFailed || (mapsInSession() && mq.phase == ModuleQueueState.Phase.DONE);
        boolean running = !done && (ModuleQueueRepository.get().isRunning()
                || (mapsInSession() && mq.phase != ModuleQueueState.Phase.DONE));
        boolean failed = mapsStartFailed || (done && mq.failedModules.contains("maps"));
        boolean started = running || done;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        LinearLayout slot = new LinearLayout(this);
        slot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(24), px(24));
        slotLp.rightMargin = px(10);
        slot.addView(indicator(started, done, failed ? 1 : 0));
        row.addView(slot, slotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this);
        h.setText(getString(R.string.k2go_gm_maps_title));
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(h);
        TextView sub = new TextView(this);
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        String state;
        if (!started) state = getString(R.string.k2go_setup_state_queued);
        else if (failed) state = getString(R.string.k2go_maps_phase_failed);
        else if (done) state = getString(R.string.k2go_setup_state_done);
        else state = getString(R.string.k2go_maps_phase_building);
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, failed ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // ADFA-4901: like the ZIM/Books rows, maps opens its own progress detail once the stage has
        // started (running or done). Maps is a single proot runrole, so the detail is the queue-driven
        // Preparing card (spinner + phase), not a per-item checklist.
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        chev.setVisibility(started ? View.VISIBLE : View.INVISIBLE);
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));
        if (started) row.setOnClickListener(v -> openDetail("maps"));
        return row;
    }

    /** ADFA-4842: one module's row in the batch. State is derived from the durable batch order plus
     *  the queue: earlier-than-current = done, current = installing, later = queued; failed modules
     *  come from failedModules; on queue DONE every non-failed module is done. Tappable once started;
     *  opens the shared module install detail (its live Ansible terminal). */
    private View moduleRow(String key) {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        ModuleCards.Card c = ModuleCards.byKey(key);
        String name = c != null ? getString(c.titleRes) : key;

        boolean failed = (mq.failedModules != null && mq.failedModules.contains(key)) || moduleStartFailed;
        boolean queueDone = mq.phase == ModuleQueueState.Phase.DONE;
        boolean running = !queueDone && !failed && key.equals(mq.currentModule)
                && mq.phase == ModuleQueueState.Phase.RUNNING;
        String[] batch = ModuleBatch.keys(this);
        int me = indexOf(batch, key), cur = indexOf(batch, mq.currentModule);
        boolean done = !failed && (queueDone || (me >= 0 && cur >= 0 && me < cur));
        boolean started = running || done || failed;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        LinearLayout slot = new LinearLayout(this);
        slot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(24), px(24));
        slotLp.rightMargin = px(10);
        slot.addView(indicator(started, done || failed, failed ? 1 : 0));
        row.addView(slot, slotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this);
        h.setText(name);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(h);
        TextView sub = new TextView(this);
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        String state;
        if (failed) state = getString(R.string.k2go_mod_phase_failed);
        else if (done) state = getString(R.string.k2go_setup_state_done);
        else if (running) state = getString(R.string.k2go_mod_phase_installing);
        else state = getString(R.string.k2go_mod_phase_queued);
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, failed ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        chev.setVisibility(started ? View.VISIBLE : View.INVISIBLE);
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));
        if (started) row.setOnClickListener(v -> openDetail("mod:" + key));
        return row;
    }

    private static int indexOf(String[] arr, String v) {
        if (arr == null || v == null) return -1;
        for (int i = 0; i < arr.length; i++) if (v.equals(arr[i])) return i;
        return -1;
    }

    private View indicator(boolean sess, boolean complete, int failed) {
        if (!sess) {                                   // waiting for services / drain
            View d = new View(this);
            d.setBackgroundResource(R.drawable.k2go_dot);
            d.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.k2go_hairline)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(10), px(10));
            return wrap(d, lp);
        }
        if (complete && failed > 0) {                  // partial failure
            ImageView a = new ImageView(this);
            a.setImageResource(R.drawable.k2go_info_circle);
            a.setColorFilter(ContextCompat.getColor(this, R.color.k2go_amber_text));
            return sized(a, 20);
        }
        if (complete) {                                // done
            ImageView chk = new ImageView(this);
            chk.setImageResource(R.drawable.ic_check_circle);
            chk.setColorFilter(ContextCompat.getColor(this, R.color.k2go_leaf));
            return sized(chk, 18);
        }
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);   // active
        return sized(pb, 22);
    }

    private View wrap(View v, LinearLayout.LayoutParams lp) { v.setLayoutParams(lp); return v; }
    private View sized(View v, int dp) { v.setLayoutParams(new LinearLayout.LayoutParams(px(dp), px(dp))); return v; }

    // ---- auto-redirect on success ----
    private final Runnable goHomeRunnable = () -> goHome(true);
    private void scheduleRedirect() {
        if (redirectScheduled) return;
        redirectScheduled = true;
        main.postDelayed(goHomeRunnable, REDIRECT_MS);
    }
    private void cancelRedirect() {
        main.removeCallbacks(goHomeRunnable);
        redirectScheduled = false;
    }
    /** ADFA-4842: after a module batch (the server was pdsm-stopped for the runroles), start the server
     *  once and poll the REST core until it answers. render() gates completion/redirect on
     *  moduleServerUp, so we only leave for the Library once the system is actually live. */
    private void ensureServerUpForModules() {
        if (moduleServerUp || moduleServerFailed || moduleRestartKicked) return;
        moduleRestartKicked = true;
        moduleRestartAt = SystemClock.elapsedRealtime();
        // ADFA-4842: MECHANISM (proot ≠ REST — do NOT "simplify" this to the toggle; read
        // ServerController.startEnvironment() first). Each module runrole runs in its own proot with
        // --kill-on-exit: clean start → its tasks → clean stop, so the environment is DOWN when the batch
        // finishes (that is the correct per-module cycle, especially with several modules in series). After
        // the LAST module the INDEX is the actuator: it brings the environment back UNCONDITIONALLY.
        // We must NOT call handleServerLaunchClick here (it is a TOGGLE): the cached alive can still read
        // TRUE the instant after the runrole proot exits, and the toggle would then STOP instead of start
        // (that was the "Stopping IIAB environment gracefully → dead Home" bug from the device log).
        // startEnvironment() always starts. The server proot is process-scoped, so it survives into
        // LibraryActivity, which only MONITORS it — Home never starts the server.
        serverController.startEnvironment();
        main.postDelayed(serverUpPoll, READY_POLL_MS);
    }

    private final Runnable serverUpPoll = new Runnable() {
        @Override public void run() {
            if (isFinishing() || moduleServerUp || moduleServerFailed) return;
            AppExecutors.get().io().execute(() -> {
                final boolean up = RestReadiness.apiReady();
                main.post(() -> {
                    if (isFinishing() || moduleServerUp || moduleServerFailed) return;
                    if (up) { moduleServerUp = true; render(); }   // REST core answered → complete → redirect
                    else if (SystemClock.elapsedRealtime() - moduleRestartAt > SERVER_UP_TIMEOUT_MS) {
                        // ADFA-4842: the environment didn't come online in time. Do NOT declare success and
                        // drop the user on a dead Home (the old behavior); surface it as a FAILURE so the
                        // index shows the Finish/error state (like an Ansible failure). Home is a monitor —
                        // it won't recover this, so the honest thing is to tell the user here.
                        moduleServerFailed = true; render();
                    } else main.postDelayed(serverUpPoll, READY_POLL_MS);
                });
            });
        }
    };

    private void goHome(boolean clearSessions) {
        cancelRedirect();
        if (clearSessions) { ZimDownloadService.finishSession(); BooksDownloadService.finishSession(); KolibriSeedService.finishSession(); }
        ModuleBatch.clear(this);   // ADFA-4842: this run's module batch is done

        // ADFA-4919: the natural end of installing is the Library — go there directly and clear the
        // install screens above it. Both the wizard and Get More launch from LibraryActivity, so
        // CLEAR_TOP + SINGLE_TOP lands on the existing Library (dropping Get More + this index). Only
        // success/Finish reach here; "Run in background" (REST) still finish()es in place. ADFA-4842: a
        // module batch already restarted the server and waited for it here (ensureServerUpForModules), so
        // the reused Library is live on arrival — no cold-boot recreate needed.
        startActivity(new android.content.Intent(this, LibraryActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_library));   // ADFA-4842: land on Home, not the launching tab (Settings)
        finish();
    }

    /**
     * ADFA-5074: whether a completed run should take this detail away again.
     *
     * <p>Armed when a detail is opened over work still in flight, cleared when it fires. A
     * detail opened over a run that had already finished is a deliberate look at the result —
     * usually at a failed row, whose retry lives only there — and must not be closed underneath
     * the user.
     */
    private boolean bounceOnComplete = false;

    /** The last completion verdict, so opening a detail can tell "still working" from "finished". */
    private boolean lastAllComplete = false;

    // ---- detail: host the real per-module card ----
    private void openDetail(String key) {
        showingDetail = true;
        bounceOnComplete = !lastAllComplete;
        androidx.fragment.app.Fragment f;
        boolean proot;
        if (key.startsWith("mod:")) { f = ModuleInstallFragment.newInstance(key.substring(4)); proot = true; }  // ADFA-4842
        else if ("zim".equals(key)) { f = new ZimPreparingFragment(); proot = false; }   // ADFA-5074: observe-only
        else if ("kolibri".equals(key)) { f = new KolibriSeedingFragment(); proot = false; }   // ADFA-4954: observe-only
        else if ("maps".equals(key)) { f = MapsPreparingFragment.newInstance(true); proot = true; }   // ADFA-4901: observe-only
        else { f = BooksDownloadsFragment.newInstance(true); proot = false; }
        // ADFA-4919/4842: a proot detail (maps or module) cannot background either — only Back (to the index).
        if (detailRunBgBtn != null) detailRunBgBtn.setVisibility(proot ? View.GONE : View.VISIBLE);
        // ADFA-5074: commitNow, to match backToIndex. With an async commit a render() landing in
        // between set showingDetail back to false and found nothing to remove, and the queued
        // transaction then added the fragment into a hidden host — where ZimPreparingFragment
        // takes the service listener with nobody left to reclaim it, freezing the index's row.
        // The mirror image of the bug backToIndex's commitNow already exists for. Only reachable
        // from a row tap now that the intent routing is gone, so the activity is resumed and the
        // synchronous commit is safe.
        getSupportFragmentManager().beginTransaction().replace(R.id.k2go_sp_fraghost, f).commitNow();
        indexScroll.setVisibility(View.GONE);
        detailRoot.setVisibility(View.VISIBLE);
    }

    private void backToIndex() {
        showingDetail = false;
        // commitNow (synchronous) so the fragment's onDestroyView — which nulls the service
        // listener — runs BEFORE we reclaim it. With async commit() the teardown fired later and
        // clobbered the index's listener, so a job finishing while back on the index never updated
        // the UI (spinner stuck) until the card was reopened.
        androidx.fragment.app.Fragment cur = getSupportFragmentManager().findFragmentById(R.id.k2go_sp_fraghost);
        if (cur != null) getSupportFragmentManager().beginTransaction().remove(cur).commitNow();
        detailRoot.setVisibility(View.GONE);
        indexScroll.setVisibility(View.VISIBLE);
        ZimDownloadService.setListener(this::render);
        BooksDownloadService.setListener(this::render);
        render();
    }

    // ---- ServerController.Host (ADFA-4842): minimal — the index only needs to START the server after
    // a module batch. UI-affordance callbacks are no-ops here (the index has its own status line). ----
    @Override public void addToLog(String message) { android.util.Log.d("K2Go-SetupProgress", message); }
    @Override public void startFusionPulse() { }
    @Override public void startExitPulse() { }
    @Override public void stopBtnProgress() { }
    @Override public void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn) { }
    @Override public void refreshServerUi() { }
    @Override public Boolean getTargetServerState() { return targetServerState; }
    @Override public void setTargetServerState(Boolean target) { targetServerState = target; }
    @Override public boolean isNegotiating() { return false; }

    @Override public void enableSystemProtection() {
        android.content.Intent i = new android.content.Intent(this, org.iiab.controller.WatchdogService.class);
        i.setAction(org.iiab.controller.WatchdogService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
    }

    @Override public void disableSystemProtection() {
        android.content.Intent i = new android.content.Intent(this, org.iiab.controller.WatchdogService.class);
        i.setAction(org.iiab.controller.WatchdogService.ACTION_STOP);
        startService(i);
    }
}
