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

import android.content.Context;
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

import org.iiab.controller.R;
import org.iiab.controller.util.AppExecutors;

public class DashboardDetailFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());
    private ViewGroup chips;   // FlowLayout in XML — typed as ViewGroup so it wraps chips to 2 lines
    private TextView statusChip;   // ADFA-5026: live "Up to date / Update available" pill (restyled in place)
    private TextView versionChip;  // ADFA-5051: "v<version>" chip, updated in place after a live update
    private Button rebuild;        // de-emphasized when already on the latest
    private TextView rebuildHint;  // "no rebuild needed" note, shown only when on the latest

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
        rebuild.setOnClickListener(v -> DashboardRebuild.confirmAndStart(this, root, this::refreshAfterLiveUpdate));
        root.findViewById(R.id.k2go_moddet_install_now).setVisibility(View.GONE);
        rebuildHint = buildRebuildHint(rebuild);   // ADFA-5026: "no rebuild needed" note (hidden until on-latest)

        // ADFA-5026: resolve the live update status (falls back to the last-known cache when offline).
        fetchUpdateStatus();

        return root;
    }

    /** ADFA-5026: ask the box whether a newer build exists and reflect it in the status pill + Rebuild
     *  emphasis. Shows the cached last-known state right away (if any) so the pill isn't stuck on
     *  "Checking…", then refreshes from the live check; on failure it keeps the cached state. */
    private void fetchUpdateStatus() {
        final Context ctx = requireContext().getApplicationContext();
        if (UpdateStatusCache.has(ctx)) applyUpdateStatus(UpdateStatusCache.updateAvailable(ctx));
        DashboardClient.updateCheck(new DashboardClient.UpdateCb() {
            @Override public void onResult(String installed, String available, boolean updateAvailable) {
                if (!isAdded()) return;
                UpdateStatusCache.save(ctx, updateAvailable);
                applyUpdateStatus(updateAvailable);
            }
            @Override public void onErr(String message) {
                if (!isAdded()) return;
                if (UpdateStatusCache.has(ctx)) applyUpdateStatus(UpdateStatusCache.updateAvailable(ctx));
                else if (statusChip != null) statusChip.setVisibility(View.GONE);   // nothing to show yet
            }
        });
    }

    /** Reflect the update status: recolor the pill in place and, when already on the latest, de-emphasize
     *  Rebuild + show the "not needed" hint (never blocks — the user can still rebuild). */
    private void applyUpdateStatus(boolean updateAvailable) {
        if (statusChip != null) {
            if (updateAvailable) styleChip(statusChip, getString(R.string.k2go_dash_chip_update), R.color.k2go_amber);
            else styleChip(statusChip, getString(R.string.k2go_dash_chip_uptodate), R.color.k2go_leaf);
            statusChip.setVisibility(View.VISIBLE);
        }
        if (rebuild != null) rebuild.setAlpha(updateAvailable ? 1f : 0.6f);
        if (rebuildHint != null) rebuildHint.setVisibility(updateAvailable ? View.GONE : View.VISIBLE);
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
