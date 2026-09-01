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
package org.iiab.controller.redesign;

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

import org.iiab.controller.R;
import org.iiab.controller.dashboard.domain.DashboardCardState;
import org.iiab.controller.util.AppExecutors;

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
        statusChip = chip(getString(R.string.k2go_dash_chip_checking), R.color.k2go_muted);
        chips.addView(statusChip);
        chips.addView(chip(getString(R.string.k2go_dash_chip_rest), R.color.k2go_teal));
        chips.addView(chip(getString(R.string.k2go_dash_chip_core), R.color.k2go_teal));
        fetchVersionChip();

        ((TextView) root.findViewById(R.id.k2go_moddet_includes_body)).setText(R.string.k2go_dash_includes);
        ((TextView) root.findViewById(R.id.k2go_moddet_license))
                .setText(getString(R.string.k2go_mod_license_fmt, getString(R.string.k2go_dash_license)));

        // Core service — the only action is Rebuild (no schedule/install). Reuse the primary button as
        // "Rebuild"; hide the secondary "Install now".
        rebuild = root.findViewById(R.id.k2go_moddet_schedule);
        rebuild.setText(R.string.k2go_dash_rebuild);
        rebuild.setOnClickListener(v -> DashboardRebuild.confirmAndStart(this, root));
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
                    styleChip(statusChip, getString(R.string.k2go_dash_no_connection), R.color.k2go_muted); break;
                case CHECKING:
                    styleChip(statusChip, getString(R.string.k2go_dash_chip_checking), R.color.k2go_muted); break;
                case UP_TO_DATE:
                    styleChip(statusChip, getString(R.string.k2go_dash_chip_uptodate), R.color.k2go_leaf); break;
                case UPDATE_AVAILABLE:
                    styleChip(statusChip, getString(R.string.k2go_dash_chip_update), R.color.k2go_amber); break;
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
        bar.setIndeterminate(true);
        bar.setIndicatorColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
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
        row.setVisibility(View.GONE);
        parent.addView(row, parent.indexOfChild(rebuildBtn));
        return row;
    }

    /** Ask the service to cancel; the bar stays until STATE_CANCELLED lands (or the update finishes if it
     *  was too late). Disable the affordance to avoid double taps. */
    private void onCancelUpdate() {
        // ADFA-5339: stopping a live update is worth a confirm — the swap may be seconds away, and a
        // stray tap shouldn't abandon it. Only on a positive answer do we signal the server.
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.k2go_dash_cancel_confirm_title)
                .setMessage(R.string.k2go_dash_cancel_confirm_msg)
                .setNegativeButton(R.string.k2go_dash_cancel_confirm_keep, null)
                .setPositiveButton(R.string.k2go_dash_cancel_confirm_stop, (d, w) -> signalCancel())
                .show();
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
                    versionChip = chip("v" + ver, R.color.k2go_teal);
                    chips.addView(versionChip, 0);
                } else {
                    styleChip(versionChip, "v" + ver, R.color.k2go_teal);
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

    /** Small outlined pill for the meta-chip row (matches ModuleDetailFragment). */
    private TextView chip(String text, int colorRes) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(requireContext());
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        int hp = Math.round(10 * d), vp = Math.round(5 * d);
        t.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(8 * d);
        t.setLayoutParams(lp);
        styleChip(t, text, colorRes);
        return t;
    }

    /** (Re)apply a chip's text + outlined color. ADFA-5026: split out of {@link #chip} so the live
     *  status pill can change text/color in place without rebuilding the view. */
    private void styleChip(TextView t, String text, int colorRes) {
        float d = getResources().getDisplayMetrics().density;
        int color = ContextCompat.getColor(requireContext(), colorRes);
        t.setText(text);
        t.setTextColor(color);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setCornerRadius(11 * d);
        bg.setStroke(Math.max(1, Math.round(1.4f * d)), color);
        t.setBackground(bg);
    }
}
