/*
 * ============================================================================
 * Name        : ZimPreparingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. ZIM Preparing (screen 4). Drives the real download of the selection
 *               cart through ZimDownloadService (foreground; sequential per ZIM via the REST job
 *               engine, continuing past failures). Shows a contained placeholder spinner + a REAL
 *               progress bar (weighted by bytes) + "X of N items" + a per-item checklist (round
 *               check when done, teal dot while active/indexing, amber when failed). The service
 *               is the source of truth, so this screen re-attaches to an in-flight session and
 *               "Run in background" leaves it running.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import org.json.JSONObject;

public class ZimPreparingFragment extends Fragment {

    private TextView label, pct, detail;
    private ProgressBar bar;
    private LinearLayout listv;
    private Button finishBtn, runBgBtn;

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

        label = root.findViewById(R.id.k2go_zprep_label);
        pct = root.findViewById(R.id.k2go_zprep_pct);
        detail = root.findViewById(R.id.k2go_zprep_detail);
        bar = root.findViewById(R.id.k2go_zprep_bar);
        listv = root.findViewById(R.id.k2go_zprep_list);

        runBgBtn = root.findViewById(R.id.k2go_zprep_run_bg);
        runBgBtn.setOnClickListener(v -> {
            ZimDownloadService.setListener(null);           // stop observing; server keeps going
            if (getActivity() instanceof SetupLibraryActivity) ((SetupLibraryActivity) getActivity()).backToGetMoreHubZim();
        });
        finishBtn = root.findViewById(R.id.k2go_zprep_finish);
        finishBtn.setOnClickListener(v -> {
            ZimDownloadService.finishSession();             // clear the session; free it for a new list
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).getZimCart().clear();
                ((SetupLibraryActivity) getActivity()).backToGetMoreHubZim();
            }
        });

        // Start (or re-attach to) the download session, then observe its state.
        KiwixCatalog.getOrFetch(requireContext(), new KiwixCatalog.Listener() {
            @Override public void onReady(JSONObject catalog) {
                if (!isAdded()) return;
                if (!ZimDownloadService.isRunning()) startSession(catalog);
                ZimDownloadService.setListener(ZimPreparingFragment.this::render);
                render();
            }
            @Override public void onError(String m) {
                if (!isAdded()) return;
                ZimDownloadService.setListener(ZimPreparingFragment.this::render);
                render();
            }
        });
        return root;
    }

    /** Resolve the cart to (filename, label, bytes) triples and start the foreground service. */
    private void startSession(JSONObject catalog) {
        List<String> files = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Long> bytes = new ArrayList<>();
        for (Map.Entry<String, Long> e : cart().entrySet()) {
            String[] p = e.getKey().split("\\|", 3);   // project | lang | entryKey
            if (p.length < 3) continue;
            JSONObject ld = KiwixCatalog.langData(catalog, p[0], p[1]);
            JSONObject v = ld != null ? ld.optJSONObject(p[2]) : null;
            if (v == null) continue;
            files.add(v.optString("file"));
            labels.add(itemLabel(p[0], v.optString("creator"), v.optString("flavour")));
            bytes.add(v.optLong("size", e.getValue()));
        }
        if (files.isEmpty()) return;
        long[] b = new long[bytes.size()];
        for (int i = 0; i < b.length; i++) b[i] = bytes.get(i);
        ZimDownloadService.start(requireContext().getApplicationContext(),
                files.toArray(new String[0]), labels.toArray(new String[0]), b);
    }

    private String itemLabel(String project, String creator, String flavour) {
        KiwixCategories.Category c = KiwixCategories.byKey(project);
        String cat = c != null ? c.title : project;
        if (creator == null) creator = "";
        if (flavour == null || flavour.isEmpty()) flavour = "all";
        boolean creatorIsProject = creator.equalsIgnoreCase(project)
                || creator.toLowerCase(Locale.ROOT).startsWith(project.toLowerCase(Locale.ROOT));
        String tail = "all".equals(flavour) ? creator : (creatorIsProject
                ? flavour.replace('_', ' ').replace('-', ' ')
                : creator + " · " + flavour.replace('_', ' ').replace('-', ' '));
        if ("all".equals(flavour) && creatorIsProject) tail = "All";
        return cat + " · " + tail;
    }

    private void render() {
        if (!isAdded()) return;
        String[] labels = ZimDownloadService.labels();
        long[] bytes = ZimDownloadService.bytes();
        int[] status = ZimDownloadService.status();
        int idx = ZimDownloadService.index();
        int p = ZimDownloadService.percent();
        int n = labels.length;
        if (n == 0) return;

        long totalBytes = 0, doneBytes = 0;
        int doneCount = 0;
        for (int i = 0; i < n; i++) {
            totalBytes += bytes[i];
            if (status[i] == ZimDownloadService.DONE || status[i] == ZimDownloadService.FAILED) {
                doneBytes += bytes[i];
                doneCount++;
            } else if (i == idx && (status[i] == ZimDownloadService.ACTIVE || status[i] == ZimDownloadService.INDEXING)) {
                doneBytes += bytes[i] * p / 100;
            }
        }
        int overall = totalBytes > 0 ? (int) Math.min(100, doneBytes * 100 / totalBytes)
                : (ZimDownloadService.isComplete() ? 100 : 0);
        bar.setProgress(overall);
        pct.setText(overall + "%");

        boolean allDone = ZimDownloadService.isComplete();
        label.setText(allDone
                ? getString(R.string.k2go_zim_all_ready)
                : getString(R.string.k2go_zim_downloading_fmt, idx < n ? labels[idx] : ""));
        detail.setText(getString(R.string.k2go_zim_prep_detail_fmt,
                gb(doneBytes / (1024L * 1024L)), gb(totalBytes / (1024L * 1024L)), doneCount, n));

        drawChecklist(labels, status);

        boolean complete = ZimDownloadService.isComplete();
        finishBtn.setEnabled(complete);
        runBgBtn.setVisibility(complete ? View.GONE : View.VISIBLE);
    }

    private void drawChecklist(String[] labels, int[] status) {
        listv.removeAllViews();
        for (int i = 0; i < labels.length; i++) {
            int st = status[i];
            boolean done = st == ZimDownloadService.DONE;
            boolean failed = st == ZimDownloadService.FAILED;
            boolean active = st == ZimDownloadService.ACTIVE || st == ZimDownloadService.INDEXING;

            LinearLayout r = new LinearLayout(requireContext());
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, px(6), 0, px(6));

            if (done) {
                ImageView chk = new ImageView(requireContext());
                chk.setImageResource(R.drawable.ic_check_circle);
                chk.setColorFilter(ContextCompat.getColor(requireContext(), R.color.k2go_leaf));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(16), px(16));
                clp.rightMargin = px(8);
                r.addView(chk, clp);
            } else {
                View dot = new View(requireContext());
                dot.setBackgroundResource(R.drawable.k2go_dot);
                int c = failed ? R.color.k2go_amber : (active ? R.color.k2go_teal : R.color.k2go_hairline);
                dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), c)));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(10), px(10));
                dlp.leftMargin = px(3);
                dlp.rightMargin = px(11);
                r.addView(dot, dlp);
            }

            TextView t = new TextView(requireContext());
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            t.setText(failed ? labels[i] + getString(R.string.k2go_zim_item_failed_suffix) : labels[i]);
            int tc = failed ? R.color.k2go_amber_text : (done || active ? R.color.k2go_ink : R.color.k2go_muted);
            t.setTextColor(ContextCompat.getColor(requireContext(), tc));
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (failed) {
                final int idx = i;
                TextView retry = new TextView(requireContext());
                retry.setText(R.string.k2go_zim_retry);
                retry.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                retry.setTypeface(retry.getTypeface(), android.graphics.Typeface.BOLD);
                retry.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
                retry.setPadding(px(12), px(6), px(12), px(6));
                retry.setBackgroundResource(R.drawable.k2go_getmore_bg);
                retry.setClickable(true);
                retry.setOnClickListener(v -> ZimDownloadService.retry(requireContext().getApplicationContext(), idx));
                LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                retryLp.leftMargin = px(8);
                r.addView(retry, retryLp);
            }

            listv.addView(r);
        }
    }

    private String gb(long mb) {
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        return mb + " MB";
    }

    @Override
    public void onDestroyView() {
        ZimDownloadService.setListener(null);
        super.onDestroyView();
    }
}
