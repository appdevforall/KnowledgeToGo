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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.Locale;

import org.iiab.controller.R;

public class ZimPreparingFragment extends Fragment {

    private TextView label, pct, detail;
    private ProgressBar bar;
    private LinearLayout listv;

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
        if (n == 0) return;

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

        boolean allDone = ZimDownloadService.isComplete();
        label.setText(allDone
                ? getString(R.string.k2go_zim_all_ready)
                : getString(R.string.k2go_zim_downloading_fmt, idx < n ? labels[idx] : ""));
        long sp = ZimDownloadService.speed();
        String speedPart = (!allDone && sp > 0)
                ? " · " + humanRate(sp) + getString(R.string.k2go_rate_per_second) : "";
        detail.setText(getString(R.string.k2go_zim_prep_detail_fmt,
                gb(doneBytes / (1024L * 1024L)), gb(totalBytes / (1024L * 1024L)), speedPart, doneCount, n));

        drawChecklist(labels, status);
    }

    private void drawChecklist(String[] labels, int[] status) {
        ProvisioningChecklist.render(requireContext(), listv, labels.length, status,
                ZimDownloadService.DONE, ZimDownloadService.FAILED,
                i -> (status[i] == ZimDownloadService.FAILED)
                        ? labels[i] + getString(R.string.k2go_zim_item_failed_suffix) : labels[i],
                i -> ZimDownloadService.retry(requireContext().getApplicationContext(), i));
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
