/*
 * ============================================================================
 * Name        : ZimPreparingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. ZIM Preparing (screen 4). A contained placeholder spinner (independent
 *               of the boot/close Lottie) + a REAL progress bar with percent + "X of N items" +
 *               a per-category checklist. ZIM downloads have true byte progress, so a real bar is
 *               honest here (unlike Maps tile-building). Progress is MOCK until the download
 *               backend is wired. "Run in background" returns to the Get More hub.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.iiab.controller.R;

public class ZimPreparingFragment extends Fragment {

    private final List<String> catTitles = new ArrayList<>();
    private long totalMb = 0;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable tick;
    private int prog = 0;
    private TextView label, pct, detail;
    private ProgressBar bar;
    private LinearLayout listv;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Long> cart() {
        return (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getZimCart() : new LinkedHashMap<>();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_zim_preparing, container, false);

        LinkedHashMap<String, Long> byCat = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : cart().entrySet()) {
            String project = e.getKey().split("\\|", 2)[0];
            Long agg = byCat.get(project);
            byCat.put(project, (agg == null ? 0 : agg) + e.getValue());
        }
        long totalBytes = 0;
        for (Map.Entry<String, Long> e : byCat.entrySet()) {
            KiwixCategories.Category c = KiwixCategories.byKey(e.getKey());
            catTitles.add(c != null ? c.title : e.getKey());
            totalBytes += e.getValue();
        }
        totalMb = totalBytes / (1024L * 1024L);

        label = root.findViewById(R.id.k2go_zprep_label);
        pct = root.findViewById(R.id.k2go_zprep_pct);
        detail = root.findViewById(R.id.k2go_zprep_detail);
        bar = root.findViewById(R.id.k2go_zprep_bar);
        listv = root.findViewById(R.id.k2go_zprep_list);

        root.findViewById(R.id.k2go_zprep_run_bg).setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) ((SetupLibraryActivity) getActivity()).backToGetMoreHubZim();
        });

        startMock();
        return root;
    }

    private int activeIndex() {
        if (catTitles.isEmpty()) return 0;
        if (prog >= 100) return catTitles.size();
        return Math.min(catTitles.size() - 1, prog * catTitles.size() / 100);
    }

    private void startMock() {
        prog = 0;
        tick = new Runnable() {
            @Override public void run() {
                prog = Math.min(100, prog + 3);
                int active = activeIndex();
                pct.setText(prog + "%");
                bar.setProgress(prog);
                String cur = active < catTitles.size() ? catTitles.get(active)
                        : (catTitles.isEmpty() ? "" : catTitles.get(catTitles.size() - 1));
                label.setText(getString(R.string.k2go_zim_downloading_fmt, cur));
                long doneMb = totalMb * prog / 100;
                int idx = Math.min(catTitles.size(), active + 1);
                detail.setText(getString(R.string.k2go_zim_prep_detail_fmt, gb(doneMb), gb(totalMb), idx, catTitles.size()));
                drawChecklist(active);
                if (prog < 100) main.postDelayed(this, 400);
            }
        };
        main.post(tick);
    }

    private void drawChecklist(int active) {
        listv.removeAllViews();
        for (int i = 0; i < catTitles.size(); i++) {
            boolean done = i < active;
            boolean current = i == active;
            int textColor = i <= active ? R.color.k2go_ink : R.color.k2go_muted;

            LinearLayout r = new LinearLayout(requireContext());
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, px(6), 0, px(6));

            if (done) {
                // Completed item: the round check, like the "fits" banner.
                ImageView chk = new ImageView(requireContext());
                chk.setImageResource(R.drawable.ic_check_circle);
                chk.setColorFilter(ContextCompat.getColor(requireContext(), R.color.k2go_leaf));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(16), px(16));
                clp.rightMargin = px(8);
                r.addView(chk, clp);
            } else {
                View dot = new View(requireContext());
                dot.setBackgroundResource(R.drawable.k2go_dot);
                dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(),
                        current ? R.color.k2go_teal : R.color.k2go_hairline)));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(10), px(10));
                dlp.leftMargin = px(3);
                dlp.rightMargin = px(11);
                r.addView(dot, dlp);
            }

            TextView t = new TextView(requireContext());
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            t.setText(catTitles.get(i));
            t.setTextColor(ContextCompat.getColor(requireContext(), textColor));
            r.addView(t);

            listv.addView(r);
        }
    }

    private String gb(long mb) {
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        return mb + " MB";
    }

    @Override
    public void onDestroyView() {
        if (tick != null) main.removeCallbacks(tick);
        super.onDestroyView();
    }
}
