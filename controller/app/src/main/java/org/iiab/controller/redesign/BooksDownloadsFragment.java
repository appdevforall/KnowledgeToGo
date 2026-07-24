/*
 * ============================================================================
 * Name        : BooksDownloadsFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4850. Books download manager screen (sibling of ZimPreparingFragment).
 *               Observes BooksDownloadService — which adds books ONE AT A TIME (kind to Project
 *               Gutenberg) and continues past failures — and shows a per-book checklist
 *               (round check when done, teal dot while downloading/adding, amber + Retry when
 *               failed, gray when queued) plus "X of N books". The service is the source of truth,
 *               so this screen re-attaches to an in-flight session; "Run in background" leaves it
 *               running and "Finish" clears the session. Reading happens on the home "Read a Book"
 *               card, never here.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
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

public class BooksDownloadsFragment extends Fragment {

    private TextView detail;
    private LinearLayout listv;
    private Button finishBtn, runBgBtn;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_books_downloads, container, false);

        detail = root.findViewById(R.id.k2go_bdl_detail);
        listv = root.findViewById(R.id.k2go_bdl_list);

        runBgBtn = root.findViewById(R.id.k2go_bdl_run_bg);
        runBgBtn.setOnClickListener(v -> {
            BooksDownloadService.setListener(null);   // stop observing; the server keeps going
            requireActivity().getSupportFragmentManager().popBackStack();
        });
        finishBtn = root.findViewById(R.id.k2go_bdl_finish);
        finishBtn.setOnClickListener(v -> {
            BooksDownloadService.finishSession();      // clear the session; free it for a new list
            requireActivity().getSupportFragmentManager().popBackStack();
        });

        BooksDownloadService.setListener(this::render);
        render();
        return root;
    }

    private void render() {
        if (!isAdded()) return;
        String[] titles = BooksDownloadService.titles();
        int[] status = BooksDownloadService.status();
        int n = titles.length;
        if (n == 0) { requireActivity().getSupportFragmentManager().popBackStack(); return; }

        int done = 0;
        for (int st : status) if (st == BooksDownloadService.DONE) done++;
        detail.setText(getString(R.string.k2go_books_dl_detail_fmt, done, n));

        drawChecklist(titles, status);

        boolean complete = BooksDownloadService.isComplete();
        finishBtn.setEnabled(complete);
        runBgBtn.setVisibility(complete ? View.GONE : View.VISIBLE);
    }

    private void drawChecklist(String[] titles, int[] status) {
        listv.removeAllViews();
        for (int i = 0; i < titles.length; i++) {
            int st = status[i];
            boolean done = st == BooksDownloadService.DONE;
            boolean failed = st == BooksDownloadService.FAILED;
            boolean active = st == BooksDownloadService.ACTIVE || st == BooksDownloadService.ADDING;

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

            LinearLayout col = new LinearLayout(requireContext());
            col.setOrientation(LinearLayout.VERTICAL);

            TextView t = new TextView(requireContext());
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            t.setText(titles[i]);
            t.setMaxLines(2);
            int tc = failed ? R.color.k2go_amber_text : (done || active ? R.color.k2go_ink : R.color.k2go_muted);
            t.setTextColor(ContextCompat.getColor(requireContext(), tc));
            col.addView(t);

            TextView state = new TextView(requireContext());
            state.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            state.setText(stateLabel(st));
            state.setTextColor(ContextCompat.getColor(requireContext(),
                    failed ? R.color.k2go_amber_text : R.color.k2go_muted));
            col.addView(state);

            r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (failed) {
                final int idx = i;
                TextView retry = new TextView(requireContext());
                retry.setText(R.string.k2go_zim_retry);
                retry.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                retry.setTypeface(retry.getTypeface(), Typeface.BOLD);
                retry.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
                retry.setPadding(px(12), px(6), px(12), px(6));
                retry.setBackgroundResource(R.drawable.k2go_getmore_bg);
                retry.setClickable(true);
                retry.setOnClickListener(v -> BooksDownloadService.retry(requireContext().getApplicationContext(), idx));
                LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                retryLp.leftMargin = px(8);
                r.addView(retry, retryLp);
            }

            listv.addView(r);
        }
    }

    private String stateLabel(int st) {
        switch (st) {
            case BooksDownloadService.ACTIVE: return getString(R.string.k2go_books_state_downloading);
            case BooksDownloadService.ADDING: return getString(R.string.k2go_books_state_adding);
            case BooksDownloadService.DONE:   return getString(R.string.k2go_books_state_done);
            case BooksDownloadService.FAILED: return getString(R.string.k2go_books_state_failed);
            default:                          return getString(R.string.k2go_books_state_queued);
        }
    }

    @Override
    public void onDestroyView() {
        BooksDownloadService.setListener(null);
        super.onDestroyView();
    }
}
