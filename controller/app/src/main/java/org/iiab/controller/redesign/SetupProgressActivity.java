/*
 * ============================================================================
 * Name        : SetupProgressActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. "Finishing setup" — the post-install provisioning screen. An INDEX with
 *               one summary row per content stream being provisioned (Wikipedia/ZIM, Books; Maps
 *               later), in a fixed order; each row shows a rolled-up state and a chevron. Tapping a
 *               row opens that stream's REAL detail card (ZimPreparingFragment / BooksDownloadsFragment
 *               in "from index" mode) hosted right here, so the user sees the actual live progress —
 *               not a re-drawn copy. In a detail, the primary action is Back (to the index); Finish
 *               (secondary, with confirmation) leaves the whole setup. The index-level Finish is the
 *               single exit; while running the user can leave via Run in background.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;

public class SetupProgressActivity extends AppCompatActivity {

    private ScrollView indexScroll;
    private LinearLayout sections;
    private Button finishBtn, runBgBtn;
    private View detailRoot;
    private int fragHostId;
    private boolean showingDetail = false;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        fragHostId = View.generateViewId();

        FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(ContextCompat.getColor(this, R.color.k2go_paper));

        // ---- INDEX ----
        indexScroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(20), px(24), px(20), px(24));
        indexScroll.addView(root);

        TextView title = new TextView(this);
        title.setText(R.string.k2go_setup_progress_title);
        title.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.k2go_setup_progress_sub);
        sub.setTextColor(ContextCompat.getColor(this, R.color.k2go_muted));
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = px(4);
        root.addView(sub, subLp);

        sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        secLp.topMargin = px(16);
        root.addView(sections, secLp);

        finishBtn = primaryButton(getString(R.string.k2go_zim_finish));
        finishBtn.setOnClickListener(v -> confirmFinish());
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52));
        fLp.topMargin = px(20);
        root.addView(finishBtn, fLp);

        runBgBtn = secondaryButton(getString(R.string.k2go_zim_run_bg));
        runBgBtn.setOnClickListener(v -> finish());   // leave the services running
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52));
        rLp.topMargin = px(10);
        root.addView(runBgBtn, rLp);

        // ---- DETAIL (hosts the real per-module card) ----
        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(View.GONE);

        FrameLayout fragHost = new FrameLayout(this);
        fragHost.setId(fragHostId);
        detail.addView(fragHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(px(20), px(10), px(20), px(16));
        Button back = primaryButton(getString(R.string.k2go_setup_back));
        back.setOnClickListener(v -> backToIndex());
        bar.addView(back, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52)));
        Button term = secondaryButton(getString(R.string.k2go_zim_finish));
        LinearLayout.LayoutParams termLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52));
        termLp.topMargin = px(10);
        term.setOnClickListener(v -> confirmFinish());
        bar.addView(term, termLp);
        detail.addView(bar);

        detailRoot = detail;

        rootFrame.addView(indexScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rootFrame.addView(detailRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(rootFrame);
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.k2go_teal)));
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(ContextCompat.getColor(this, R.color.k2go_teal));
        b.setBackgroundResource(R.drawable.k2go_getmore_bg);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!showingDetail) {
            ZimDownloadService.setListener(this::render);
            BooksDownloadService.setListener(this::render);
            render();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
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

    // ---- Index ----
    private void render() {
        if (sections == null || showingDetail) return;
        sections.removeAllViews();
        if (ZimDownloadService.hasSession()) {
            sections.addView(indexRow(getString(R.string.k2go_gm_wikipedia_title), "zim",
                    ZimDownloadService.status(), ZimDownloadService.DONE, ZimDownloadService.FAILED,
                    ZimDownloadService.isComplete()));
        }
        if (BooksDownloadService.hasSession()) {
            sections.addView(indexRow(getString(R.string.k2go_gm_books_title), "books",
                    BooksDownloadService.status(), BooksDownloadService.DONE, BooksDownloadService.FAILED,
                    BooksDownloadService.isComplete()));
        }
        boolean complete = allComplete();
        finishBtn.setEnabled(complete);
        runBgBtn.setVisibility(complete ? View.GONE : View.VISIBLE);
    }

    private boolean allComplete() {
        boolean zimActive = ZimDownloadService.hasSession() && !ZimDownloadService.isComplete();
        boolean booksActive = BooksDownloadService.hasSession() && !BooksDownloadService.isComplete();
        return !zimActive && !booksActive;
    }

    private View indexRow(String heading, String key, int[] status, int doneVal, int failedVal, boolean complete) {
        int n = status != null ? status.length : 0, done = 0, failed = 0;
        if (status != null) for (int st : status) { if (st == doneVal) done++; else if (st == failedVal) failed++; }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this);
        h.setText(heading);
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(h);
        TextView sub = new TextView(this);
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        String state;
        if (!complete) state = getString(R.string.k2go_setup_state_progress_fmt, done, n);
        else if (failed > 0) state = getString(R.string.k2go_setup_state_failed_fmt, failed);
        else state = getString(R.string.k2go_setup_state_done);
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, failed > 0 ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));

        row.setOnClickListener(v -> openDetail(key));
        return row;
    }

    // ---- Detail: host the real per-module card ----
    private void openDetail(String key) {
        showingDetail = true;
        androidx.fragment.app.Fragment f = "zim".equals(key)
                ? ZimPreparingFragment.newInstance(true) : BooksDownloadsFragment.newInstance(true);
        getSupportFragmentManager().beginTransaction().replace(fragHostId, f).commit();
        indexScroll.setVisibility(View.GONE);
        detailRoot.setVisibility(View.VISIBLE);
    }

    private void backToIndex() {
        showingDetail = false;
        androidx.fragment.app.Fragment cur = getSupportFragmentManager().findFragmentById(fragHostId);
        if (cur != null) getSupportFragmentManager().beginTransaction().remove(cur).commit();
        detailRoot.setVisibility(View.GONE);
        indexScroll.setVisibility(View.VISIBLE);
        // The detail card took over the service listener; reclaim it for the index.
        ZimDownloadService.setListener(this::render);
        BooksDownloadService.setListener(this::render);
        render();
    }

    private void confirmFinish() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.k2go_setup_finish_q)
                .setMessage(R.string.k2go_setup_finish_msg)
                .setPositiveButton(R.string.k2go_zim_finish, (d, w) -> {
                    ZimDownloadService.finishSession();
                    BooksDownloadService.finishSession();
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
