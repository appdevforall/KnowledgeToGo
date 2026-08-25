/*
 * ============================================================================
 * Name        : ZimPreparingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849 / ADFA-5074. The ZIM stream's detail view, hosted by
 *               SetupProgressActivity. Observe-only: it draws the session ZimDownloadService
 *               owns — a progress bar weighted by bytes, "X of N items", and a per-item
 *               checklist with inline retry. The service is the source of truth, so this
 *               re-attaches to an in-flight session and leaving does not stop it.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.Locale;

import org.iiab.controller.R;

public class ZimPreparingFragment extends Fragment {

    private TextView label, pct, detail;
    private ProgressBar bar;
    private LinearLayout listv;
    private LinearLayout controls;          // ADFA-4893: session control row (pause/resume + cancel)
    private Button pauseBtn, cancelBtn;
    private boolean pauseSupported = false; // ADFA-4893: box dash-node >= 1.2.4 exposes pause/resume (4894)

    /**
     * ADFA-5074: {@code newInstance(boolean fromIndex)} used to live here, and every caller now
     * passes what that flag meant. The flag hid this screen's own Finish and Run-in-background
     * buttons, and — the part that mattered — suppressed starting the download, because when the
     * Get More door hosted this fragment the fragment itself was what started it. Reopening a
     * screen that starts work is a question with no good answer, which is why the flag existed.
     * The door starts the work now ({@code SetupLibraryActivity.startZimDownload}), so this is
     * only ever an observer and there is nothing left to switch on.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_zim_preparing, container, false);

        label = root.findViewById(R.id.k2go_zprep_label);
        pct = root.findViewById(R.id.k2go_zprep_pct);
        detail = root.findViewById(R.id.k2go_zprep_detail);
        bar = root.findViewById(R.id.k2go_zprep_bar);
        listv = root.findViewById(R.id.k2go_zprep_list);
        // ADFA-4893: session controls on the status screen. Poll (service) drives label + visibility.
        controls = root.findViewById(R.id.k2go_zprep_controls);
        pauseBtn = root.findViewById(R.id.k2go_zprep_pause);
        cancelBtn = root.findViewById(R.id.k2go_zprep_cancel);
        // ADFA-4893: the primary button morphs Pause -> Resume -> Retry; the action is decided at tap
        // time from the live state, so we don't remove/re-add buttons across states.
        pauseBtn.setOnClickListener(v -> {
            android.content.Context ctx = requireContext().getApplicationContext();
            if (!ZimDownloadService.isRunning() && ZimDownloadService.hasFailed()) ZimDownloadService.retryFailed(ctx);
            else if (ZimDownloadService.isPaused()) ZimDownloadService.resume(ctx);
            else ZimDownloadService.pause(ctx);
        });
        // Cancel is always available: cancels a running session, or dismisses a failed/terminal one.
        cancelBtn.setOnClickListener(v -> {
            android.content.Context ctx = requireContext().getApplicationContext();
            if (ZimDownloadService.isRunning()) {
                ContextCompat.startForegroundService(ctx,
                        new Intent(ctx, ZimDownloadService.class).setAction(ZimDownloadService.ACTION_CANCEL));
            } else {
                ZimDownloadService.finishSession();
                render();
            }
        });
        // ADFA-4893: only offer the controls when the box's dash-node exposes pause/resume (>= 1.2.4,
        // ADFA-4894). atLeast() returns false for an unknown/older version, so old boxes hide the row
        // (notification Cancel still works) instead of showing a Pause that 404s.
        pauseSupported = DashboardVersion.atLeast(DashboardVersion.installed(requireContext()), 1, 2, 4);
        // ADFA-5074: the shared animation block declares no asset; the rule picks it.
        org.iiab.controller.util.ProgressVisuals.apply(root, org.iiab.controller.system.domain.ContentType.ZIM);

        // Observe the session; the service is the source of truth and outlives this view.
        ZimDownloadService.setListener(this::render);
        render();
        return root;
    }

    private void render() {
        if (!isAdded()) return;
        String[] labels = ZimDownloadService.labels();
        long[] bytes = ZimDownloadService.bytes();
        int[] status = ZimDownloadService.status();
        int idx = ZimDownloadService.index();
        int p = ZimDownloadService.percent();
        int n = labels.length;
        if (n == 0) { controls.setVisibility(View.GONE); return; }

        long totalBytes = 0, doneBytes = 0;
        int doneCount = 0;
        for (int i = 0; i < n; i++) {
            totalBytes += bytes[i];
            if (status[i] == ZimDownloadService.DONE || status[i] == ZimDownloadService.FAILED) {
                doneBytes += bytes[i];
                doneCount++;
            } else if (i == idx && (status[i] == ZimDownloadService.ACTIVE || status[i] == ZimDownloadService.INDEXING)) {
                doneBytes += bytes[i] * p / 100;
            }
        }
        int overall = totalBytes > 0 ? (int) Math.min(100, doneBytes * 100 / totalBytes)
                : (ZimDownloadService.isComplete() ? 100 : 0);
        bar.setProgress(overall);
        pct.setText(overall + "%");

        boolean anyFailed = ZimDownloadService.hasFailed();
        boolean running = ZimDownloadService.isRunning();
        boolean paused = ZimDownloadService.isPaused();
        int rc = ZimDownloadService.reconnectAttempt();
        boolean allOk = ZimDownloadService.isComplete() && !anyFailed;

        // Label reflects the true state — honest: never "all ready" when something failed.
        if (!running && anyFailed) {
            int f = firstFailed(status);
            label.setText(f >= 0 ? labels[f] + getString(R.string.k2go_zim_item_failed_suffix)
                                 : getString(R.string.k2go_zim_downloading_fmt, ""));
        } else if (paused) {
            label.setText(R.string.k2go_dl_paused);
        } else if (running && rc > 0) {
            // ADFA-4893: match rootfs wording exactly — "Reconnecting…  n of N" (static ellipsis).
            label.setText(getString(R.string.k2go_dl_attempt, rc, ZimDownloadService.reconnectTotal()));
        } else if (allOk) {
            label.setText(R.string.k2go_zim_all_ready);
        } else {
            label.setText(getString(R.string.k2go_zim_downloading_fmt, idx < n ? labels[idx] : ""));
        }

        long sp = ZimDownloadService.speed();
        String speedPart = (running && !paused && rc <= 0 && sp > 0)
                ? " · " + humanRate(sp) + getString(R.string.k2go_rate_per_second) : "";
        detail.setText(getString(R.string.k2go_zim_prep_detail_fmt,
                gb(doneBytes / (1024L * 1024L)), gb(totalBytes / (1024L * 1024L)), speedPart, doneCount, n));

        // ADFA-4893: [primary][Cancel] row. The primary morphs Pause -> Resume -> Retry; Cancel is always
        // present. Shown while running (needs pause support, dash-node >= 1.2.4) OR whenever an item failed
        // (Retry works on any version). Old boxes keep the notification Cancel while running.
        boolean showControls = (running && pauseSupported) || anyFailed;
        controls.setVisibility(showControls ? View.VISIBLE : View.GONE);
        if (showControls) {
            if (!running && anyFailed) pauseBtn.setText(R.string.k2go_dl_retry);
            else if (paused) pauseBtn.setText(R.string.k2go_dl_resume);
            else pauseBtn.setText(R.string.k2go_dl_pause);
        }

        drawChecklist(labels, status);
    }

    private void drawChecklist(String[] labels, int[] status) {
        // ADFA-4893: no per-item Retry pill — the status-screen Retry (the morphing primary button) owns
        // retry now, so the checklist is display-only and the retry affordance stays one consistent size.
        ProvisioningChecklist.render(requireContext(), listv, labels.length, status,
                ZimDownloadService.DONE, ZimDownloadService.FAILED,
                i -> (status[i] == ZimDownloadService.FAILED)
                        ? labels[i] + getString(R.string.k2go_zim_item_failed_suffix) : labels[i],
                null);
    }

    private static int firstFailed(int[] status) {
        for (int i = 0; i < status.length; i++) if (status[i] == ZimDownloadService.FAILED) return i;
        return -1;
    }

    private String gb(long mb) {   // ADFA-4910: one standard size formatter for the whole UI
        return org.iiab.controller.util.ByteFormatter.humanMb(mb);
    }

    /** bytes/sec -> a short rate token ("3.4 MB"); the caller appends the localized "/s". */
    private String humanRate(long bps) {
        if (bps <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB"};
        double v = bps;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return i == 0 ? String.format(Locale.US, "%.0f %s", v, u[i])
                : String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    @Override
    public void onDestroyView() {
        ZimDownloadService.setListener(null);
        super.onDestroyView();
    }
}
