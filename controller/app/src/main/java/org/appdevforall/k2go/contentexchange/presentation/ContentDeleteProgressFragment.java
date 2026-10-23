/*
 * ============================================================================
 * Name        : ContentDeleteProgressFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Observes the ContentDeleteService session and renders the delete progress
 *               (overall settled-count bar + the item in flight), then the settled state (done or
 *               partial) with the total space reclaimed and Retry / Finish. Reads state only; all
 *               work is the foreground service's.
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
import org.appdevforall.k2go.contentexchange.domain.DeleteJob;
import org.appdevforall.k2go.util.ByteFormatter;

public class ContentDeleteProgressFragment extends Fragment {

    private TextView overallLabel;
    private LinearProgressIndicator overallBar;
    private TextView overallDetail;
    private TextView itemLabel;
    private LinearProgressIndicator itemBar;
    private TextView message;
    private Button cancel;
    private Button retry;
    private Button finish;

    private final DeleteSession session = ContentDeleteService.session();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_content_delete, container, false);
        overallLabel = root.findViewById(R.id.k2go_cxd_overall_label);
        overallBar = root.findViewById(R.id.k2go_cxd_overall_bar);
        overallDetail = root.findViewById(R.id.k2go_cxd_overall_detail);
        itemLabel = root.findViewById(R.id.k2go_cxd_item_label);
        itemBar = root.findViewById(R.id.k2go_cxd_item_bar);
        message = root.findViewById(R.id.k2go_cxd_message);
        cancel = root.findViewById(R.id.k2go_cxd_cancel);
        retry = root.findViewById(R.id.k2go_cxd_retry);
        finish = root.findViewById(R.id.k2go_cxd_finish);

        cancel.setOnClickListener(v -> ContentDeleteService.cancel(requireContext()));
        retry.setOnClickListener(v -> ContentDeleteService.retry(requireContext()));
        finish.setOnClickListener(v -> {
            ContentDeleteService.finishSession();
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
        boolean running = session.isRunning();
        String error = session.errorMessage();

        overallBar.setProgressCompat(session.overallPercent(), true);
        overallLabel.setText(getString(R.string.k2go_cx_delete_overall_fmt, session.overallPercent()));
        overallDetail.setText(getString(R.string.k2go_cx_delete_reclaimed_fmt,
                ByteFormatter.humanBytes(session.freedTotal())));

        renderCurrentItem(running);
        renderSettled(running, error);

        cancel.setVisibility(running ? View.VISIBLE : View.GONE);
        boolean settled = !running;
        retry.setVisibility(settled && session.canRetry() ? View.VISIBLE : View.GONE);
        finish.setVisibility(settled ? View.VISIBLE : View.GONE);
    }

    private void renderCurrentItem(boolean running) {
        int i = session.index();
        if (running && i >= 0 && session.status(i) == DeleteJob.ACTIVE) {
            itemLabel.setVisibility(View.VISIBLE);
            itemBar.setVisibility(View.VISIBLE);
            itemLabel.setText(session.label(i));
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
        int done = session.doneCount();
        int total = session.itemCount();
        String text;
        if (error != null) {
            text = error;
        } else if (session.allDone()) {
            text = getString(R.string.k2go_cx_delete_done_fmt, done,
                    ByteFormatter.humanBytes(session.freedTotal()));
        } else {
            text = getString(R.string.k2go_cx_delete_partial_fmt, done, total);
        }
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private void goBack() {
        if (isAdded()) {
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
        }
    }
}
