/*
 * ============================================================================
 * Name        : ImportProgressFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Observes the ContentImportService session and renders the import progress
 *               (overall + current item: bar / percent / bytes / speed / ETA), then the settled state
 *               (done or partial) with Retry / Finish. Reads state only; all work is the foreground
 *               service's. Mirror of ExportProgressFragment.
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

import java.util.Locale;

public class ImportProgressFragment extends Fragment {

    private TextView source;
    private TextView overallLabel;
    private LinearProgressIndicator overallBar;
    private TextView overallDetail;
    private TextView itemLabel;
    private LinearProgressIndicator itemBar;
    private TextView message;
    private Button cancel;
    private Button retry;
    private Button finish;

    private final ImportSession session = ContentImportService.session();

    /** One-shot guard so a re-entrant render() does not schedule the back-pop twice. */
    private boolean leaving;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_import, container, false);
        source = root.findViewById(R.id.k2go_cxi_source);
        overallLabel = root.findViewById(R.id.k2go_cxi_overall_label);
        overallBar = root.findViewById(R.id.k2go_cxi_overall_bar);
        overallDetail = root.findViewById(R.id.k2go_cxi_overall_detail);
        itemLabel = root.findViewById(R.id.k2go_cxi_item_label);
        itemBar = root.findViewById(R.id.k2go_cxi_item_bar);
        message = root.findViewById(R.id.k2go_cxi_message);
        cancel = root.findViewById(R.id.k2go_cxi_cancel);
        retry = root.findViewById(R.id.k2go_cxi_retry);
        finish = root.findViewById(R.id.k2go_cxi_finish);

        cancel.setOnClickListener(v -> ContentImportService.cancel(requireContext()));
        retry.setOnClickListener(v -> ContentImportService.retry(requireContext()));
        finish.setOnClickListener(v -> {
            ContentImportService.finishSession(requireContext());
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
        source.setText(getString(R.string.k2go_cx_import_source_fmt, session.sourceLabel()));

        boolean running = session.isRunning();
        String error = session.errorMessage();

        overallBar.setIndeterminate(false);
        overallBar.setProgressCompat(session.overallPercent(), true);
        overallLabel.setText(getString(R.string.k2go_cx_export_overall_fmt, session.overallPercent()));
        overallDetail.setText(overallDetail());

        renderCurrentItem(running);
        renderSettled(running, error);

        cancel.setVisibility(running ? View.VISIBLE : View.GONE);
        boolean settled = !running;
        retry.setVisibility(settled && session.canRetry() ? View.VISIBLE : View.GONE);
        finish.setVisibility(settled ? View.VISIBLE : View.GONE);
    }

    private void renderCurrentItem(boolean running) {
        int i = session.index();
        if (running && i >= 0) {
            itemLabel.setVisibility(View.VISIBLE);
            itemBar.setVisibility(View.VISIBLE);
            itemLabel.setText(session.label(i));
            itemBar.setProgressCompat(session.currentItemPercent(), true);
        } else {
            itemLabel.setVisibility(View.GONE);
            itemBar.setVisibility(View.GONE);
        }
    }

    private void renderSettled(boolean running, String error) {
        if (running) {
            message.setVisibility(View.GONE);
            return;
        }
        int done = countDone();
        int total = session.itemCount();
        String text;
        if (error != null) {
            text = error;
        } else if (session.allDone()) {
            text = getString(R.string.k2go_cx_import_done_fmt, done);
        } else {
            text = getString(R.string.k2go_cx_import_partial_fmt, done, total);
        }
        message.setText(text);
        message.setVisibility(View.VISIBLE);
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

    /**
     * Leave this progress screen. The pop is deferred to the next main-loop tick and re-checked there.
     * render() runs from onResume() and from the session listener, which the FragmentManager can drive
     * from inside a transaction, so a synchronous pop here throws "FragmentManager is already executing
     * transactions" (K2GO-427). The foreground service also begins the session on a separate message, so
     * at the first render the session may not exist yet: only leave if it is still gone on the tick.
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
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        });
    }
}
