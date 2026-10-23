/*
 * ============================================================================
 * Name        : ExportProgressFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Observes the ContentExportService session and renders the export progress
 *               (overall + current item: bar / percent / bytes / speed / ETA), then the settled
 *               state (done or partial) with Retry / Finish. Reads state only; all work is the
 *               foreground service's.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.domain.ExportJob;
import org.appdevforall.k2go.util.ByteFormatter;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ExportProgressFragment extends Fragment {

    private TextView destination;
    private TextView overallLabel;
    private LinearProgressIndicator overallBar;
    private TextView overallDetail;
    private TextView itemLabel;
    private LinearProgressIndicator itemBar;
    private TextView categoriesLabel;
    private LinearLayout categories;
    private TextView message;
    private TextView skipped;
    private Button cancel;
    private Button retry;
    private Button finish;

    private final ExportSession session = ContentExportService.session();

    /** One-shot guard so a re-entrant render() does not schedule the back-pop twice. */
    private boolean leaving;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_export, container, false);
        destination = root.findViewById(R.id.k2go_cxx_destination);
        overallLabel = root.findViewById(R.id.k2go_cxx_overall_label);
        overallBar = root.findViewById(R.id.k2go_cxx_overall_bar);
        overallDetail = root.findViewById(R.id.k2go_cxx_overall_detail);
        itemLabel = root.findViewById(R.id.k2go_cxx_item_label);
        itemBar = root.findViewById(R.id.k2go_cxx_item_bar);
        categoriesLabel = root.findViewById(R.id.k2go_cxx_categories_label);
        categories = root.findViewById(R.id.k2go_cxx_categories);
        message = root.findViewById(R.id.k2go_cxx_message);
        skipped = root.findViewById(R.id.k2go_cxx_skipped);
        cancel = root.findViewById(R.id.k2go_cxx_cancel);
        retry = root.findViewById(R.id.k2go_cxx_retry);
        finish = root.findViewById(R.id.k2go_cxx_finish);

        cancel.setOnClickListener(v -> ContentExportService.cancel(requireContext()));
        retry.setOnClickListener(v -> ContentExportService.retry(requireContext()));
        finish.setOnClickListener(v -> {
            ContentExportService.finishSession(requireContext());
            goBack();
        });
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        session.setListener(() -> {
            if (isAdded()) {
                render();
            }
        });
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        session.setListener(null);
    }

    private void render() {
        // A cleared / absent session means the run finished and was dismissed: leave this screen.
        if (!session.hasSession()) {
            goBack();
            return;
        }
        destination.setText(getString(R.string.k2go_cx_export_destination_fmt, session.destinationLabel()));

        boolean running = session.isRunning();
        boolean preparing = session.isPreparing();
        String error = session.errorMessage();

        overallBar.setIndeterminate(preparing);
        if (!preparing) {
            overallBar.setProgressCompat(session.overallPercent(), true);
        }
        overallLabel.setText(preparing
                ? getString(R.string.k2go_cx_export_preparing)
                : getString(R.string.k2go_cx_export_overall_fmt, session.overallPercent()));
        overallDetail.setText(preparing ? "" : overallDetail());

        renderCurrentItem(running, preparing);
        renderCategories(running, preparing);
        renderSettled(running, preparing, error);

        cancel.setVisibility(running ? View.VISIBLE : View.GONE);
        boolean settled = !running;
        retry.setVisibility(settled && session.canRetry() ? View.VISIBLE : View.GONE);
        finish.setVisibility(settled && !preparing ? View.VISIBLE : View.GONE);
    }

    private void renderCurrentItem(boolean running, boolean preparing) {
        int i = session.index();
        if (running && !preparing && i >= 0) {
            itemLabel.setVisibility(View.VISIBLE);
            itemBar.setVisibility(View.VISIBLE);
            itemLabel.setText(session.label(i));
            itemBar.setProgressCompat(session.currentItemPercent(), true);
        } else {
            itemLabel.setVisibility(View.GONE);
            itemBar.setVisibility(View.GONE);
        }
    }

    /**
     * One progress bar per app being exported ("3 bars if 3 categories"), beside the overall bar. Shown
     * only while running with at least two apps: with a single app the overall bar already is it.
     */
    private void renderCategories(boolean running, boolean preparing) {
        List<ExportSession.Category> cats = (running && !preparing)
                ? session.categories() : Collections.emptyList();
        if (cats.size() < 2) {
            categoriesLabel.setVisibility(View.GONE);
            categories.setVisibility(View.GONE);
            categories.removeAllViews();
            return;
        }
        categoriesLabel.setVisibility(View.VISIBLE);
        categories.setVisibility(View.VISIBLE);
        Context ctx = requireContext();
        // The apps are fixed for a run, so build the rows once, then just update text/progress.
        if (categories.getChildCount() != cats.size()) {
            categories.removeAllViews();
            for (int i = 0; i < cats.size(); i++) {
                categories.addView(buildCategoryRow(ctx));
            }
        }
        for (int i = 0; i < cats.size(); i++) {
            ExportSession.Category c = cats.get(i);
            LinearLayout row = (LinearLayout) categories.getChildAt(i);
            TextView label = (TextView) row.getChildAt(0);
            LinearProgressIndicator bar = (LinearProgressIndicator) row.getChildAt(1);
            label.setText(getString(R.string.k2go_cx_export_category_fmt, appDisplayName(c.app), c.percent));
            bar.setProgressCompat(c.percent, true);
        }
    }

    /** A per-category row: the app label over its own progress bar. */
    private LinearLayout buildCategoryRow(Context ctx) {
        int gap = px(ctx, 8);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = gap;
        row.setLayoutParams(rlp);

        TextView label = new TextView(ctx);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        label.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        row.addView(label);

        LinearProgressIndicator bar = new LinearProgressIndicator(ctx);
        bar.setMax(100);
        bar.setTrackThickness(px(ctx, 6));
        bar.setTrackCornerRadius(px(ctx, 3));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = px(ctx, 4);
        row.addView(bar, blp);
        return row;
    }

    private static int px(Context ctx, int dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    /** Friendly app label; falls back to the raw key for an unknown app. */
    private String appDisplayName(String app) {
        if (app == null) {
            return "";
        }
        switch (app) {
            case "kiwix":
                return getString(R.string.k2go_cx_app_kiwix);
            case "kolibri":
                return getString(R.string.k2go_cx_app_kolibri);
            case "calibre":
                return getString(R.string.k2go_cx_app_calibre);
            default:
                return app;
        }
    }

    private void renderSettled(boolean running, boolean preparing, String error) {
        if (running || preparing) {
            message.setVisibility(View.GONE);
            skipped.setVisibility(View.GONE);
            return;
        }
        int done = countDone();
        int total = session.itemCount();
        String text;
        if (error != null) {
            text = error;
        } else if (session.allDone()) {
            text = getString(R.string.k2go_cx_export_done_fmt, done);
        } else {
            text = getString(R.string.k2go_cx_export_partial_fmt, done, total);
        }
        message.setText(text);
        message.setVisibility(View.VISIBLE);

        List<String> skippedItems = session.skipped();
        if (skippedItems != null && !skippedItems.isEmpty()) {
            skipped.setText(getString(R.string.k2go_cx_export_skipped_fmt,
                    android.text.TextUtils.join("\n", skippedItems)));
            skipped.setVisibility(View.VISIBLE);
        } else {
            skipped.setVisibility(View.GONE);
        }
    }

    private int countDone() {
        int c = 0;
        for (int i = 0; i < session.itemCount(); i++) {
            if (session.status(i) == ExportJob.DONE) {
                c++;
            }
        }
        return c;
    }

    private String overallDetail() {
        String bytes = ByteFormatter.humanBytes(session.copiedTotal())
                + " / " + ByteFormatter.humanBytes(session.plannedTotal());
        long speed = session.speed();
        long eta = session.etaSeconds();
        StringBuilder sb = new StringBuilder(bytes);
        if (speed > 0) {
            sb.append("  :  ").append(ByteFormatter.humanBytes(speed)).append("/s");
        }
        if (eta >= 0) {
            sb.append("  :  ").append(getString(R.string.k2go_cx_export_eta_fmt, formatEta(eta)));
        }
        return sb.toString();
    }

    private static String formatEta(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    @Override
    public void onDestroyView() {
        // The scheduled pop runs on the view's handler; if the view is gone it will not run, so clear
        // the guard here so it does not stay true across a view recreation.
        leaving = false;
        super.onDestroyView();
    }

    /**
     * Leave this progress screen. The pop is deferred to the next main-loop tick and re-checked there.
     * render() runs from onResume() and from the session listener, which the FragmentManager can drive
     * from inside a transaction; the foreground service also begins the session on a later message, so
     * the first render can see no session yet. Deferring plus the enqueued popBackStack() (never a
     * synchronous onBackPressed) avoids "FragmentManager is already executing transactions" (K2GO-427),
     * and re-checking hasSession() on the tick keeps the screen if a run just started.
     */
    private void goBack() {
        if (leaving) {
            return;
        }
        View v = getView();
        if (v == null) {
            return;
        }
        leaving = true;
        v.post(() -> {
            if (!isAdded()) {
                return;
            }
            if (session.hasSession()) {
                leaving = false;   // a run exists now: stay, and let a later leave (Finish) reschedule
                return;
            }
            // Enqueued pop of this screen's own back-stack entry: deterministic and async-safe.
            getParentFragmentManager().popBackStack();
        });
    }
}
