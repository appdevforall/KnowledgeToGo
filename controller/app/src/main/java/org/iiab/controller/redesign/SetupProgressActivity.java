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
import android.widget.ImageView;
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

    private void render() {
        if (sections == null) return;
        sections.removeAllViews();

        // Fixed order: Wikipedia/ZIM first, then Books (Maps will slot in here later).
        if (ZimDownloadService.hasSession()) {
            sections.addView(section(getString(R.string.k2go_gm_wikipedia_title),
                    ZimDownloadService.labels(), ZimDownloadService.status(),
                    ZimDownloadService.DONE, ZimDownloadService.FAILED, true));
        }
        if (BooksDownloadService.hasSession()) {
            sections.addView(section(getString(R.string.k2go_gm_books_title),
                    BooksDownloadService.titles(), BooksDownloadService.status(),
                    BooksDownloadService.DONE, BooksDownloadService.FAILED, false));
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

    /** One content stream's card: header + a per-item checklist. {@code isZim} routes Retry. */
    private View section(String heading, String[] labels, int[] status, int doneVal, int failedVal, boolean isZim) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.k2go_card_bg);
        card.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = px(12);
        card.setLayoutParams(cardLp);

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        card.addView(h);

        if (labels == null) labels = new String[0];
        for (int i = 0; i < labels.length; i++) {
            int st = status != null && i < status.length ? status[i] : 0;
            boolean done = st == doneVal;
            boolean failed = st == failedVal;
            boolean active = !done && !failed && st != 0;   // 0 == PENDING in both services

            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, px(6), 0, px(6));

            if (done) {
                ImageView chk = new ImageView(this);
                chk.setImageResource(R.drawable.ic_check_circle);
                chk.setColorFilter(ContextCompat.getColor(this, R.color.k2go_leaf));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(px(16), px(16));
                clp.rightMargin = px(8);
                r.addView(chk, clp);
            } else {
                View dot = new View(this);
                dot.setBackgroundResource(R.drawable.k2go_dot);
                int c = failed ? R.color.k2go_amber : (active ? R.color.k2go_teal : R.color.k2go_hairline);
                dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, c)));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(10), px(10));
                dlp.leftMargin = px(3);
                dlp.rightMargin = px(11);
                r.addView(dot, dlp);
            }

            TextView t = new TextView(this);
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            t.setText(labels[i]);
            t.setMaxLines(2);
            int tc = failed ? R.color.k2go_amber_text : (done || active ? R.color.k2go_ink : R.color.k2go_muted);
            t.setTextColor(ContextCompat.getColor(this, tc));
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (failed) {
                final int idx = i;
                TextView retry = new TextView(this);
                retry.setText(R.string.k2go_zim_retry);
                retry.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
                retry.setTypeface(retry.getTypeface(), Typeface.BOLD);
                retry.setTextColor(ContextCompat.getColor(this, R.color.k2go_teal));
                retry.setPadding(px(12), px(6), px(12), px(6));
                retry.setBackgroundResource(R.drawable.k2go_getmore_bg);
                retry.setOnClickListener(v -> {
                    if (isZim) ZimDownloadService.retry(getApplicationContext(), idx);
                    else BooksDownloadService.retry(getApplicationContext(), idx);
                });
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rl.leftMargin = px(8);
                r.addView(retry, rl);
            }

            card.addView(r);
        }
        return card;
    }
}
