/*
 * ============================================================================
 * Name        : MapsChooseFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 (slice 2). Maps "Choose layers & quality" (Option B). Segmented
 *               groups constrained to the Android support matrix (roles/maps/defaults +
 *               local_vars_android_*): base map, satellite, 3D terrain, low-power search.
 *               Live total + free-space guard drive the CTA. Sizes are PLACEHOLDERS until
 *               the refreshMapsSizes build task lands maps_sizes.csv. Download hands off to
 *               Confirm (next slice). No language picker, no search box (Maps has neither).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.os.StatFs;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;

import java.util.Locale;

public class MapsChooseFragment extends Fragment {

    private static final class Opt { final int label; final long mb; Opt(int l, long m) { label = l; mb = m; } }
    private static final class Grp {
        final int label; final int hint; final Opt[] opts; final int def;
        Grp(int l, int h, Opt[] o, int d) { label = l; hint = h; opts = o; def = d; }
    }

    // Options mirror the interactive prototype (human labels -> real zoom levels wire in with the
    // backend). Sizes are placeholders pending maps_sizes.csv.
    private final Grp[] GROUPS = {
            new Grp(R.string.k2go_maps_grp_base, R.string.k2go_maps_grp_base_hint, new Opt[]{
                    new Opt(R.string.k2go_maps_lvl_low, 120), new Opt(R.string.k2go_maps_lvl_standard, 2500),
                    new Opt(R.string.k2go_maps_lvl_high, 14000) }, 1),
            new Grp(R.string.k2go_maps_grp_sat, R.string.k2go_maps_grp_sat_hint, new Opt[]{
                    new Opt(R.string.k2go_maps_lvl_off, 0), new Opt(R.string.k2go_maps_lvl_low, 600),
                    new Opt(R.string.k2go_maps_lvl_standard, 2400), new Opt(R.string.k2go_maps_lvl_high, 9000),
                    new Opt(R.string.k2go_maps_lvl_max, 40000) }, 0),
            new Grp(R.string.k2go_maps_grp_terrain, R.string.k2go_maps_grp_terrain_hint, new Opt[]{
                    new Opt(R.string.k2go_maps_lvl_off, 0), new Opt(R.string.k2go_maps_lvl_low, 500),
                    new Opt(R.string.k2go_maps_lvl_standard, 1200), new Opt(R.string.k2go_maps_lvl_high, 3000),
                    new Opt(R.string.k2go_maps_lvl_max, 7000) }, 0),
            new Grp(R.string.k2go_maps_grp_search, R.string.k2go_maps_grp_search_hint, new Opt[]{
                    new Opt(R.string.k2go_maps_lvl_off, 0), new Opt(R.string.k2go_maps_lvl_cities, 35) }, 1),
    };

    private final long[] selectedMb = new long[GROUPS.length];
    private final TextView[][] pillViews = new TextView[GROUPS.length][];
    private long freeMb = 0;
    private ProgressBar freeBar;
    private TextView freeLabel, fit;
    private Button download;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_choose, container, false);

        TextView back = root.findViewById(R.id.k2go_choose_back);
        back.setText("‹ " + getString(R.string.k2go_maps_scr_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        freeBar = root.findViewById(R.id.k2go_free_bar);
        freeLabel = root.findViewById(R.id.k2go_free_label);
        fit = root.findViewById(R.id.k2go_fit);
        download = root.findViewById(R.id.k2go_download_btn);

        try {
            freeMb = new StatFs(requireContext().getFilesDir().getPath()).getAvailableBytes() / (1024L * 1024L);
        } catch (Exception e) {
            freeMb = 0;
        }

        buildGroups(root.findViewById(R.id.k2go_maps_groups));

        download.setOnClickListener(v -> {
            long total = total();
            if (freeMb > 0 && total > freeMb) return; // guarded (button already disabled)
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openMapsConfirm(total);
            }
        });

        refresh();
        return root;
    }

    private void buildGroups(LinearLayout host) {
        for (int gi = 0; gi < GROUPS.length; gi++) {
            final Grp g = GROUPS[gi];
            selectedMb[gi] = g.opts[g.def].mb;

            TextView label = new TextView(requireContext());
            label.setText(getString(g.label) + "  " + getString(g.hint));
            label.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
            label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.topMargin = px(16);
            host.addView(label, llp);

            HorizontalScrollView hsv = new HorizontalScrollView(requireContext());
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            hsv.addView(row);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = px(8);
            host.addView(hsv, hlp);

            pillViews[gi] = new TextView[g.opts.length];
            for (int oi = 0; oi < g.opts.length; oi++) {
                final int gg = gi, oo = oi;
                TextView pill = new TextView(requireContext());
                pill.setText(getString(g.opts[oi].label));
                pill.setPadding(px(14), px(8), px(14), px(8));
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                plp.rightMargin = px(8);
                pill.setLayoutParams(plp);
                pill.setOnClickListener(v -> selectOpt(gg, oo));
                row.addView(pill);
                pillViews[gi][oi] = pill;
            }
            applyGroupSelection(gi, g.def);
        }
    }

    private void selectOpt(int gi, int oi) {
        selectedMb[gi] = GROUPS[gi].opts[oi].mb;
        applyGroupSelection(gi, oi);
        refresh();
    }

    private void applyGroupSelection(int gi, int selOi) {
        for (int oi = 0; oi < pillViews[gi].length; oi++) {
            TextView p = pillViews[gi][oi];
            boolean sel = oi == selOi;
            p.setBackgroundResource(sel ? R.drawable.k2go_chip_bg : R.drawable.k2go_pill_bg);
            p.setTextColor(ContextCompat.getColor(requireContext(),
                    sel ? android.R.color.white : R.color.k2go_ink));
        }
    }

    private long total() {
        long t = 0;
        for (long m : selectedMb) t += m;
        return t;
    }

    private void refresh() {
        long t = total();
        freeLabel.setText(getString(R.string.k2go_maps_free, fmt(freeMb)));
        int pct = freeMb > 0 ? (int) Math.min(100, Math.round(t * 100.0 / freeMb)) : 0;
        freeBar.setProgress(pct);
        boolean over = freeMb > 0 && t > freeMb;
        if (over) {
            fit.setText(getString(R.string.k2go_maps_nospace, fmt(t), fmt(freeMb)));
            fit.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_clay));
            download.setEnabled(false);
            download.setAlpha(0.5f);
        } else {
            fit.setText(getString(R.string.k2go_maps_fits, fmt(t), fmt(freeMb)));
            fit.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_leaf));
            download.setEnabled(true);
            download.setAlpha(1f);
        }
        download.setText(getString(R.string.k2go_maps_download, fmt(t)));
    }

    private String fmt(long mb) {
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        return mb + " MB";
    }
}
