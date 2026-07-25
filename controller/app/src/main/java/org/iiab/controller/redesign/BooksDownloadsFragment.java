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
    private boolean fromIndex;   // hosted by the Finishing-setup index: hide own buttons, only observe

    /** Open as a detail card inside the Finishing-setup index (host owns Back/Finish; observe only). */
    public static BooksDownloadsFragment newInstance(boolean fromIndex) {
        BooksDownloadsFragment f = new BooksDownloadsFragment();
        Bundle b = new Bundle();
        b.putBoolean("fromIndex", fromIndex);
        f.setArguments(b);
        return f;
    }

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_books_downloads, container, false);
        fromIndex = getArguments() != null && getArguments().getBoolean("fromIndex", false);

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

        if (fromIndex) {   // the index host provides Back/Finish; this card only observes
            finishBtn.setVisibility(View.GONE);
            runBgBtn.setVisibility(View.GONE);
        }

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

        if (!fromIndex) {
            boolean complete = BooksDownloadService.isComplete();
            finishBtn.setEnabled(complete);
            runBgBtn.setVisibility(complete ? View.GONE : View.VISIBLE);
        }
    }

    private void drawChecklist(String[] titles, int[] status) {
        ProvisioningChecklist.render(requireContext(), listv, titles.length, status,
                BooksDownloadService.DONE, BooksDownloadService.FAILED,
                new ProvisioningChecklist.RowText() {
                    @Override public String main(int i) { return titles[i]; }
                    @Override public String sub(int i) { return stateLabel(status[i]); }
                },
                i -> BooksDownloadService.retry(requireContext().getApplicationContext(), i));
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
