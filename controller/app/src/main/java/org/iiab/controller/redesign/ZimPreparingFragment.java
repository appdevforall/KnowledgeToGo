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
    private DownloadControls dlControls;     // ADFA-4893: shared status-screen control row
    private boolean pauseSupported = false;  // ADFA-4893: box dash-node >= 1.2.4 exposes pause/resume (4894)

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
        // ADFA-4893: the shared status-screen control row (morph Pause -> Resume -> Retry + Cancel).
        // Gate on dash-node >= 1.2.4 (pause/resume); older boxes hide it (notification Cancel stands in).
        pauseSupported = DashboardVersion.atLeast(DashboardVersion.installed(requireContext()), 1, 2, 4);
        dlControls = new DownloadControls(
                root.findViewById(R.id.k2go_zprep_controls),
                root.findViewById(R.id.k2go_zprep_pause),
                root.findViewById(R.id.k2go_zprep_cancel),
                new DownloadControls.Controller() {
                    @Override public boolean isRunning() { return ZimDownloadService.isRunning(); }
                    @Override public boolean isPaused() { return ZimDownloadService.isPaused(); }
                    @Override public boolean hasFailed() { return ZimDownloadService.hasFailed(); }
                    @Override public boolean pauseSupported() { return pauseSupported; }
                    @Override public void pause() { ZimDownloadService.pause(ctx()); }
                    @Override public void resume() { ZimDownloadService.resume(ctx()); }
                    @Override public void retryFailed() { ZimDownloadService.retryFailed(ctx()); }
                    @Override public void cancelRunning() {
                        ContextCompat.startForegroundService(ctx(),
                                new Intent(ctx(), ZimDownloadService.class).setAction(ZimDownloadService.ACTION_CANCEL));
                    }
                    @Override public void dismiss() { ZimDownloadService.finishSession(); render(); }
                });
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
        // Derive n from the shortest of the arrays this method indexes (labels/bytes/status). A Cancel
        // purge clears them together now, but a snapshot read mid-purge could still see a mismatch —
        // clamping here means the loop below can never index past any array (ADFA-4893, defense in depth).
        int n = Math.min(labels.length, Math.min(bytes.length, status.length));
        if (n == 0) { dlControls.render(); return; }

        // Byte figures for the "X of Y · N of M" detail line; the bar percent comes from the single
        // shared source (ZimDownloadService.overallPercent) so the status screen and the index can't drift.
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
        int overall = ZimDownloadService.overallPercent();
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
            label.setText(f >= 0 && f < labels.length ? labels[f] + getString(R.string.k2go_zim_item_failed_suffix)
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

        dlControls.render();   // ADFA-4893: shared morph Pause/Resume/Retry + Cancel

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

    private android.content.Context ctx() { return requireContext().getApplicationContext(); }

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
