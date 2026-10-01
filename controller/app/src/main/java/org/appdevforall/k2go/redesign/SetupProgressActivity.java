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
package org.appdevforall.k2go.redesign;

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

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.install.presentation.InstallProgressRepository;
import org.appdevforall.k2go.install.presentation.InstallState;
import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;
import org.appdevforall.k2go.install.presentation.ModuleQueueState;
import org.appdevforall.k2go.kolibri.presentation.KolibriProvisioner;
import org.appdevforall.k2go.kolibri.presentation.KolibriSeedRepository;
import org.appdevforall.k2go.kolibri.presentation.KolibriSeedService;
import org.appdevforall.k2go.kolibri.presentation.KolibriSeedState;
import org.appdevforall.k2go.kolibri.presentation.KolibriSeedingFragment;
import org.appdevforall.k2go.setup.domain.RunScope;
import org.appdevforall.k2go.setup.domain.RunSnapshot;
import org.appdevforall.k2go.setup.domain.RebuildUiState;
import org.appdevforall.k2go.setup.domain.RunVerdict;
import org.appdevforall.k2go.setup.domain.SetupUiState;
import org.appdevforall.k2go.setup.domain.StreamState;
import org.appdevforall.k2go.system.data.PendingContent;
import org.appdevforall.k2go.system.domain.OperationDispatcher;
import org.appdevforall.k2go.system.domain.ContentType;
import org.appdevforall.k2go.util.Snackbars;
import org.appdevforall.k2go.util.AppExecutors;

public class SetupProgressActivity extends AppCompatActivity implements org.appdevforall.k2go.ServerController.Host {

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
    /** K2GO-422: this run is a post-install Forgejo repo seed (the "Install repos" button), so the
     *  index tracks and waits on the seed even though there is no module install this run. */
    public static final String EXTRA_FORGEJO_SEED = "forgejoSeed";

    private static final long REDIRECT_MS = 3000L;
    // K2GO-434: READY_POLL_MS / MAPS_START_TIMEOUT_MS / SERVER_UP_TIMEOUT_MS / SLOW_AFTER_POLLS moved to
    // SetupProgressController with the loop; render() reads them as SetupProgressController.<name>.

    private View dot;
    private TextView statusText, redirect, cancel, finishNote, contextText;
    private LinearLayout sections;
    private Button finishBtn, runBgBtn;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean redirectCancelled = false;
    private boolean redirectScheduled = false;
    private boolean leaveWarned = false;   // ADFA-4919 (2c): captured the first exit-Back once
    // K2GO-434: the per-run stage latches ("what belongs to this run") live in a small domain state
    // machine (setup/domain/RunScope); see controller/docs/ADR-434-setupprogress-decomposition.md.
    private final RunScope runScope = new RunScope();
    // K2GO-434 (slice 3b): the provisioning pipeline loop + its latch state live in the controller;
    // render() and the *InSession/prootActive predicates read the latches back through its getters.
    private SetupProgressController pipeline;
    // K2GO-434 (slice 6): opening a row's detail over the index, the detail action bar, and the return
    // to the index live in the detail host; render()/onResume/onPause/onBackPressed read its state.
    private SetupDetailHost detailHost;
    private boolean postInstallSeed = false; // K2GO-422: this run was launched to seed repos post-install
    // ADFA-4842: the index owns a ServerController (Host) to issue the post-batch server (re)start; the
    // server proot is process-scoped so it survives into LibraryActivity.
    private org.appdevforall.k2go.ServerController serverController;
    private org.appdevforall.k2go.util.EllipsisAnimator statusEllipsis;   // ADFA-4842: animated "…" on the amber wait line

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_k2go_setup_progress);
        // K2GO-422: launched by the "Install repos" button, so this run tracks the post-install seed.
        postInstallSeed = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FORGEJO_SEED, false);

        dot = findViewById(R.id.k2go_sp_dot);
        statusText = findViewById(R.id.k2go_sp_status);
        statusEllipsis = new org.appdevforall.k2go.util.EllipsisAnimator(statusText);
        sections = findViewById(R.id.k2go_sp_sections);
        redirect = findViewById(R.id.k2go_sp_redirect);
        cancel = findViewById(R.id.k2go_sp_cancel);
        finishBtn = findViewById(R.id.k2go_sp_finish);
        finishNote = findViewById(R.id.k2go_sp_finish_note);
        contextText = findViewById(R.id.k2go_sp_context);
        runBgBtn = findViewById(R.id.k2go_sp_runbg);
        View indexScroll = findViewById(R.id.k2go_sp_index);
        View detailRoot = findViewById(R.id.k2go_sp_detail);

        finishBtn.setOnClickListener(v -> goHome(true));
        // K2GO-382: land deliberately on Home (goHome), not a bare finish() that pops to whatever
        // launched this (the wizard/hub). goHome(false) keeps the download sessions alive, so the
        // Library keeps provisioning in the background.
        runBgBtn.setOnClickListener(v -> goHome(false));
        cancel.setOnClickListener(v -> { redirectCancelled = true; cancelRedirect(); render(); });

        Button detailBackBtn = findViewById(R.id.k2go_sp_back);
        Button detailRunBgBtn = findViewById(R.id.k2go_sp_detail_finish);
        // K2GO-434 (slice 6): the detail host owns opening a row's detail over the index, the detail
        // action bar (its button defaults included), and the return to the index. The actions a bar can
        // fire that need Activity state (leave, retry the seed, cancel a module) come back through Host.
        detailHost = new SetupDetailHost(new SetupDetailHost.Host() {
            @Override public androidx.fragment.app.FragmentManager fragmentManager() { return getSupportFragmentManager(); }
            @Override public void render() { SetupProgressActivity.this.render(); }
            @Override public void goHome(boolean keepSessionsAlive) { SetupProgressActivity.this.goHome(keepSessionsAlive); }
            @Override public void retryForgejoSeed() { SetupProgressActivity.this.retryForgejoSeed(); }
            @Override public void confirmCancelModule() { SetupProgressActivity.this.confirmCancelModule(); }
        }, indexScroll, detailRoot, detailBackBtn, detailRunBgBtn);

        // ADFA-4842: own a ServerController so the index can restart the server after a module batch
        // (it was pdsm-stopped for the runroles) and keep ServerStateRepository fresh so the start
        // toggle can't misfire. The server proot is process-scoped, so it survives into LibraryActivity.
        serverController = new org.appdevforall.k2go.ServerController(this, this);
        serverController.start();

        // K2GO-434 (slice 3b): the provisioning pipeline loop, driven through a narrow Host into this
        // Activity (render + the run-scope predicates + the environment boot + a Context for the drains).
        pipeline = new SetupProgressController(new SetupProgressController.Host() {
            @Override public android.content.Context context() { return SetupProgressActivity.this; }
            @Override public boolean isFinishing() { return SetupProgressActivity.this.isFinishing(); }
            @Override public void render() { SetupProgressActivity.this.render(); }
            @Override public boolean rebuildInSession() { return SetupProgressActivity.this.rebuildInSession(); }
            @Override public boolean moduleInSession() { return SetupProgressActivity.this.moduleInSession(); }
            @Override public boolean serverObservedUp() { return SetupProgressActivity.this.serverObservedUp(); }
            @Override public boolean forgejoSeedActive() { return SetupProgressActivity.this.forgejoSeedActive(); }
            @Override public void startEnvironmentBoot() { serverController.startEnvironment(); }
        });

        // ADFA-5343 (§3.3 follow-up): an install marker left by a DEAD process launch
        // (InstallGuard.isInterrupted) is a killed install, not a resumable session. If the OS
        // restores this index on top after such a kill, resuming here strands it polling for a server
        // that may never come up — and the reconciler keeps retrying pdsm start uncut, because the
        // DAMAGED loop-cut lives only in LibraryActivity.evaluateRecovery. Hand it to that single
        // recovery owner instead: it boots a healthy base and clears the marker, or declares DAMAGED
        // and cuts the retry. A live install in THIS process reads isLive (the marker is cleared on a
        // clean finish), so a normal run never takes this branch.
        //
        // Guarded so it only fires for a genuinely stranded install with nothing live: this screen is
        // also the home of the dashboard rebuild (which does NOT plant InstallGuard) and of live content
        // downloads (LIVE), so a stale marker coinciding with one of those must not hijack it into
        // recovery. After a real kill the queue/downloads are idle (device-observed: holder=NONE), so the
        // guard never blocks the case it exists for.
        if (org.appdevforall.k2go.InstallGuard.isInterrupted(this)
                && !rebuildInSession()
                && !org.appdevforall.k2go.env.EnvironmentLock.isBusyNow()) {
            routeToRecovery();
            return;
        }

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
        pipeline.resume();
        if (!detailHost.isShowingDetail()) {
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
        pipeline.pause();
        if (statusEllipsis != null) statusEllipsis.stop();   // ADFA-4842
        cancelRedirect();
        if (serverController != null) serverController.onPause();   // ADFA-4842: stop the status poll (not the server)
        if (!detailHost.isShowingDetail()) {
            ZimDownloadService.setListener(null);
            BooksDownloadService.setListener(null);
        }
    }

    @Override
    public void onBackPressed() {
        if (detailHost.isShowingDetail()) { detailHost.backToIndex(); return; }
        // ADFA-5011: a rebuild owns the rootfs and can't be abandoned mid-run — same gate as proot. Block
        // while building AND through the post-success wait for services to come up (so we never drop the
        // user onto a Library showing a half-rebuilt / not-yet-started server). First Back reassures; a
        // second backgrounds the app (the rebuild keeps going and reopening resumes here).
        if (rebuildInSession()) {
            InstallState rst = InstallProgressRepository.get().current();
            boolean rebuiltOk = pipeline.rebuildRunningSeen() && rst.phase == InstallState.Phase.SUCCESS;
            boolean stillWorking = InstallProgressRepository.get().isRunning()
                    || (rebuiltOk && !pipeline.rebuildServerUp() && !pipeline.rebuildServerFailed());
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
     *  (serverObservedUp) or the wait times out. */
    private boolean prootActive() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean mapsTerminal = pipeline.mapsStartFailed() || (mapsInSession() && mq.phase == ModuleQueueState.Phase.DONE);
        boolean mapsActive = mapsInSession() && !mapsTerminal;
        // A module session stays active from its runroles through the server restart that follows.
        boolean moduleActive = (moduleInSession() || pipeline.moduleStartFailed()) && !serverObservedUp();
        return mapsActive || moduleActive;
    }

    /** ADFA-5343 (Phase 2): the server is observed up — the 3s poll saw /k2go-api answer. Replaces the
     *  moduleServerUp boot latch: the fact is read from the one published observation
     *  ({@link org.appdevforall.k2go.ServerStateRepository}), not tracked per screen. */
    private boolean serverObservedUp() {
        return org.appdevforall.k2go.ServerStateRepository.get().hasObservation()
                && org.appdevforall.k2go.ServerStateRepository.get().current().alive;
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
        return runScope.latchMaps(pipeline.mapsLaunched() || pipeline.mapsStartFailed()
                || MapsProvisioner.hasPending(this)
                || (ModuleQueueRepository.get().isRunning() && "maps".equals(mq.currentModule)));
    }

    /** ADFA-4842: is a non-maps proot module batch part of THIS session? Latched from the durable
     *  batch (ModuleBatch) + provisioner + a running queue on a non-maps module, so a reopened index
     *  still renders the module rows and reaches completion. */
    private boolean moduleInSession() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        return runScope.latchModule(pipeline.moduleLaunched() || pipeline.moduleStartFailed()
                || ModuleProvisioner.hasPending(this)
                || ModuleBatch.has(this)
                || (ModuleQueueRepository.get().isRunning() && mq.currentModule != null && !"maps".equals(mq.currentModule)));
    }

    /** K2GO-423: a Forgejo seed belongs to THIS session — a seed is banked, or its service is running.
     *  Latched (like the proot stages) so a reopened index still renders the seed row and reaches
     *  completion. Latches on isRunning (not hasSession): a DONE/FAILED repository from a prior
     *  run-in-background seed lingers in the process-scoped singleton, and latching on it would draw a
     *  stale seed row in a later, unrelated install. */
    private boolean forgejoSeedInSession() {
        return runScope.latchForgejoSeed(
                org.appdevforall.k2go.forgejo.data.ForgejoInstallPrefs.isSeedPending(this)
                || org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository.get().isRunning());
    }

    /** K2GO-423: the seed still needs to run (banked) or is running, so completion must wait for it.
     *  NOT gated on repo.isComplete(): the service always CLEARS the banked marker BEFORE it sets the
     *  repository terminal, so a real DONE/give-up already reads isSeedPending == false here. A
     *  short-circuit on isComplete would be wrong right after a Retry, which re-banks the marker while
     *  the repository is still FAILED until the service reopens the session: that window must read as
     *  active so the pipeline keeps tracking the retried seed instead of stopping the poll. */
    private boolean forgejoSeedActive() {
        return org.appdevforall.k2go.forgejo.data.ForgejoInstallPrefs.isSeedPending(this)
                || org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository.get().isRunning();
    }

    /** ADFA-5011: is a dash-node rebuild the operation driving THIS screen? Latched from the launch
     *  extra (primary signal) or a LIVE REBUILD op in InstallProgressRepository (covers a reopen while
     *  the rebuild runs; a stale terminal REBUILD is excluded by the isRunning() check). Once latched it
     *  stays for the screen's life so the terminal result (done/failed) is shown, not skipped. */
    private boolean rebuildInSession() {
        if (runScope.rebuild()) return true;
        boolean signal = (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REBUILD, false))
                || (InstallProgressRepository.get().currentOp() == InstallState.Op.REBUILD
                        && InstallProgressRepository.get().isRunning());
        return runScope.latchRebuild(signal);
    }


    // ---- render ----
    private void render() {
        if (sections == null) return;

        // ADFA-5011: a rebuild has its own, simpler surface (one row + status), driven by
        // InstallProgressRepository — never the install/content completion logic below.
        // A rebuild never opens a detail, so it keeps the plain guard.
        if (rebuildInSession()) { if (!detailHost.isShowingDetail()) renderRebuild(); return; }

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
                        ZimDownloadService.speed(), zimOverallPercent()));
        if (booksShown) (booksSession ? started : waiting).add(
                streamRow(getString(R.string.k2go_gm_books_title), "books",
                        booksSession, BooksDownloadService.status(),
                        BooksDownloadService.DONE, BooksDownloadService.FAILED,
                        booksSession && BooksDownloadService.isComplete(), content.banked(ContentType.BOOKS),
                        0L, booksOverallPercent()));   // ADFA-4893: item-count bar, homologated with ZIM
        // ADFA-4954. Statuses come from an observable snapshot rather than static arrays, so the
        // ordinals are mapped to the checklist's PENDING=0 / doneVal / failedVal convention here.
        if (kolibriShown) (kolibriState.hasSession() ? started : waiting).add(
                streamRow(getString(R.string.k2go_gm_courses_title), "kolibri",
                        kolibriState.hasSession(), kolibriState.statusOrdinals(),
                        KolibriSeedState.Status.DONE.ordinal(), KolibriSeedState.Status.FAILED.ordinal(),
                        kolibriState.hasSession() && kolibriState.isComplete(), kolibriBanked,
                        kolibriState.speedBytesPerSec(), -1));   // ADFA-4893: Kolibri keeps its indicator
        for (View v : started) sections.addView(v);
        for (View v : waiting) sections.addView(v);
        // K2GO-423: the Forgejo seed row, after the content rows (the seed runs after the role). Shown
        // once it is imminent (module server up) or already has a session, so it does not sit as
        // "Queued" through the whole runrole while the module row already tells that story.
        if (forgejoSeedInSession()
                && (serverObservedUp() || postInstallSeed
                    || org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository.get().hasSession())) {
            sections.addView(forgejoSeedRow());
        }

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
        boolean prootTerminal = pipeline.mapsStartFailed() || pipeline.moduleStartFailed()
                || (prootShown && mq.phase == ModuleQueueState.Phase.DONE);
        // ADFA-4919: a proot module is queued/running (the gate is active).
        boolean prootActive = prootActive();
        if (contextText != null) contextText.setText(prootActive ? R.string.k2go_setup_context_proot : R.string.k2go_setup_context);
        // ADFA-4842/5343: a terminal MODULE batch stopped the server for its runroles, so the server must
        // come back before we can finish. Record the intent here for ANY terminal module session —
        // independent of the noRest/REST branch below — so onModuleBatchTerminal() (which sets desired=UP
        // and anchors the wait timeout) always runs and serverObservedUp() / prootActive() can never hang.
        // Idempotent (guarded by the wait anchor).
        boolean queueTerminalNotRunning = prootTerminal && !ModuleQueueRepository.get().isRunning();
        if (moduleShown && queueTerminalNotRunning) onModuleBatchTerminal();

        // ADFA-5343 (Phase 2): the module server state read from the observed phase, not a boot latch.
        //  up       = the poll observed /k2go-api answering;
        //  slow     = still not up past the wait timeout — a stuck flap the reconciler keeps re-driving,
        //             surfaced so the user isn't left staring (Finish lands on a Home it drives live);
        //  settling = terminal, not up yet, still within the timeout ((re)starting).
        boolean batchServerUp = moduleShown && queueTerminalNotRunning && serverObservedUp();
        boolean batchAwaitingServer = moduleShown && queueTerminalNotRunning && !serverObservedUp();
        boolean batchServerSlow = batchAwaitingServer && pipeline.moduleServerWaitAt() != 0L
                && SystemClock.elapsedRealtime() - pipeline.moduleServerWaitAt() > SetupProgressController.SERVER_UP_TIMEOUT_MS;
        boolean batchServerSettling = batchAwaitingServer && !batchServerSlow;

        boolean moduleServerSettled = batchServerUp || batchServerSlow;   // ADFA-5343: up, or gave up waiting here
        // K2GO-423: the seed blocks completion only while it can actually make progress:
        //  - only in a module (forgejo install) flow -- that is the only flow whose batch-terminal ->
        //    reconciler sequence brings the server up so the seed can start (a stranded seed in a
        //    non-module Get More flow never gets a server-up signal here; the Home resume drives it);
        //  - not when the forgejo runrole FAILED -- the seed never starts then (it would clear the
        //    marker), so waiting on it would hang; that run is already a failure (Finish + Retry);
        //  - not when the server is slow/failed -- also already a failure.
        // K2GO-422: also wait in a post-install seed run (the "Install repos" button, no module this
        // run). The stranded case (a banked seed leaking into an unrelated Get More flow) has neither
        // moduleShown nor the launch extra, so it still does not block -- no hang reintroduced.
        boolean seedPendingRun = forgejoSeedActive() && !batchServerSlow
                && (moduleShown || postInstallSeed)
                // The forgejo runrole failing releases the gate ONLY in a module-install flow; a
                // post-install seed run (postInstallSeed) must not read a stale/unrelated queue verdict.
                && !(moduleShown && mq.didFail("forgejo"));
        // ADFA-4900/4842: failed proot runroles count as failures too (Finish, not a false success).
        // On DONE the queue's failedModules covers maps + modules; before DONE, a start-timeout counts.
        // ADFA-4954: ModuleQueueRepository is process-scoped, so a DONE phase left by an
        // earlier run kept reporting its failed modules to runs that launched no proot work
        // at all. A ZIM-only wizard run then showed Finish + "review failed tasks" over a
        // clean, finished download instead of redirecting to the library. Only read the
        // queue's verdict when it belongs to THIS run — the same three signals prootTerminal
        // already uses, so the two stay in agreement.
        boolean queueVerdictIsOurs = prootShown || pipeline.mapsStartFailed() || pipeline.moduleStartFailed();
        int prootFailed = !queueVerdictIsOurs ? 0
                : (mq.phase == ModuleQueueState.Phase.DONE)
                        ? (mq.failedModules == null ? 0 : mq.failedModules.size())
                        : ((pipeline.mapsStartFailed() ? 1 : 0) + (pipeline.moduleStartFailed() ? 1 : 0));
        // K2GO-434: the completion + success/failure rule is now a pure domain use case
        // (setup/domain/RunVerdict). This pass only GATHERS the inputs from the live
        // repositories/services; the rule is unit-tested off device. See
        // controller/docs/ADR-434-setupprogress-decomposition.md (slice 1).
        int zimFailed = failedCount(zimSession ? ZimDownloadService.status() : null, ZimDownloadService.FAILED);
        int booksFailed = failedCount(booksSession ? BooksDownloadService.status() : null, BooksDownloadService.FAILED);
        boolean forgejoSeedFailed = forgejoSeedInSession()
                && org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository.get().isFailed();
        RunVerdict verdict = RunVerdict.of(new RunSnapshot.Builder()
                .noRest(noRest).prootShown(prootShown).moduleShown(moduleShown).drained(pipeline.drained())
                .queueTerminalNotRunning(queueTerminalNotRunning).moduleServerSettled(moduleServerSettled)
                .batchServerSlow(batchServerSlow).seedPendingRun(seedPendingRun)
                .zim(new StreamState(zimSession, zimSession && ZimDownloadService.isComplete(), zimFailed))
                .books(new StreamState(booksSession, booksSession && BooksDownloadService.isComplete(), booksFailed))
                .kolibri(new StreamState(kolibriState.hasSession(),
                        kolibriState.hasSession() && kolibriState.isComplete(), kolibriState.failedCount()))
                .prootFailed(prootFailed).forgejoSeedFailed(forgejoSeedFailed)
                .build());
        boolean allComplete = verdict.allComplete();

        // Status dot + line. While waiting, a long-stuck engine shows a softer "taking longer"
        // message instead of "Starting services" so it doesn't look frozen (ADFA-4874). ADFA-4842: while
        // restarting the server after a module batch, show "starting services" (amber) too.
        // ADFA-4842: a module (solo-proot) install must never read as "Starting services" — that is REST
        // wording and would suggest we're bringing the server up while runroles own the rootfs. During the
        // runroles show "Modules are installing"; only the real post-DONE restart says "Starting services".
        boolean moduleFlow = moduleInSession();
        // ADFA-4898: the module batch reached its terminal with at least one runrole failed. The server
        // itself came up (so this is NOT moduleServerFailed), and a green "Adding your content" here read
        // the failed batch as a clean success. Keep the SAME amber "installing" header it had before the
        // failure — no new copy — and let the failure + Retry surface per-module below (hub pill + detail),
        // not in this batch-level header.
        boolean moduleFailed = moduleFlow && prootFailed > 0;
        // Amber "working" while a module install runs, its post-DONE restart is pending, or the batch
        // ended with a failed module (kept on the same amber install line, never a green success).
        // K2GO-434: the status tone/message + the bottom-controls mode are a pure view-state rule
        // (setup/domain/SetupUiState). This pass gathers inputs; the Activity maps the result to
        // resources (here) and to show()/scheduleRedirect()/cancelRedirect() (in the controls block).
        SetupUiState ui = new SetupUiState.Inputs()
                .batchServerSlow(batchServerSlow).batchServerSettling(batchServerSettling)
                .batchServerUp(batchServerUp).moduleFlow(moduleFlow).moduleFailed(moduleFailed)
                .servicesReady(pipeline.servicesReady()).slowByPolls(pipeline.readyPolls() >= SetupProgressController.SLOW_AFTER_POLLS)
                .success(verdict.success()).failure(verdict.failure()).redirectCancelled(redirectCancelled)
                .runInBackgroundEnabled((pipeline.servicesReady() || forgejoSeedActive()) && !prootActive)
                .build();
        tint(dot, ui.tone == SetupUiState.StatusTone.WAITING ? R.color.k2go_amber : R.color.k2go_leaf);
        int statusRes = statusStringRes(ui.message);
        // Animate a "…" (dots appear/disappear) on the amber waiting line so it never looks frozen.
        if (ui.animate) statusEllipsis.start(getString(statusRes));
        else { statusEllipsis.stop(); statusText.setText(statusRes); }

        // ADFA-5074: a detail card is covering the index. Everything above still had to be computed
        // (whether the run finished is a fact about the run, not about which screen is in front), but
        // the controls below are not on screen. The detail host records the latest verdict, keeps the
        // detail bar in step with the queue, and steps back to the index when a run that was still in
        // flight when the detail opened completes while the user watches it. The step-back is armed once
        // per opening and only fires while resumed (a poll callback can outlive onPause, and commitNow
        // on a stopped FragmentManager would throw); SetupDetailHost holds both conditions.
        detailHost.onVerdict(allComplete);
        if (detailHost.isShowingDetail()) {
            boolean resumed = getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED);
            detailHost.refreshWhileShowing(allComplete, resumed);
            return;
        }

        // Bottom controls. ADFA-4842: a failed post-module server restart counts as a failure (Finish +
        // note), never a silent success — so the user is told, not dropped on a dead Home.
        // K2GO-434: apply the bottom-controls mode from SetupUiState. The Run-in-background visibility
        // rule (only once the run's shape is known and no proot module gates the index: ADFA-4919/5074,
        // plus the K2GO-423 live seed) is carried by runInBackgroundVisible, computed with the state above.
        switch (ui.controls) {
            case REDIRECT:
                show(redirect, true); show(cancel, true);
                show(finishBtn, false); show(finishNote, false); show(runBgBtn, false);
                scheduleRedirect();
                break;
            case FINISH_SUCCESS:   // success, but the user cancelled the countdown
                cancelRedirect();
                show(finishBtn, true); show(runBgBtn, false);
                show(redirect, false); show(cancel, false); show(finishNote, false);
                break;
            case FINISH_FAILURE:
                cancelRedirect();
                show(finishBtn, true); show(finishNote, true); show(runBgBtn, false);
                show(redirect, false); show(cancel, false);
                break;
            case RUNNING:
            default:
                cancelRedirect();
                show(runBgBtn, ui.runInBackgroundVisible);
                show(finishBtn, false); show(finishNote, false); show(redirect, false); show(cancel, false);
                break;
        }
    }

    /** ADFA-5011: dedicated render for a dashboard rebuild — one row + a status line driven by
     *  InstallProgressRepository. While running the screen is the gate (no Run in background, Back is
     *  softened then backgrounds the app); on SUCCESS it redirects to a live Library; on FAILED it
     *  shows Finish + the note (never a silent success on a half-rebuilt server). */
    /** K2GO-434: map the semantic status message (SetupUiState) to its string resource. */
    private int statusStringRes(SetupUiState.StatusMessage m) {
        switch (m) {
            case SLOW: return R.string.k2go_setup_slow;
            case STARTING: return R.string.k2go_setup_starting;
            case INSTALLING: return R.string.install_busy_modules;
            case ADDING:
            default: return R.string.k2go_setup_adding;
        }
    }

    private void renderRebuild() {
        InstallState st = InstallProgressRepository.get().current();
        if (st.isRunning()) pipeline.markRebuildRunningSeen();
        // Only honor a terminal state once THIS rebuild has been seen running — otherwise a stale
        // SUCCESS/FAILED from a previous rebuild would flash on entry and trigger a premature redirect.
        boolean rebuiltOk = pipeline.rebuildRunningSeen() && st.phase == InstallState.Phase.SUCCESS;
        boolean rebuildFailed = pipeline.rebuildRunningSeen() && st.phase == InstallState.Phase.FAILED;
        // K2GO-434: the rebuild state -> view decision is a pure rule (setup/domain/RebuildUiState).
        // The apiReady wait that feeds rebuildServerUp/Failed is driven by readyPoll (lifecycle-managed);
        // this method only reflects state and maps the phase to the row, status line and controls.
        RebuildUiState rb = RebuildUiState.from(
                rebuiltOk, rebuildFailed, pipeline.rebuildServerUp(), pipeline.rebuildServerFailed(), redirectCancelled);

        String sub;
        switch (rb.phase) {
            case ERROR:
                sub = rb.errorIsRebuildFailure
                        ? ((st.message != null && !st.message.isEmpty()) ? st.message : getString(R.string.k2go_dash_rebuild_failed))
                        : getString(R.string.k2go_dash_services_failed);
                break;
            case DONE: sub = getString(R.string.k2go_setup_state_done); break;
            case SERVER_WAIT: sub = getString(R.string.k2go_setup_starting); break;
            case BUILDING:
            default: sub = getString(R.string.k2go_dash_rebuild_building); break;
        }

        sections.removeAllViews();
        boolean check = rb.phase == RebuildUiState.Phase.DONE || rb.phase == RebuildUiState.Phase.SERVER_WAIT;
        sections.addView(rebuildRow(check, rb.phase == RebuildUiState.Phase.ERROR, sub));

        if (contextText != null) contextText.setText(R.string.k2go_setup_context_proot);

        tint(dot, rb.phase == RebuildUiState.Phase.DONE ? R.color.k2go_leaf : R.color.k2go_amber);
        boolean working = rb.phase == RebuildUiState.Phase.BUILDING || rb.phase == RebuildUiState.Phase.SERVER_WAIT;
        if (working) {
            statusEllipsis.start(getString(rb.phase == RebuildUiState.Phase.SERVER_WAIT
                    ? R.string.k2go_setup_starting : R.string.k2go_dash_rebuilding));
        } else {
            statusEllipsis.stop();
            statusText.setText(rb.phase == RebuildUiState.Phase.DONE ? R.string.k2go_setup_state_done
                    : (rb.errorIsRebuildFailure ? R.string.k2go_dash_rebuild_failed : R.string.k2go_dash_services_failed));
        }

        switch (rb.controls) {
            case REDIRECT:
                redirect.setText(R.string.k2go_dash_redirect);   // rebuild-specific wording (not "Installation complete")
                show(redirect, true); show(cancel, true);
                show(finishBtn, false); show(finishNote, false); show(runBgBtn, false);
                scheduleRedirect();
                break;
            case FINISH_SUCCESS:   // cancelled by the user: stay, reveal Finish
                cancelRedirect();
                show(finishBtn, true); show(runBgBtn, false);
                show(redirect, false); show(cancel, false); show(finishNote, false);
                break;
            case FINISH_FAILURE:
                cancelRedirect();
                show(finishBtn, true); show(finishNote, true); show(runBgBtn, false);
                show(redirect, false); show(cancel, false);
                break;
            case GATED:
            default:   // building or waiting for services: the screen is the gate, no leaving
                cancelRedirect();
                show(runBgBtn, false); show(finishBtn, false); show(finishNote, false);
                show(redirect, false); show(cancel, false);
                break;
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
        return "  ·  " + org.appdevforall.k2go.util.ByteFormatter.toHuman(bytesPerSec)
                + getString(R.string.k2go_rate_per_second);
    }

    private View streamRow(String heading, String key, boolean sess, int[] status, int doneVal, int failedVal,
                           boolean complete, int wishlistCount, long bytesPerSec, int barPercent) {
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
        addRowProgressBar(col, barPercent, sess && !complete);   // ADFA-4893: determinate bar for REST streams (ZIM)
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        // ADFA-5074: no chevron on a row that does not open. It was always drawn, which was
        // harmless while a queued row was a brief state during an install; with a real queue a
        // row can sit "Queued" for an hour, offering to be tapped and doing nothing.
        chev.setVisibility(sess ? View.VISIBLE : View.INVISIBLE);
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));

        if (sess) row.setOnClickListener(v -> detailHost.openDetail(key));   // detail only once there's a live session
        return row;
    }

    /** K2GO-423: summary row for the Forgejo seed (one unit, not per-item): a phase subtitle plus a
     *  running "repositories added" count, tappable to the seed detail once it has a session. Bespoke
     *  rather than streamRow because the seed has a coarse state, not a per-item status array. */
    private View forgejoSeedRow() {
        org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository repo =
                org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository.get();
        boolean sess = repo.hasSession();
        boolean complete = repo.isComplete();
        boolean failed = repo.isFailed();

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
        slot.addView(indicator(sess, complete, failed ? 1 : 0));
        row.addView(slot, slotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this);
        h.setText(R.string.k2go_forgejo_seed_title);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(h);
        TextView sub = new TextView(this);
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        String state;
        if (failed) state = getString(R.string.k2go_forgejo_seed_state_failed);
        else if (complete) state = getString(R.string.k2go_forgejo_seed_state_done);
        else if (!sess) state = getString(R.string.k2go_setup_state_queued);
        else {
            state = getString(R.string.k2go_forgejo_seed_state_active);
            if (repo.includeRepos() && repo.reposSeeded() > 0) {
                state += "  ·  " + getString(R.string.k2go_forgejo_seed_repos_fmt, repo.reposSeeded());
            }
        }
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, failed ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        chev.setVisibility(sess ? View.VISIBLE : View.INVISIBLE);
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));

        if (sess) row.setOnClickListener(v -> detailHost.openDetail("forgejo"));
        return row;
    }

    /** ADFA-4900/4901: summary row for the maps (proot) stage, driven by the module-queue state:
     *  queued -> spinner (runrole in flight) -> check (done) / amber alert (failed). Tappable once
     *  the stage has started; opens the maps Preparing card as its detail (ADFA-4901). */
    /** ADFA-5228: determinate install bar for a running proot module row (moduleRow + mapsRow share
     *  it). Only shown while the module is running and has a task table (percent >= 0); otherwise the
     *  row keeps its indeterminate indicator. Lifted off the card's bottom edge with a small margin. */
    private void addRowProgressBar(LinearLayout col, ModuleQueueState mq, boolean running) {
        addRowProgressBar(col, mq.percent, running);   // ADFA-5228 (proot rows)
    }

    /** ADFA-4893: same determinate row bar driven by a plain percent, for REST streams (ZIM). Hidden
     *  when not running or percent < 0, so a paused/indeterminate stream keeps its indicator. */
    private void addRowProgressBar(LinearLayout col, int percent, boolean running) {
        if (!(running && percent >= 0)) return;
        com.google.android.material.progressindicator.LinearProgressIndicator bar =
                new com.google.android.material.progressindicator.LinearProgressIndicator(this);
        bar.setIndeterminate(false);
        bar.setMax(100);
        bar.setProgressCompat(percent, true);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = px(8);
        barLp.bottomMargin = px(4);
        col.addView(bar, barLp);
    }

    /** ADFA-4893: the ZIM index row bar shares one formula with the status screen — see
     *  {@link ZimDownloadService#overallPercent()}. Here we only gate visibility: -1 (no bar) unless a
     *  job is actively running, so a terminal/failed row falls back to its indicator. */
    private int zimOverallPercent() {
        return (ZimDownloadService.isRunning() && !ZimDownloadService.isComplete())
                ? ZimDownloadService.overallPercent() : -1;
    }

    /** ADFA-4893: Books index row bar — same gating as ZIM, item-count percent from the service. */
    private int booksOverallPercent() {
        return (BooksDownloadService.isRunning() && !BooksDownloadService.isComplete())
                ? BooksDownloadService.overallPercent() : -1;
    }

    private View mapsRow() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean done = pipeline.mapsStartFailed() || (mapsInSession() && mq.phase == ModuleQueueState.Phase.DONE);
        boolean running = !done && (ModuleQueueRepository.get().isRunning()
                || (mapsInSession() && mq.phase != ModuleQueueState.Phase.DONE));
        boolean failed = pipeline.mapsStartFailed() || (done && mq.failedModules.contains("maps"));
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
        else state = mq.percent >= 0                                   // ADFA-5228: show the % when determinate
                ? getString(R.string.k2go_maps_phase_building) + "  " + mq.percent + "%"
                : getString(R.string.k2go_maps_phase_building);
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, failed ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        addRowProgressBar(col, mq, running);   // ADFA-5228
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // ADFA-4901: like the ZIM/Books rows, maps opens its own progress detail once the stage has
        // started (running or done). Maps is a single proot runrole, so the detail is the queue-driven
        // Preparing card (spinner + phase), not a per-item checklist.
        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        chev.setVisibility(started ? View.VISIBLE : View.INVISIBLE);
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));
        if (started) row.setOnClickListener(v -> detailHost.openDetail("maps"));
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

        boolean failed = (mq.failedModules != null && mq.failedModules.contains(key)) || pipeline.moduleStartFailed();
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
        else if (running) state = mq.percent >= 0                       // ADFA-5228: show the % when determinate
                ? getString(R.string.k2go_mod_phase_installing) + "  " + mq.percent + "%"
                : getString(R.string.k2go_mod_phase_installing);
        else state = getString(R.string.k2go_mod_phase_queued);
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, failed ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        addRowProgressBar(col, mq, running);   // ADFA-5228
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        chev.setVisibility(started ? View.VISIBLE : View.INVISIBLE);
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));
        if (started) row.setOnClickListener(v -> detailHost.openDetail("mod:" + key));
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
    /** ADFA-5343 (Phase 2): a module batch stopped the server for its runroles; when the batch is
     *  terminal the server should come back. We no longer boot + poll here. We record the intent
     *  (userWantsOn) once — the lock is already released and the durable guard cleared before the queue
     *  publishes DONE, so desired flips UP — and the reconciler drives it up and keeps it up (re-driving
     *  a flap), so a redirect to Home lands on a live system rather than a dead one (5336). The wait
     *  timestamp anchors the "taking longer" UI. When actuation is disabled (rollback), we boot once here
     *  as before (and Home is a monitor again, so 5336 is not fixed in that mode). */
    private void onModuleBatchTerminal() {
        if (!pipeline.beginModuleServerWait()) return;   // once: also the "taking longer" timeout anchor
        new org.appdevforall.k2go.Preferences(this).setWatchdogEnable(true);   // persisted intent → desired = UP
        if (!org.appdevforall.k2go.env.ServerLifecycleReconciler.ACTUATES) {
            serverController.startEnvironment();   // rollback path: reconciler is log-only, boot here
        }
    }

    /**
     * ADFA-5343 (§3.3 follow-up): hand an interrupted (dead-process) install to LibraryActivity's single
     * recovery path. {@code CLEAR_TOP} <b>without</b> {@code SINGLE_TOP} so the standard-launchMode
     * LibraryActivity is finished and re-created — its {@code onCreate} re-computes {@code recovering}
     * and schedules {@code evaluateRecovery} (which boots a healthy base and clears the marker, or
     * declares DAMAGED and sets {@code userWantsOn=false} to cut the reconciler's retry). Reusing the
     * instance via {@code onNewIntent} would not, since Home is a monitor there.
     */
    private void routeToRecovery() {
        startActivity(new android.content.Intent(this, LibraryActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_library));
        finish();
    }

    private void goHome(boolean clearSessions) {
        cancelRedirect();
        if (clearSessions) { ZimDownloadService.finishSession(); BooksDownloadService.finishSession(); KolibriSeedService.finishSession(); org.appdevforall.k2go.forgejo.presentation.ForgejoSeedService.finishSession(); }
        ModuleBatch.clear(this);   // ADFA-4842: this run's module batch is done

        // ADFA-4919: the natural end of installing is the Library — go there directly and clear the
        // install screens above it. Both the wizard and Get More launch from LibraryActivity, so
        // CLEAR_TOP + SINGLE_TOP lands on the existing Library (dropping Get More + this index).
        // K2GO-382: "Run in background" now reaches here too via goHome(false) — same Home landing, but
        // clearSessions=false leaves the download sessions running. ADFA-5343: a
        // module batch set desired=UP; the reconciler brings the server up and keeps re-driving it wherever
        // the app is, so the reused Library is (or becomes) live on arrival — even Finish under a slow/flap
        // start lands on a Home the reconciler drives up, not a dead one (5336).
        startActivity(new android.content.Intent(this, LibraryActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_library));   // ADFA-4842: land on Home, not the launching tab (Settings)
        finish();
    }

    /** K2GO-423: re-run a Forgejo seed that gave up (the Retry on the failed seed detail). The give-up
     *  cleared the banked marker, so re-bank it with the opt-in the session used, restart the service,
     *  and re-kick the pipeline poll so the index row and the completion gate track the retry. */
    private void retryForgejoSeed() {
        boolean includeRepos = org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository.get().includeRepos();
        org.appdevforall.k2go.forgejo.data.ForgejoInstallPrefs.bankSeed(this, includeRepos);
        org.appdevforall.k2go.forgejo.presentation.ForgejoSeedService.start(this);
        pipeline.kick();
    }

    /**
     * ADFA-4898 P5: strong confirmation before cancelling a running module install, then send
     * ACTION_CANCEL. The service kills the runrole (proot --kill-on-exit), rolls back the speculative
     * flag and marks the module failed; the base system is untouched and the server restarts.
     */
    private void confirmCancelModule() {
        // K2GO-385: cancel-running-install confirm -> the shared BrandDialog (destructive).
        new org.appdevforall.k2go.ui.dialog.BrandDialog(this)
                .setTitle(R.string.k2go_mod_cancel_title)
                .setMessage(R.string.k2go_mod_cancel_body)
                .setDestructive(R.string.k2go_mod_cancel_confirm, () ->
                        startService(new android.content.Intent(this, org.appdevforall.k2go.install.presentation.InstallService.class)
                                .setAction(org.appdevforall.k2go.install.presentation.InstallService.ACTION_CANCEL)))
                .setNegative(R.string.k2go_mod_cancel_dismiss, null)
                .show();
    }

    // ---- ServerController.Host (ADFA-4842): minimal — the index only needs to START the server after
    // a module batch. UI-affordance callbacks are no-ops here (the index has its own status line). ----
    @Override public void addToLog(String message) { android.util.Log.d("K2Go-SetupProgress", message); }
    @Override public void startFusionPulse() { }
    @Override public void stopBtnProgress() { }
    @Override public void updateConnectivityLeds(boolean wifiOn, boolean hotspotOn) { }
    @Override public void refreshServerUi() { }

}
