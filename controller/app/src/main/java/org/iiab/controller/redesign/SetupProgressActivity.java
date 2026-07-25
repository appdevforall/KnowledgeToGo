/*
 * ============================================================================
 * Name        : SetupProgressActivity.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4853. "Finishing setup" — the visible post-install provisioning screen.
 *               A status dot (like Library's) shows "Starting services…" until the REST engine is
 *               up; only then does the drain start (honest: no spinner spins before the system can
 *               download). One row per stream (Wikipedia/ZIM, Books) shown from the start: waiting
 *               dot → active spinner → green check (done) / amber alert (failed). Tapping a row
 *               opens that stream's REAL detail card. When everything succeeds it auto-redirects to
 *               the library after a short countdown (Cancel keeps you here and reveals Finish); on
 *               failure it shows Finish plus a note pointing to the (future) background jobs monitor.
 *               Leaving via Run in background lets the Library keep the provisioning going.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;
import org.iiab.controller.config.BoxEndpoints;
import org.iiab.controller.util.AppExecutors;

import java.net.HttpURLConnection;
import java.net.URL;

public class SetupProgressActivity extends AppCompatActivity {

    private static final long READY_POLL_MS = 2000L;
    private static final long REDIRECT_MS = 3000L;

    private View dot;
    private TextView statusText, redirect, cancel, finishNote;
    private LinearLayout sections;
    private Button finishBtn, runBgBtn;
    private View detailRoot, indexScroll;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean servicesReady = false;
    private boolean drained = false;
    private boolean redirectCancelled = false;
    private boolean redirectScheduled = false;
    private boolean showingDetail = false;
    private boolean probing = false;

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_k2go_setup_progress);

        dot = findViewById(R.id.k2go_sp_dot);
        statusText = findViewById(R.id.k2go_sp_status);
        sections = findViewById(R.id.k2go_sp_sections);
        redirect = findViewById(R.id.k2go_sp_redirect);
        cancel = findViewById(R.id.k2go_sp_cancel);
        finishBtn = findViewById(R.id.k2go_sp_finish);
        finishNote = findViewById(R.id.k2go_sp_finish_note);
        runBgBtn = findViewById(R.id.k2go_sp_runbg);
        indexScroll = findViewById(R.id.k2go_sp_index);
        detailRoot = findViewById(R.id.k2go_sp_detail);

        finishBtn.setOnClickListener(v -> goHome(true));
        runBgBtn.setOnClickListener(v -> finish());   // leave; the Library keeps provisioning going
        cancel.setOnClickListener(v -> { redirectCancelled = true; cancelRedirect(); render(); });

        Button back = findViewById(R.id.k2go_sp_back);
        back.setOnClickListener(v -> backToIndex());
        Button detailRunBg = findViewById(R.id.k2go_sp_detail_finish);
        detailRunBg.setText(R.string.k2go_zim_run_bg);   // in a detail, secondary = leave (never abort)
        detailRunBg.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!showingDetail) {
            ZimDownloadService.setListener(this::render);
            BooksDownloadService.setListener(this::render);
            main.post(readyPoll);
            render();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(readyPoll);
        cancelRedirect();
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

    // ---- readiness gate: wait for the REST engine, then drain the wishlists ----
    private final Runnable readyPoll = new Runnable() {
        @Override public void run() {
            if (probing || servicesReady) return;
            probing = true;
            AppExecutors.get().io().execute(() -> {
                final boolean ready = apiReady();
                main.post(() -> {
                    probing = false;
                    if (isFinishing()) return;
                    if (ready) {
                        servicesReady = true;
                        if (!drained) {
                            if (BooksProvisioner.hasPending(SetupProgressActivity.this)) BooksProvisioner.drain(SetupProgressActivity.this);
                            if (ZimProvisioner.hasPending(SetupProgressActivity.this)) ZimProvisioner.drain(SetupProgressActivity.this);
                            drained = true;
                        }
                        render();
                    } else {
                        render();
                        main.postDelayed(readyPoll, READY_POLL_MS);
                    }
                });
            });
        }
    };

    private static boolean apiReady() {
        HttpURLConnection c = null;
        try {
            URL u = new URL(BoxEndpoints.BASE + "/api/books/library");
            c = (HttpURLConnection) u.openConnection();
            c.setUseCaches(false);
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;   // 502/503 => engine not up yet
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---- render ----
    private void render() {
        if (sections == null || showingDetail) return;

        boolean zimShown = ZimDownloadService.hasSession() || ZimWishlist.size(this) > 0;
        boolean booksShown = BooksDownloadService.hasSession() || BooksWishlist.size(this) > 0;

        sections.removeAllViews();
        if (zimShown) sections.addView(streamRow(getString(R.string.k2go_gm_wikipedia_title), "zim",
                ZimDownloadService.hasSession(), ZimDownloadService.status(),
                ZimDownloadService.DONE, ZimDownloadService.FAILED,
                ZimDownloadService.hasSession() && ZimDownloadService.isComplete(), ZimWishlist.size(this)));
        if (booksShown) sections.addView(streamRow(getString(R.string.k2go_gm_books_title), "books",
                BooksDownloadService.hasSession(), BooksDownloadService.status(),
                BooksDownloadService.DONE, BooksDownloadService.FAILED,
                BooksDownloadService.hasSession() && BooksDownloadService.isComplete(), BooksWishlist.size(this)));

        // Overall state.
        boolean allComplete = drained
                && (!ZimDownloadService.hasSession() || ZimDownloadService.isComplete())
                && (!BooksDownloadService.hasSession() || BooksDownloadService.isComplete());
        int failedTotal = failedCount(ZimDownloadService.hasSession() ? ZimDownloadService.status() : null, ZimDownloadService.FAILED)
                + failedCount(BooksDownloadService.hasSession() ? BooksDownloadService.status() : null, BooksDownloadService.FAILED);

        // Status dot + line.
        tint(dot, servicesReady ? R.color.k2go_leaf : R.color.k2go_amber);
        statusText.setText(servicesReady ? R.string.k2go_setup_progress_sub : R.string.k2go_setup_starting);

        // Bottom controls.
        boolean success = allComplete && failedTotal == 0;
        boolean failure = allComplete && failedTotal > 0;
        if (success && !redirectCancelled) {
            show(redirect, true); show(cancel, true);
            show(finishBtn, false); show(finishNote, false); show(runBgBtn, false);
            scheduleRedirect();
        } else if (success) {   // cancelled by the user
            cancelRedirect();
            show(finishBtn, true); show(runBgBtn, false);
            show(redirect, false); show(cancel, false); show(finishNote, false);
        } else if (failure) {
            cancelRedirect();
            show(finishBtn, true); show(finishNote, true); show(runBgBtn, false);
            show(redirect, false); show(cancel, false);
        } else {   // starting or running
            cancelRedirect();
            show(runBgBtn, true);
            show(finishBtn, false); show(finishNote, false); show(redirect, false); show(cancel, false);
        }
    }

    private static int failedCount(int[] status, int failedVal) {
        if (status == null) return 0;
        int n = 0; for (int st : status) if (st == failedVal) n++; return n;
    }

    private void show(View v, boolean vis) { v.setVisibility(vis ? View.VISIBLE : View.GONE); }
    private void tint(View v, int colorRes) {
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
    }

    /** A stream summary row: waiting dot / spinner / check / alert, title, "X of N", chevron. */
    private View streamRow(String heading, String key, boolean sess, int[] status, int doneVal, int failedVal,
                           boolean complete, int wishlistCount) {
        int n = sess && status != null ? status.length : wishlistCount;
        int done = 0, failed = 0;
        if (sess && status != null) for (int st : status) { if (st == doneVal) done++; else if (st == failedVal) failed++; }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(px(16), px(14), px(16), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px(12);
        row.setLayoutParams(lp);

        // Indicator (fixed 24dp slot for alignment).
        LinearLayout slot = new LinearLayout(this);
        slot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(px(24), px(24));
        slotLp.rightMargin = px(10);
        slot.addView(indicator(sess, complete, failed));
        row.addView(slot, slotLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView h = new TextView(this);
        h.setText(heading);
        h.setTypeface(h.getTypeface(), android.graphics.Typeface.BOLD);
        h.setTextColor(ContextCompat.getColor(this, R.color.k2go_ink));
        h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        col.addView(h);
        TextView sub = new TextView(this);
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        String state;
        if (!sess) state = getString(R.string.k2go_setup_state_queued);
        else if (complete && failed > 0) state = getString(R.string.k2go_setup_state_failed_fmt, failed);
        else if (complete) state = getString(R.string.k2go_setup_state_done);
        else state = getString(R.string.k2go_setup_state_progress_fmt, done, n);
        sub.setText(state);
        sub.setTextColor(ContextCompat.getColor(this, (sess && failed > 0) ? R.color.k2go_amber_text : R.color.k2go_muted));
        col.addView(sub);
        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_right);
        chev.setColorFilter(ContextCompat.getColor(this, R.color.k2go_muted));
        row.addView(chev, new LinearLayout.LayoutParams(px(24), px(24)));

        if (sess) row.setOnClickListener(v -> openDetail(key));   // detail only once there's a live session
        return row;
    }

    private View indicator(boolean sess, boolean complete, int failed) {
        if (!sess) {                                   // waiting for services / drain
            View d = new View(this);
            d.setBackgroundResource(R.drawable.k2go_dot);
            d.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.k2go_hairline)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(10), px(10));
            return wrap(d, lp);
        }
        if (complete && failed > 0) {                  // partial failure
            ImageView a = new ImageView(this);
            a.setImageResource(R.drawable.k2go_info_circle);
            a.setColorFilter(ContextCompat.getColor(this, R.color.k2go_amber_text));
            return sized(a, 20);
        }
        if (complete) {                                // done
            ImageView chk = new ImageView(this);
            chk.setImageResource(R.drawable.ic_check_circle);
            chk.setColorFilter(ContextCompat.getColor(this, R.color.k2go_leaf));
            return sized(chk, 18);
        }
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);   // active
        return sized(pb, 22);
    }

    private View wrap(View v, LinearLayout.LayoutParams lp) { v.setLayoutParams(lp); return v; }
    private View sized(View v, int dp) { v.setLayoutParams(new LinearLayout.LayoutParams(px(dp), px(dp))); return v; }

    // ---- auto-redirect on success ----
    private final Runnable goHomeRunnable = () -> goHome(true);
    private void scheduleRedirect() {
        if (redirectScheduled) return;
        redirectScheduled = true;
        main.postDelayed(goHomeRunnable, REDIRECT_MS);
    }
    private void cancelRedirect() {
        main.removeCallbacks(goHomeRunnable);
        redirectScheduled = false;
    }
    private void goHome(boolean clearSessions) {
        cancelRedirect();
        if (clearSessions) { ZimDownloadService.finishSession(); BooksDownloadService.finishSession(); }
        finish();
    }

    // ---- detail: host the real per-module card ----
    private void openDetail(String key) {
        showingDetail = true;
        androidx.fragment.app.Fragment f = "zim".equals(key)
                ? ZimPreparingFragment.newInstance(true) : BooksDownloadsFragment.newInstance(true);
        getSupportFragmentManager().beginTransaction().replace(R.id.k2go_sp_fraghost, f).commit();
        indexScroll.setVisibility(View.GONE);
        detailRoot.setVisibility(View.VISIBLE);
    }

    private void backToIndex() {
        showingDetail = false;
        androidx.fragment.app.Fragment cur = getSupportFragmentManager().findFragmentById(R.id.k2go_sp_fraghost);
        if (cur != null) getSupportFragmentManager().beginTransaction().remove(cur).commit();
        detailRoot.setVisibility(View.GONE);
        indexScroll.setVisibility(View.VISIBLE);
        ZimDownloadService.setListener(this::render);
        BooksDownloadService.setListener(this::render);
        render();
    }
}
