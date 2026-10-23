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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.domain.ExportJob;
import org.appdevforall.k2go.util.ByteFormatter;

import java.util.List;
import java.util.Locale;

public class ExportProgressFragment extends Fragment {

    private TextView destination;
    private TextView overallLabel;
    private LinearProgressIndicator overallBar;
    private TextView overallDetail;
    private TextView itemLabel;
    private LinearProgressIndicator itemBar;
    private TextView message;
    private TextView skipped;
    private Button cancel;
    private Button retry;
    private Button finish;

    private final ExportSession session = ContentExportService.session();

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

    private void goBack() {
        if (isAdded()) {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    }
}
