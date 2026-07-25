/*
 * ============================================================================
 * Name        : SetupProgressActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. "Finishing setup" — the visible post-install provisioning screen. Shows
 *               one section per content stream actually being provisioned, in a fixed order
 *               (Wikipedia/ZIM, then Books; Maps later), each with a per-item checklist driven by
 *               the live download services (ZimDownloadService / BooksDownloadService). A section
 *               only appears if its service has a session, so the user sees exactly what they picked
 *               being added. Finish clears the sessions; Run in background leaves them going (each
 *               service also shows its own notification).
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;

public class SetupProgressActivity extends AppCompatActivity {

    private LinearLayout sections;
    private Button finishBtn, runBgBtn;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(20), px(24), px(20), px(24));
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.k2go_paper));
        sv.addView(root);
        setContentView(sv);

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

        finishBtn = new Button(this);
        finishBtn.setText(R.string.k2go_zim_finish);
        finishBtn.setAllCaps(false);
        finishBtn.setTextColor(0xFFFFFFFF);
        finishBtn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.k2go_teal)));
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52));
        fLp.topMargin = px(20);
        finishBtn.setLayoutParams(fLp);
        finishBtn.setOnClickListener(v -> {
            ZimDownloadService.finishSession();
            BooksDownloadService.finishSession();
            finish();
        });
        root.addView(finishBtn);

        runBgBtn = new Button(this);
        runBgBtn.setText(R.string.k2go_zim_run_bg);
        runBgBtn.setAllCaps(false);
        runBgBtn.setTextColor(ContextCompat.getColor(this, R.color.k2go_teal));
        runBgBtn.setBackgroundResource(R.drawable.k2go_getmore_bg);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52));
        rLp.topMargin = px(10);
        runBgBtn.setLayoutParams(rLp);
        runBgBtn.setOnClickListener(v -> finish());   // leave the services running
        root.addView(runBgBtn);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ZimDownloadService.setListener(this::render);
        BooksDownloadService.setListener(this::render);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ZimDownloadService.setListener(null);
        BooksDownloadService.setListener(null);
    }

    private String detailStream = null;   // null = index; "zim" / "books" = a stream's detail

    private void render() {
        if (sections == null) return;
        sections.removeAllViews();
        if (detailStream == null) buildIndex();
        else buildDetail(detailStream);
        boolean complete = allComplete();
        finishBtn.setEnabled(complete);
        runBgBtn.setVisibility(complete ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        if (detailStream != null) { detailStream = null; render(); }
        else super.onBackPressed();
    }

    private boolean allComplete() {
        boolean zimActive = ZimDownloadService.hasSession() && !ZimDownloadService.isComplete();
        boolean booksActive = BooksDownloadService.hasSession() && !BooksDownloadService.isComplete();
        return !zimActive && !booksActive;
    }

    // ---- Index: one light summary row per active stream; tapping opens its detail ----
    private void buildIndex() {
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

        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextColor(ContextCompat.getColor(this, R.color.k2go_muted));
        chev.setTextSize(22);
        row.addView(chev);

        row.setOnClickListener(v -> { detailStream = key; render(); });
        return row;
    }

    // ---- Detail: the tapped stream's checklist, drawn by the shared ProvisioningChecklist ----
    private void buildDetail(String key) {
        final boolean isZim = "zim".equals(key);
        String[] raw = isZim ? ZimDownloadService.labels() : BooksDownloadService.titles();
        final String[] labels = raw != null ? raw : new String[0];
        int[] status = isZim ? ZimDownloadService.status() : BooksDownloadService.status();
        int doneVal = isZim ? ZimDownloadService.DONE : BooksDownloadService.DONE;
        int failedVal = isZim ? ZimDownloadService.FAILED : BooksDownloadService.FAILED;

        TextView back = new TextView(this);
        back.setText("‹ " + getString(R.string.k2go_setup_progress_title));
        back.setTextColor(ContextCompat.getColor(this, R.color.k2go_teal));
        back.setPadding(0, px(4), 0, px(10));
        back.setOnClickListener(v -> { detailStream = null; render(); });
        sections.addView(back);

        TextView h = new TextView(this);
        h.setText(getString(isZim ? R.string.k2go_gm_wikipedia_title : R.string.k2go_gm_books_title));
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        sections.addView(h);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = px(6);
        sections.addView(list, llp);

        ProvisioningChecklist.render(this, list, labels.length, status, doneVal, failedVal,
                i -> labels[i],
                i -> { if (isZim) ZimDownloadService.retry(getApplicationContext(), i);
                       else BooksDownloadService.retry(getApplicationContext(), i); });
    }
}
