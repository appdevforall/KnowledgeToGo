/*
 * ============================================================================
 * Name        : SetupDetailHost.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-434 (slice 6). The per-stream detail host carved out of
 *               SetupProgressActivity: opening a row's real detail fragment over the index, the
 *               two-button detail action bar (Back / Run-in-background, or Retry / Cancel for a
 *               module or seed), and returning to the index. Activity-scoped (owns the index/detail
 *               views and a narrow Host for the FragmentManager and the actions a bar can fire).
 *               Behavior-preserving: the fragment transactions keep commitNow exactly as before: the
 *               listener-reclaim ordering the ADFA-5074 / backToIndex comments protect is unchanged.
 *               See controller/docs/ADR-434-setupprogress-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.view.View;
import android.widget.Button;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;
import org.appdevforall.k2go.install.presentation.ModuleQueueState;
import org.appdevforall.k2go.kolibri.presentation.KolibriSeedingFragment;
import org.appdevforall.k2go.system.domain.ContentType;
import org.appdevforall.k2go.system.domain.Operation;

/**
 * The Activity provides only what the detail host cannot do itself: the FragmentManager for the
 * transactions, a view refresh, and the three actions a configured bar can fire (leave and keep
 * provisioning, retry the Forgejo seed, confirm-cancel a running module). The detail state
 * (showing/key/bounce) and the index/detail view toggle live here.
 */
public final class SetupDetailHost {

    public interface Host {
        FragmentManager fragmentManager();
        void render();
        /** K2GO-382: land on Home; clearSessions=false leaves the downloads running in the background. */
        void goHome(boolean clearSessions);
        /** K2GO-423: re-run a Forgejo seed that gave up (the Retry on the failed seed detail). */
        void retryForgejoSeed();
        /** ADFA-4898 P5: confirm, then cancel a running module install. */
        void confirmCancelModule();
    }

    private final Host host;
    private final View indexScroll;
    private final View detailRoot;
    private final Button detailBackBtn;
    private final Button detailRunBgBtn;

    private boolean showingDetail = false;
    /** ADFA-4898: the key currently shown in the detail host ("mod:<k>", "zim", ...), or null on the index. */
    private String detailKey;

    /**
     * ADFA-5074: whether a completed run should take this detail away again.
     *
     * <p>Armed when a detail is opened over work still in flight, cleared when it fires. A detail
     * opened over a run that had already finished is a deliberate look at the result: usually at a
     * failed row, whose retry lives only there, and must not be closed underneath the user.
     */
    private boolean bounceOnComplete = false;

    /** The last completion verdict, so opening a detail can tell "still working" from "finished". */
    private boolean lastAllComplete = false;

    public SetupDetailHost(Host host, View indexScroll, View detailRoot,
                           Button detailBackBtn, Button detailRunBgBtn) {
        this.host = host;
        this.indexScroll = indexScroll;
        this.detailRoot = detailRoot;
        this.detailBackBtn = detailBackBtn;
        this.detailRunBgBtn = detailRunBgBtn;
        // ADFA-4898: the two detail buttons are (re)configured per shown detail by configureDetailBar()
        // : normally [Back (primary) / Run in background (secondary, LIVE only)], and for a failed module
        // [Retry (primary) / Back (secondary)]. The defaults here cover the window before the first
        // configure and any non-module detail.
        detailBackBtn.setOnClickListener(v -> backToIndex());
        detailRunBgBtn.setText(R.string.k2go_zim_run_bg);   // in a detail, secondary = leave (never abort)
        detailRunBgBtn.setOnClickListener(v -> host.goHome(false));   // K2GO-382: land on Home, keep provisioning
    }

    public boolean isShowingDetail() { return showingDetail; }

    /** render() records the latest verdict here every pass, even on the index, so a later openDetail
     *  arms the bounce correctly. */
    public void onVerdict(boolean allComplete) { lastAllComplete = allComplete; }

    /**
     * render() detail branch: keep the bar in step with the queue, and bounce back to the index when a
     * run that was still in flight when the detail opened completes while the user is watching.
     * {@code resumed} is passed in so this stays free of the Activity lifecycle.
     */
    public void refreshWhileShowing(boolean allComplete, boolean resumed) {
        // ADFA-4898: keep the detail bar in step with the queue : a module that fails shows Retry,
        // and a Retry that puts it back to RUNNING restores Back/Run-in-background on the next tick.
        configureDetailBar();
        if (allComplete && bounceOnComplete && resumed) {
            bounceOnComplete = false;
            backToIndex();
        }
    }

    // ---- detail: host the real per-module card ----
    public void openDetail(String key) {
        showingDetail = true;
        detailKey = key;
        bounceOnComplete = !lastAllComplete;
        Fragment f;
        // Per-key detail view : presentation routing only; the execution class is NOT decided here.
        if (key.startsWith("mod:")) { f = ModuleInstallFragment.newInstance(key.substring(4)); }  // ADFA-4842
        else if ("zim".equals(key)) { f = new ZimPreparingFragment(); }   // ADFA-5074: observe-only
        else if ("kolibri".equals(key)) { f = new KolibriSeedingFragment(); }   // ADFA-4954: observe-only
        else if ("forgejo".equals(key)) { f = new org.appdevforall.k2go.forgejo.presentation.ForgejoSeedingFragment(); }   // K2GO-423: observe-only
        else if ("maps".equals(key)) { f = MapsPreparingFragment.newInstance(true); }   // ADFA-4901: observe-only
        else { f = BooksDownloadsFragment.newInstance(true); }
        configureDetailBar();
        // ADFA-5074: commitNow, to match backToIndex. With an async commit a render() landing in
        // between set showingDetail back to false and found nothing to remove, and the queued
        // transaction then added the fragment into a hidden host : where ZimPreparingFragment
        // takes the service listener with nobody left to reclaim it, freezing the index's row.
        // The mirror image of the bug backToIndex's commitNow already exists for. Only reachable
        // from a row tap now that the intent routing is gone, so the activity is resumed and the
        // synchronous commit is safe.
        host.fragmentManager().beginTransaction().replace(R.id.k2go_sp_fraghost, f).commitNow();
        indexScroll.setVisibility(View.GONE);
        detailRoot.setVisibility(View.VISIBLE);
    }

    /**
     * ADFA-5062: only a LIVE op (zim/kolibri/books) can keep running in the background; a stopped-class
     * detail (maps or a module install) cannot. Read from the model, not re-derived from the key prefix.
     */
    private boolean isLiveDetail(String key) {
        if (key == null) return false;
        if (key.startsWith("mod:")) return Operation.appInstall(key.substring(4)).isLive();
        if ("forgejo".equals(key)) return true;   // K2GO-423: the seed is a live, backgroundable step
        ContentType ct = ContentType.byKey(key);
        return ct != null && ct.isLive();
    }

    /**
     * ADFA-4898: (re)configure the two-button detail bar for the currently shown detail. Reuses the one
     * existing template : a filled primary (k2go_sp_back) over an outlined secondary (k2go_sp_detail_finish):
     *   - a failed module -> Retry (primary) + Back (secondary), so the recovery action sits where the LIVE
     *     details put Run-in-background, instead of a bespoke button in the card;
     *   - anything else -> Back (primary) + Run in background (secondary, LIVE only).
     * Recomputed on every render while a detail is open, so a Retry that puts the module back to RUNNING
     * flips the bar back to Back/Run-in-background on the next tick (no stale Retry). Retry stays on the
     * card: this detail is the live progress view and follows the re-run with its log.
     */
    private void configureDetailBar() {
        if (!showingDetail || detailKey == null) return;
        // K2GO-423: the Forgejo seed detail offers Retry on failure, mirroring the module Retry. The
        // seed's give-up cleared the banked marker (A), so retryForgejoSeed() re-banks with the same
        // repo opt-in and restarts the service; the bar flips back to Back/Run-in-background on the
        // next tick once the seed is running again. (A durable/one-tap re-seed after leaving is K2GO-422.)
        if ("forgejo".equals(detailKey)
                && org.appdevforall.k2go.forgejo.presentation.ForgejoSeedRepository.get().isFailed()) {
            detailBackBtn.setText(R.string.k2go_home_retry);
            detailBackBtn.setOnClickListener(v -> host.retryForgejoSeed());
            detailRunBgBtn.setText(R.string.k2go_setup_back);
            detailRunBgBtn.setOnClickListener(v -> backToIndex());
            detailRunBgBtn.setVisibility(View.VISIBLE);
            return;
        }
        // K2GO-394: the maps detail opens under the legacy key "maps" (not "mod:maps"), but maps IS a
        // module, so treat it as one here -> it gets the same Cancel-while-running (the mockup's op-level
        // Cancel install) and Retry-on-failure the other modules already have, with no duplicated logic.
        final boolean isModule = detailKey.startsWith("mod:") || "maps".equals(detailKey);
        final String moduleKey = !isModule ? null
                : (detailKey.startsWith("mod:") ? detailKey.substring(4) : detailKey);
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        boolean moduleFailed = isModule && mq.didFail(moduleKey);
        boolean moduleRunning = isModule && mq.isInstalling(moduleKey);
        if (moduleFailed) {
            detailBackBtn.setText(R.string.k2go_home_retry);
            detailBackBtn.setOnClickListener(v -> ModuleRetry.fire(v, moduleKey));
            detailRunBgBtn.setText(R.string.k2go_setup_back);
            detailRunBgBtn.setOnClickListener(v -> backToIndex());
            detailRunBgBtn.setVisibility(View.VISIBLE);
        } else if (moduleRunning) {
            // ADFA-4898 P5: while this module's runrole runs, offer a confirmed Cancel in the same
            // secondary slot (Back stays primary). Cancel kills the runrole and surfaces the module as
            // failed, so the Retry above appears on the next tick : the "immediate retry" of the ticket.
            detailBackBtn.setText(R.string.k2go_setup_back);
            detailBackBtn.setOnClickListener(v -> backToIndex());
            detailRunBgBtn.setText(R.string.k2go_setup_cancel);
            detailRunBgBtn.setOnClickListener(v -> host.confirmCancelModule());
            detailRunBgBtn.setVisibility(View.VISIBLE);
        } else {
            detailBackBtn.setText(R.string.k2go_setup_back);
            detailBackBtn.setOnClickListener(v -> backToIndex());
            detailRunBgBtn.setText(R.string.k2go_zim_run_bg);
            detailRunBgBtn.setOnClickListener(v -> host.goHome(false));   // K2GO-382: land on Home, keep provisioning
            detailRunBgBtn.setVisibility(isLiveDetail(detailKey) ? View.VISIBLE : View.GONE);
        }
    }

    public void backToIndex() {
        showingDetail = false;
        detailKey = null;
        // commitNow (synchronous) so the fragment's onDestroyView : which nulls the service
        // listener : runs BEFORE we reclaim it. With async commit() the teardown fired later and
        // clobbered the index's listener, so a job finishing while back on the index never updated
        // the UI (spinner stuck) until the card was reopened.
        Fragment cur = host.fragmentManager().findFragmentById(R.id.k2go_sp_fraghost);
        if (cur != null) host.fragmentManager().beginTransaction().remove(cur).commitNow();
        detailRoot.setVisibility(View.GONE);
        indexScroll.setVisibility(View.VISIBLE);
        ZimDownloadService.setListener(host::render);
        BooksDownloadService.setListener(host::render);
        host.render();
    }
}
