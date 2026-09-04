/*
 * ============================================================================
 * Name        : DashboardDetailFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5011. Detail card for the dash-node REST core, mirroring the module detail
 *               (Play Store style): 16:9 image, title + subtitle, meta chips (live version / REST API
 *               / Runs offline / System core), a description of what the dashboard IS, a "What it
 *               includes" block and the license. The dashboard is core (not installable/removable),
 *               so the single action is "Rebuild" — routed through the shared DashboardRebuild gate.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.dashboard.domain.DashboardCardState;
import org.appdevforall.k2go.dashboard.domain.RebuildPhase;
import org.appdevforall.k2go.dashboard.domain.RebuildProgress;
import org.appdevforall.k2go.util.AppExecutors;

public class DashboardDetailFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());
    private ViewGroup chips;   // FlowLayout in XML — typed as ViewGroup so it wraps chips to 2 lines
    private TextView statusChip;   // ADFA-5026: live "Up to date / Update available" pill (restyled in place)
    private TextView versionChip;  // ADFA-5051: "v<version>" chip, updated in place after a live update
    private Button rebuild;        // de-emphasized when already on the latest
    private TextView rebuildHint;  // "no rebuild needed" note, shown only when on the latest
    private View updatingRow;      // ADFA-5333: in-progress indicator (indeterminate bar + label + Cancel)
    private View updatingCancel;   // ADFA-5333: the Cancel affordance beside the bar
    private boolean updating;      // ADFA-5333: a background rebuild is in flight; don't re-emphasize Rebuild
    private boolean updateAvailable;   // ADFA-5339: last-known — the confirm dialog matches the button
    // ADFA-5339 / K2GO-374: expandable Details — the live rebuild log via the shared LiveLogPanel. The
    // toggle stays hidden until there are lines (an older box without /rebuild/log shows no Details).
    private org.appdevforall.k2go.widget.LiveLogPanel logPanel;
    private static final long LOG_POLL_MS = 1500L;
    private final Runnable logPoll = this::pollLog;
    // K2GO-95 (Phase 2): the in-progress bar is determinate, driven by RebuildProgress from the polled
    // log. We keep the current phase and when it began (a monotonic clock — the silent native-build
    // stretch carries no log timestamp) so the bar interpolates within a phase and the next real marker
    // snaps it forward. Indeterminate only until the first marker (NONE); the snap to 100 is the
    // service's completion broadcast, not this poll.
    private LinearProgressIndicator progressBar;
    private RebuildPhase progressPhase = RebuildPhase.NONE;
    private long progressPhaseStartMs;

    /** ADFA-5333: the live update runs in the background (DashboardRebuildService), which broadcasts each
     *  state change. While this card is on screen we show/hide an in-progress bar and, on done, refresh
     *  the version/pill in place — exactly as the old in-modal completion did. Registered only while
     *  STARTED, so there is no callback captured across the multi-minute detached rebuild. */
    private final BroadcastReceiver rebuildState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!isAdded()) return;
            String state = i.getStringExtra(DashboardRebuildService.EXTRA_STATE);
            if (DashboardRebuildService.STATE_RUNNING.equals(state)) {
                setUpdating(true);
            } else if (DashboardRebuildService.STATE_DONE.equals(state)) {
                setUpdating(false);
                refreshAfterLiveUpdate();
            } else if (DashboardRebuildService.STATE_ERROR.equals(state)
                    || DashboardRebuildService.STATE_CANCELLED.equals(state)) {
                setUpdating(false);
                fetchUpdateStatus();   // version unchanged; re-resolve the pill/emphasis
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_module_detail, container, false);

        TextView back = root.findViewById(R.id.k2go_moddet_back);
        back.setText("‹ " + getString(R.string.k2go_mod_back));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        ((ImageView) root.findViewById(R.id.k2go_moddet_image)).setImageResource(R.drawable.k2go_module_placeholder);
        ((TextView) root.findViewById(R.id.k2go_moddet_title)).setText(R.string.k2go_dash_detail_title);
        ((TextView) root.findViewById(R.id.k2go_moddet_sub)).setText(R.string.k2go_dash_detail_sub);
        ((TextView) root.findViewById(R.id.k2go_moddet_desc)).setText(R.string.k2go_dash_detail_desc);

        // Meta chips, in order: version (prepended live) | update status (live) | REST API | System core.
        // ADFA-5026: the status chip starts at index 0 so that when the version chip prepends at 0 it
        // lands right after the version. "Runs offline" is replaced by the update-status pill.
        chips = root.findViewById(R.id.k2go_moddet_chips);
        statusChip = K2GoChip.create(requireContext(), getString(R.string.k2go_dash_chip_checking), R.color.k2go_muted);
        chips.addView(statusChip);
        chips.addView(K2GoChip.create(requireContext(), getString(R.string.k2go_dash_chip_rest), R.color.k2go_teal));
        chips.addView(K2GoChip.create(requireContext(), getString(R.string.k2go_dash_chip_core), R.color.k2go_teal));
        fetchVersionChip();

        ((TextView) root.findViewById(R.id.k2go_moddet_includes_body)).setText(R.string.k2go_dash_includes);
        ((TextView) root.findViewById(R.id.k2go_moddet_license))
                .setText(getString(R.string.k2go_mod_license_fmt, getString(R.string.k2go_dash_license)));

        // Core service — the only action is Rebuild (no schedule/install). Reuse the primary button as
        // "Rebuild"; hide the secondary "Install now".
        rebuild = root.findViewById(R.id.k2go_moddet_schedule);
        rebuild.setText(R.string.k2go_dash_rebuild);
        rebuild.setOnClickListener(v -> DashboardRebuild.confirmAndStart(this, root, updateAvailable));
        root.findViewById(R.id.k2go_moddet_install_now).setVisibility(View.GONE);
        rebuildHint = buildRebuildHint(rebuild);   // ADFA-5026: "no rebuild needed" note (hidden until on-latest)
        updatingRow = buildUpdatingRow(rebuild);   // ADFA-5333: in-progress bar (hidden until updating)

        // ADFA-5026: resolve the live update status (falls back to the last-known cache when offline).
        fetchUpdateStatus();

        return root;
    }

    /** ADFA-5333: while visible, listen for the background update's state changes and resolve the current
     *  state on open — so a card entered mid-update (e.g. from the notification) shows the bar. Explicit-
     *  package, not-exported — an internal signal from our own service. */
    @Override
    public void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(DashboardRebuildService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(rebuildState, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            requireContext().registerReceiver(rebuildState, f);
        }
        resolveInitialUpdatingState();
    }

    @Override
    public void onStop() {
        try { requireContext().unregisterReceiver(rebuildState); } catch (IllegalArgumentException ignore) { /* not registered */ }
        super.onStop();
    }

    /** Show the bar if an update is in flight. The live service is the fast answer; if it isn't running
     *  but the box itself still reports a rebuild (e.g. our process was killed mid-update), re-own it by
     *  starting the service again — the box replies 409 and the service re-attaches the notification and
     *  the completion broadcast. */
    private void resolveInitialUpdatingState() {
        if (DashboardRebuildService.isRunning()) { setUpdating(true); return; }
        final Context ctx = requireContext().getApplicationContext();
        DashboardClient.rebuildStatus(new DashboardClient.RebuildStatusCb() {
            @Override public void onState(String state) {
                if (!isAdded()) return;
                if (DashboardRebuildService.STATE_RUNNING.equals(state) && !DashboardRebuildService.isRunning()) {
                    setUpdating(true);
                    DashboardRebuildService.attach(ctx);   // re-own without risking a fresh rebuild
                }
            }
            @Override public void onErr(String message) { /* box stopped/offline — nothing in flight to show */ }
        });
    }

    /** ADFA-5026: ask the box whether a newer build exists and reflect it in the status pill + Rebuild
     *  emphasis. Shows the cached last-known state right away (if any) so the pill isn't stuck on
     *  "Checking…", then refreshes from the live check; on failure it keeps the cached state. */
    private void fetchUpdateStatus() {
        // ADFA-5339: the cache/live/offline orchestration lives once in DashboardCardStatus, shared
        // with the hub row; this fragment only paints what it delivers.
        DashboardCardStatus.fetch(requireContext(), s -> { if (isAdded()) applyCardState(s); });
    }

    /** ADFA-5339: paint the card from the shared {@link DashboardCardState} rule — the pill, the primary
     *  button (Update vs Rebuild), the version subtitle and the "on latest" hint. The rule lives in the
     *  domain so this and the hub row can't drift; this method only renders it. Never blocks — the user
     *  can still Rebuild manually. */
    private void applyCardState(DashboardCardState s) {
        if (statusChip != null) {
            switch (s.kind()) {
                case OFFLINE:
                    K2GoChip.style(statusChip, getString(R.string.k2go_dash_no_connection), R.color.k2go_muted); break;
                case CHECKING:
                    K2GoChip.style(statusChip, getString(R.string.k2go_dash_chip_checking), R.color.k2go_muted); break;
                case UP_TO_DATE:
                    K2GoChip.style(statusChip, getString(R.string.k2go_dash_chip_uptodate), R.color.k2go_leaf); break;
                case UPDATE_AVAILABLE:
                    K2GoChip.style(statusChip, getString(R.string.k2go_dash_chip_update), R.color.k2go_amber); break;
            }
            statusChip.setVisibility(View.VISIBLE);
        }
        // Fork B (ADFA-5339): the version target rides in the subtitle, not the button. Only a LIVE
        // update-available carries both versions; every other state restores the descriptive subtitle.
        View root = getView();
        TextView sub = root != null ? (TextView) root.findViewById(R.id.k2go_moddet_sub) : null;
        if (sub != null) {
            if (s.showsVersionArrow()) {
                sub.setText(getString(R.string.k2go_dash_card_sub_update_fmt,
                        s.installedVersion(), s.targetVersion()));
            } else {
                sub.setText(R.string.k2go_dash_detail_sub);
            }
        }
        updateAvailable = s.primaryIsUpdate();   // ADFA-5339: so the confirm dialog matches the button
        if (updating) return;   // ADFA-5333: a rebuild is in flight — leave the button/bar as they are
        if (rebuild != null) {
            if (s.primaryIsUpdate()) {
                rebuild.setText(R.string.k2go_dash_update);
                rebuild.setAlpha(1f);
            } else {
                rebuild.setText(R.string.k2go_dash_rebuild);
                // De-emphasize only when we KNOW we're on the latest; offline/checking stay full.
                rebuild.setAlpha(s.kind() == DashboardCardState.Kind.UP_TO_DATE ? 0.6f : 1f);
            }
        }
        if (rebuildHint != null) {
            rebuildHint.setVisibility(
                    s.kind() == DashboardCardState.Kind.UP_TO_DATE ? View.VISIBLE : View.GONE);
        }
    }

    /** Insert a small muted note directly above the Rebuild button (hidden until we know we're on the
     *  latest). Built in code so the shared module-detail layout is untouched. */
    private TextView buildRebuildHint(Button rebuildBtn) {
        ViewGroup parent = (ViewGroup) rebuildBtn.getParent();
        if (parent == null) return null;
        float d = getResources().getDisplayMetrics().density;
        TextView hint = new TextView(requireContext());
        hint.setText(R.string.k2go_dash_uptodate_hint);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        // Align with the body text + Rebuild button (both inset 20dp); left-aligned like the rest.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = Math.round(20 * d);
        lp.leftMargin = side;
        lp.rightMargin = side;
        lp.topMargin = Math.round(8 * d);
        hint.setLayoutParams(lp);
        hint.setVisibility(View.GONE);
        parent.addView(hint, parent.indexOfChild(rebuildBtn));
        return hint;
    }

    /** ADFA-5333: an in-progress indicator inserted just above Rebuild — a label, then an M3 indeterminate
     *  bar with a Cancel affordance beside it — shown only while a background update runs (the rebuild
     *  reports no percentage, so the bar is indeterminate). Built in code so the shared module-detail
     *  layout is untouched. */
    private View buildUpdatingRow(Button rebuildBtn) {
        ViewGroup parent = (ViewGroup) rebuildBtn.getParent();
        if (parent == null) return null;
        float d = getResources().getDisplayMetrics().density;
        int side = Math.round(20 * d);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = side;
        rlp.rightMargin = side;
        rlp.topMargin = Math.round(8 * d);
        row.setLayoutParams(rlp);

        TextView label = new TextView(requireContext());
        label.setText(R.string.k2go_dash_live_running);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        label.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        row.addView(label);

        // The bar and Cancel sit on one line: bar takes the width, Cancel is right beside it.
        LinearLayout line = new LinearLayout(requireContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Math.round(4 * d);
        line.setLayoutParams(llp);

        LinearProgressIndicator bar = new LinearProgressIndicator(requireContext());
        bar.setIndeterminate(true);   // until the first log marker; then RebuildProgress drives it (K2GO-95)
        bar.setIndicatorColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        progressBar = bar;
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);   // weight 1 → takes the remaining width
        bar.setLayoutParams(blp);
        line.addView(bar);

        TextView cancel = new TextView(requireContext());
        cancel.setText(R.string.k2go_dash_cancel);
        cancel.setAllCaps(true);
        cancel.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        cancel.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        int hp = Math.round(12 * d), vp = Math.round(6 * d);
        cancel.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Math.round(8 * d);
        cancel.setLayoutParams(clp);
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true);
        cancel.setBackgroundResource(tv.resourceId);
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> onCancelUpdate());
        line.addView(cancel);
        updatingCancel = cancel;

        row.addView(line);

        // K2GO-374: Details — the shared LiveLogPanel (was a hand-rolled toggle + ScrollView). Fork B:
        // the toggle stays hidden until there are log lines (an older box without /rebuild/log = empty).
        logPanel = new org.appdevforall.k2go.widget.LiveLogPanel(requireContext());
        logPanel.setHideUntilContent(true);
        row.addView(logPanel);

        row.setVisibility(View.GONE);
        parent.addView(row, parent.indexOfChild(rebuildBtn));
        return row;
    }

    /** Ask the service to cancel; the bar stays until STATE_CANCELLED lands (or the update finishes if it
     *  was too late). Disable the affordance to avoid double taps. */
    private void onCancelUpdate() {
        // ADFA-5339: stopping a live update is worth a confirm — the swap may be seconds away, and a
        // stray tap shouldn't abandon it. Only on a positive answer do we signal the server.
        DashboardCancelDialog.show(requireContext(), this::signalCancel);
    }

    private void signalCancel() {
        if (updatingCancel != null) updatingCancel.setEnabled(false);
        Context ctx = requireContext();
        ContextCompat.startForegroundService(ctx,
                new android.content.Intent(ctx, DashboardRebuildService.class).setAction(DashboardRebuildService.ACTION_CANCEL));
    }

    /** Toggle the in-progress state: show/hide the bar and disable Rebuild so it can't be re-triggered
     *  mid-update. On exit, the caller re-resolves the pill/emphasis via {@link #fetchUpdateStatus}. */
    private void setUpdating(boolean on) {
        updating = on;
        if (updatingRow != null) updatingRow.setVisibility(on ? View.VISIBLE : View.GONE);
        if (updatingCancel != null) updatingCancel.setEnabled(on);   // re-enable when a new update shows
        if (rebuild != null) { rebuild.setEnabled(!on); rebuild.setAlpha(on ? 0.5f : 1f); }
        if (on && rebuildHint != null) rebuildHint.setVisibility(View.GONE);
        // ADFA-5339: the Details log only exists while a rebuild runs. Poll it on, tear it down on off.
        main.removeCallbacks(logPoll);
        if (on) {
            // K2GO-95: a fresh run starts indeterminate until the first marker; pollLog then drives it.
            progressPhase = RebuildPhase.NONE;
            if (progressBar != null) progressBar.setIndeterminate(true);
            if (logPanel != null) logPanel.reset();   // K2GO-374: start clean; toggle hidden until lines
            main.post(logPoll);
        }
        // On "off" the whole updatingRow is hidden above, which takes the panel with it.
    }

    /** ADFA-5339: poll the rebuild log tail while updating. Fork B — the toggle appears only once there
     *  are lines, so an older box without the endpoint (empty) shows no Details affordance. Reschedules
     *  itself while {@code updating}; setUpdating(false) and onDestroyView remove the callback. */
    private void pollLog() {
        if (!isAdded() || !updating) return;
        DashboardClient.rebuildLog(new DashboardClient.RebuildLogCb() {
            @Override public void onLines(java.util.List<String> lines) {
                if (!isAdded() || !updating) return;
                String log = android.text.TextUtils.join("\n", lines);
                updateProgressBar(log);
                if (logPanel != null) logPanel.setContent(log);   // K2GO-374: reveal + auto-scroll handled here
                main.postDelayed(logPoll, LOG_POLL_MS);
            }
            @Override public void onErr(String message) {
                // No endpoint / transient: keep the panel as-is (toggle hidden if never populated) and retry.
                if (isAdded() && updating) main.postDelayed(logPoll, LOG_POLL_MS);
            }
        });
    }

    /** K2GO-95 (Phase 2): drive the determinate bar from the polled log. The phase comes from the log's
     *  markers ({@link RebuildProgress#phaseOf}); time within a phase comes from the monotonic clock kept
     *  here, so the silent native-build stretch (no log timestamp) still advances. Indeterminate until the
     *  first marker; the final snap to 100 is the service's completion broadcast, not this poll. */
    private void updateProgressBar(String log) {
        if (progressBar == null) return;
        RebuildPhase phase = RebuildProgress.phaseOf(log);
        if (phase != progressPhase) {
            progressPhase = phase;
            progressPhaseStartMs = android.os.SystemClock.elapsedRealtime();
        }
        if (phase == RebuildPhase.NONE) {
            if (!progressBar.isIndeterminate()) progressBar.setIndeterminate(true);
            return;
        }
        long elapsed = android.os.SystemClock.elapsedRealtime() - progressPhaseStartMs;
        int pct = RebuildProgress.percentFor(phase, elapsed);
        if (progressBar.isIndeterminate()) progressBar.setIndeterminate(false);
        progressBar.setProgressCompat(pct, true);   // animated determinate step
    }

    @Override public void onDestroyView() {
        main.removeCallbacks(logPoll);   // ADFA-5339: never poll past the view's life
        super.onDestroyView();
    }

    /** Read the installed version from the rootfs package.json on disk (authoritative, always present;
     *  no network/proot) and show a "v<version>" chip. ADFA-5051: reuses the same chip on refresh so a
     *  live update updates it in place instead of prepending a duplicate. */
    private void fetchVersionChip() {
        final Context ctx = requireContext().getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            final String ver = DashboardVersion.installed(ctx);
            main.post(() -> {
                if (!isAdded() || chips == null || ver == null) return;
                if (versionChip == null) {
                    versionChip = K2GoChip.create(requireContext(), "v" + ver, R.color.k2go_teal);
                    chips.addView(versionChip, 0);
                } else {
                    K2GoChip.style(versionChip, "v" + ver, R.color.k2go_teal);
                }
            });
        });
    }

    /** ADFA-5051: after a successful live (REST) update, refresh the version chip + update pill in
     *  place so the card reflects the new version immediately (no need to leave and re-open). */
    private void refreshAfterLiveUpdate() {
        fetchVersionChip();
        fetchUpdateStatus();
    }

}
