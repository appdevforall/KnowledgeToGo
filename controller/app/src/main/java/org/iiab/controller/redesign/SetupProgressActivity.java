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
import org.iiab.controller.install.presentation.ModuleQueueRepository;
import org.iiab.controller.install.presentation.ModuleQueueState;
import org.iiab.controller.util.AppExecutors;

public class SetupProgressActivity extends AppCompatActivity {

    private static final long READY_POLL_MS = 2000L;
    private static final long REDIRECT_MS = 3000L;
    // ADFA-4900: if the maps module queue never reports RUNNING/DONE this long after hand-off, treat
    // it as a start failure so the pipeline can't hang forever waiting on a stage that never began.
    private static final long MAPS_START_TIMEOUT_MS = 30000L;
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
    private boolean probing = false;
    private boolean mapsLaunched = false;   // ADFA-4900: maps (proot) stage has been handed to the queue
    private long mapsLaunchedAt = 0L;       // ADFA-4900: elapsedRealtime when maps was handed off
    private boolean mapsStartFailed = false; // ADFA-4900: queue never started within the timeout
    private int readyPolls = 0;   // ADFA-4874: failed readiness polls so far (slow-start message)

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_k2go_setup_progress);

        dot = findViewById(R.id.k2go_sp_dot);
        statusText = findViewById(R.id.k2go_sp_status);
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

        // ADFA-4919: observe the maps (proot) queue so its RUNNING -> DONE transition always
        // re-renders the index. The REST streams have service listeners; the proot stage had none,
        // so a proot-only install could finish without the index ever updating to Finish/redirect.
        ModuleQueueRepository.get().state().observe(this, st -> render());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!showingDetail) {
            ZimDownloadService.setListener(this::render);
            BooksDownloadService.setListener(this::render);
            main.post(readyPoll);
            render();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(readyPoll);
        cancelRedirect();
        if (!showingDetail) {
            ZimDownloadService.setListener(null);
            BooksDownloadService.setListener(null);
        }
    }

    @Override
    public void onBackPressed() {
        if (showingDetail) backToIndex();
        else super.onBackPressed();
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
     * ADFA-4900: one step of the serialized install pipeline. Starts the next stage only when the
     * previous one has finished; proot (maps) runs exclusively before any REST download, so Ansible's
     * background forks never overlap a live REST job. Returns true while work remains (keep polling),
     * false once every stage has been started and is complete.
     */
    private boolean orchestrateStep() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean queueRunning = ModuleQueueRepository.get().isRunning();

        // Stage 1 — maps (proot), exclusive of all REST work (proot tasks run serially via the queue).
        if (MapsProvisioner.hasPending(this)) {
            if (!queueRunning) { MapsProvisioner.drain(this); mapsLaunched = true; mapsLaunchedAt = SystemClock.elapsedRealtime(); }
            return true;
        }
        if (queueRunning) return true;                                      // maps runrole in flight
        if (mapsLaunched && !mapsStartFailed && mq.phase != ModuleQueueState.Phase.DONE) {
            // Launched but the queue hasn't reported RUNNING/DONE yet. Wait, but fail closed if it
            // never starts (ADFA-4900/#1) so the pipeline can't hang on a stage that never began.
            if (SystemClock.elapsedRealtime() - mapsLaunchedAt > MAPS_START_TIMEOUT_MS) mapsStartFailed = true;
            else return true;
        }

        // Stage 2 — REST: ZIM and Books run CONCURRENTLY (both are REST calls and don't conflict);
        // only proot (maps) needs exclusivity. Keep polling until both are complete.
        if (ZimProvisioner.hasPending(this)) ZimProvisioner.drain(this);
        if (BooksProvisioner.hasPending(this)) BooksProvisioner.drain(this);
        boolean restBusy = (ZimDownloadService.hasSession() && !ZimDownloadService.isComplete())
                || (BooksDownloadService.hasSession() && !BooksDownloadService.isComplete())
                || ZimProvisioner.hasPending(this) || BooksProvisioner.hasPending(this);
        if (restBusy) return true;

        // Every stage has been started and is complete.
        drained = true;
        return false;
    }

    // ---- render ----
    private void render() {
        if (sections == null || showingDetail) return;

        boolean mapsShown = MapsProvisioner.hasPending(this) || mapsLaunched;   // ADFA-4900
        boolean zimShown = ZimDownloadService.hasSession() || ZimWishlist.size(this) > 0;
        boolean booksShown = BooksDownloadService.hasSession() || BooksWishlist.size(this) > 0;

        sections.removeAllViews();
        // ADFA-4900: maps (proot) runs first in the pipeline, so its row leads the list.
        if (mapsShown) sections.addView(mapsRow());
        if (zimShown) sections.addView(streamRow(getString(R.string.k2go_gm_wikipedia_title), "zim",
                ZimDownloadService.hasSession(), ZimDownloadService.status(),
                ZimDownloadService.DONE, ZimDownloadService.FAILED,
                ZimDownloadService.hasSession() && ZimDownloadService.isComplete(), ZimWishlist.size(this)));
        if (booksShown) sections.addView(streamRow(getString(R.string.k2go_gm_books_title), "books",
                BooksDownloadService.hasSession(), BooksDownloadService.status(),
                BooksDownloadService.DONE, BooksDownloadService.FAILED,
                BooksDownloadService.hasSession() && BooksDownloadService.isComplete(), BooksWishlist.size(this)));

        // Overall state. Completion is stage-based.
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        // ADFA-4919: a proot-only set (no REST content at all) must finish WITHOUT waiting on any
        // REST drain -- its completion is simply the maps (proot) stage going terminal. Otherwise the
        // index never reaches success/failure and can't show redirect/Cancel/Finish. The REST/mixed
        // path keeps its existing drain-based signal untouched.
        boolean noRest = !ZimDownloadService.hasSession() && !BooksDownloadService.hasSession()
                && ZimWishlist.size(this) == 0 && BooksWishlist.size(this) == 0;
        boolean mapsTerminal = mapsStartFailed
                || (mapsLaunched && mq.phase == ModuleQueueState.Phase.DONE);
        // ADFA-4919: a proot module is queued/running (the gate is active).
        boolean prootActive = mapsShown && !mapsTerminal;
        contextText.setText(prootActive ? R.string.k2go_setup_context_proot : R.string.k2go_setup_context);
        boolean allComplete;
        if (noRest && mapsShown) {
            allComplete = mapsTerminal && !ModuleQueueRepository.get().isRunning();
        } else {
            allComplete = drained
                    && (!ZimDownloadService.hasSession() || ZimDownloadService.isComplete())
                    && (!BooksDownloadService.hasSession() || BooksDownloadService.isComplete());
        }
        // ADFA-4900: a failed maps runrole must count as a failure too (Finish, not a false success).
        boolean mapsFailed = mapsStartFailed
                || (mq.phase == ModuleQueueState.Phase.DONE && mq.failedModules.contains("maps"));
        int failedTotal = failedCount(ZimDownloadService.hasSession() ? ZimDownloadService.status() : null, ZimDownloadService.FAILED)
                + failedCount(BooksDownloadService.hasSession() ? BooksDownloadService.status() : null, BooksDownloadService.FAILED)
                + (mapsFailed ? 1 : 0);

        // Status dot + line. While waiting, a long-stuck engine shows a softer "taking longer"
        // message instead of "Starting services" so it doesn't look frozen (ADFA-4874).
        tint(dot, servicesReady ? R.color.k2go_leaf : R.color.k2go_amber);
        int statusRes = servicesReady ? R.string.k2go_setup_adding
                : (readyPolls >= SLOW_AFTER_POLLS ? R.string.k2go_setup_slow : R.string.k2go_setup_starting);
        statusText.setText(statusRes);

        // Bottom controls.
        boolean success = allComplete && failedTotal == 0;
        boolean failure = allComplete && failedTotal > 0;
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
            show(runBgBtn, !prootActive);   // ADFA-4919: no "Run in background" while a proot module runs — the index is the gate
            show(finishBtn, false); show(finishNote, false); show(redirect, false); show(cancel, false);
        }
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
    private View streamRow(String heading, String key, boolean sess, int[] status, int doneVal, int failedVal,
                           boolean complete, int wishlistCount) {
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
        String state;
        if (!sess) state = getString(R.string.k2go_setup_state_queued);
        else if (complete && failed > 0) state = getString(R.string.k2go_setup_state_failed_fmt, failed);
        else if (complete) state = getString(R.string.k2go_setup_state_done);
        else state = getString(R.string.k2go_setup_state_progress_fmt, done, n);
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, (sess && failed > 0) ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));

        if (sess) row.setOnClickListener(v -> openDetail(key));   // detail only once there's a live session
        return row;
    }

    /** ADFA-4900/4901: summary row for the maps (proot) stage, driven by the module-queue state:
     *  queued -> spinner (runrole in flight) -> check (done) / amber alert (failed). Tappable once
     *  the stage has started; opens the maps Preparing card as its detail (ADFA-4901). */
    private View mapsRow() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean done = mapsStartFailed || (mapsLaunched && mq.phase == ModuleQueueState.Phase.DONE);
        boolean running = !done && (ModuleQueueRepository.get().isRunning()
                || (mapsLaunched && mq.phase != ModuleQueueState.Phase.DONE));
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
    private void goHome(boolean clearSessions) {
        cancelRedirect();
        if (clearSessions) { ZimDownloadService.finishSession(); BooksDownloadService.finishSession(); }
        finish();
    }

    // ---- detail: host the real per-module card ----
    private void openDetail(String key) {
        showingDetail = true;
        androidx.fragment.app.Fragment f;
        if ("zim".equals(key)) f = ZimPreparingFragment.newInstance(true);
        else if ("maps".equals(key)) f = MapsPreparingFragment.newInstance(true);   // ADFA-4901: observe-only
        else f = BooksDownloadsFragment.newInstance(true);
        // ADFA-4919: the proot (maps) detail cannot background either — only Back (to the index).
        if (detailRunBgBtn != null) detailRunBgBtn.setVisibility("maps".equals(key) ? View.GONE : View.VISIBLE);
        getSupportFragmentManager().beginTransaction().replace(R.id.k2go_sp_fraghost, f).commit();
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
}
