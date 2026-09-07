/*
 * ============================================================================
 * Name        : MapsPreparingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 (slice 3) / ADFA-4900. Maps Preparing. A contained placeholder
 *               spinner plus a single status line. ADFA-4900: this now drives the REAL install
 *               — on first entry it starts the maps runrole through the module-queue engine
 *               (InstallService) with the per-layer selection, and the status line follows the
 *               real queue state (ModuleQueueRepository), no longer a mock. "Run in background"
 *               leaves it running and returns to the Get More hub.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;
import java.util.List;

import org.appdevforall.k2go.LogRepository;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.install.presentation.ModuleQueueRepository;
import org.appdevforall.k2go.install.presentation.ModuleQueueState;

public class MapsPreparingFragment extends Fragment {

    private static final String ARG_LEVELS = "levels";
    private static final String ARG_FROM_INDEX = "fromIndex";

    public static MapsPreparingFragment newInstance(String[] levels) {
        MapsPreparingFragment f = new MapsPreparingFragment();
        Bundle b = new Bundle();
        b.putStringArray(ARG_LEVELS, levels);
        f.setArguments(b);
        return f;
    }

    /** ADFA-4901: open as the maps detail inside the Finishing-setup index — observe only, never
     *  (re)start the install, and hide this fragment's own "Run in background" button (the index
     *  host provides Back/Finish). ADFA-5074: ZIM used to have the same flag and no longer needs
     *  one — the index is its only host. This one is still passed false by the deprecated
     *  standalone route (openMapsPreparing), so the flag survives until that route goes. */
    public static MapsPreparingFragment newInstance(boolean fromIndex) {
        MapsPreparingFragment f = new MapsPreparingFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_FROM_INDEX, fromIndex);
        f.setArguments(b);
        return f;
    }

    private TextView status;
    private View progressRow;                                                                 // ADFA-5228
    private com.google.android.material.progressindicator.LinearProgressIndicator progress;
    private TextView progressPct, progressEta;
    // K2GO-394: subordinate download card (the in-proot aria2c's live progress + Pause/Resume).
    private View downloadCard;
    private com.google.android.material.progressindicator.LinearProgressIndicator dlBar;
    private TextView dlBytes, dlRate;
    private android.widget.Button dlPause;
    private boolean fromIndex = false;  // ADFA-4901: hosted by the Finishing-setup index (observe only)
    private boolean launched = false;   // ADFA-4900: guard against re-launching maps on view recreation

    // ADFA-4901: collapsible live log (LogRepository) — the reusable terminal for proot module installs.
    private static final int MAX_LOG_LINES = 500;   // bound the on-screen buffer (repo keeps more)
    private org.appdevforall.k2go.widget.LiveLogPanel logPanel;   // K2GO-374: shared details panel
    private boolean terminal = false;   // true once the queue reports DONE (stop live status override)
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private LogRepository.Listener logListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_preparing, container, false);

        fromIndex = getArguments() != null && getArguments().getBoolean(ARG_FROM_INDEX, false);

        status = root.findViewById(R.id.k2go_prep_status);
        status.setText(getString(R.string.k2go_maps_phase_prepared));
        progressRow = root.findViewById(R.id.k2go_prep_progress_row);   // ADFA-5228
        progress = root.findViewById(R.id.k2go_prep_progress);
        progressPct = root.findViewById(R.id.k2go_prep_progress_pct);
        progressEta = root.findViewById(R.id.k2go_prep_progress_eta);
        // K2GO-394: the subordinate download card + its Pause/Resume, fed by MapsDownloadRepository.
        downloadCard = root.findViewById(R.id.k2go_prep_download);
        dlBar = root.findViewById(R.id.k2go_prep_dl_bar);
        dlBytes = root.findViewById(R.id.k2go_prep_dl_bytes);
        dlRate = root.findViewById(R.id.k2go_prep_dl_rate);
        dlPause = root.findViewById(R.id.k2go_prep_dl_pause);
        dlPause.setOnClickListener(v -> {
            // Toggle by the current phase: pause an active download, resume a paused one. The service
            // routes ACTION_PAUSE/RESUME to the in-proot aria2c over RPC (InstallService.doPause/doResume).
            boolean paused = org.appdevforall.k2go.maps.presentation.MapsDownloadRepository.get().current().isPaused();
            android.content.Intent i = new android.content.Intent(requireContext(),
                    org.appdevforall.k2go.install.presentation.InstallService.class);
            i.setAction(paused
                    ? org.appdevforall.k2go.install.presentation.InstallService.ACTION_RESUME
                    : org.appdevforall.k2go.install.presentation.InstallService.ACTION_PAUSE);
            requireContext().startService(i);
        });

        // ADFA-4919: the fromIndex=false branch below is the DEPRECATED standalone Get More route
        // (its own "Run in background", no index, no gate). Get More now routes through the install
        // index (SetupLibraryActivity.openMapsIndex), so this branch is unreachable in that flow.
        // Kept UNUSED pending ADFA-4842 (module management) — see SetupLibraryActivity.openMapsPreparing.
        View runBg = root.findViewById(R.id.k2go_prep_run_bg);
        if (fromIndex) {
            runBg.setVisibility(View.GONE);   // the index host provides Back/Finish; this card only observes
        } else {
            // Run in background -> back to the Get More hub (drops the whole Maps flow off the
            // back stack), the build keeps going in the foreground service.
            runBg.setOnClickListener(v -> {
                if (getActivity() instanceof SetupLibraryActivity) {
                    ((SetupLibraryActivity) getActivity()).backToGetMoreHub();
                }
            });
        }

        // Start the real install only on first entry (not on a config-change recreation, not when
        // hosted as the Finishing-setup detail, and not if a maps job is already running/done).
        String[] levels = getArguments() != null ? getArguments().getStringArray(ARG_LEVELS) : null;
        if (s == null && !launched && !fromIndex
                && getActivity() instanceof SetupLibraryActivity
                && !ModuleQueueRepository.get().isRunning()
                && ModuleQueueRepository.get().current().phase != ModuleQueueState.Phase.DONE) {
            ((SetupLibraryActivity) getActivity()).startMapsInstall(levels);
            launched = true;
        }

        // Follow the real queue state. While RUNNING the status line is driven by the live log
        // (latest line); the phase text is only a fallback until the first line arrives. DONE is
        // terminal and always wins.
        ModuleQueueRepository.get().state().observe(getViewLifecycleOwner(), st -> {
            if (st == null) return;
            // ADFA-5228: determinate bar while maps installs; hidden if maps has no table (percent < 0).
            if (progressRow != null) {
                boolean showBar = st.phase == ModuleQueueState.Phase.RUNNING && st.percent >= 0;
                progressRow.setVisibility(showBar ? View.VISIBLE : View.GONE);
                if (showBar) {
                    progress.setProgressCompat(st.percent, true);
                    progressPct.setText(st.percent + "%");
                    progressEta.setText(org.appdevforall.k2go.install.presentation.EtaText.of(requireContext(), st.etaSeconds));
                }
            }
            if (st.phase == ModuleQueueState.Phase.RUNNING) {
                if (logLines.isEmpty()) status.setText(getString(R.string.k2go_maps_phase_building));
            } else if (st.phase == ModuleQueueState.Phase.DONE) {
                terminal = true;
                status.setText(st.failedModules.isEmpty()
                        ? getString(R.string.k2go_maps_phase_ready)
                        : getString(R.string.k2go_maps_phase_failed));
            }
        });

        // K2GO-394: the subordinate download bar. Present only while a live in-proot download exists
        // (the RPC feed); hidden otherwise, so a stock rootfs with no RPC shows the phase spine alone.
        org.appdevforall.k2go.maps.presentation.MapsDownloadRepository.get().state()
                .observe(getViewLifecycleOwner(), this::renderDownload);

        // ADFA-4901: collapsible live log. The status line mirrors the latest line; expanding shows
        // the full terminal (LogRepository snapshot + live appends), auto-scrolled.
        org.appdevforall.k2go.util.ProgressVisuals.apply(root, org.appdevforall.k2go.system.domain.ContentType.MAPS);   // ADFA-5074
        logPanel = root.findViewById(R.id.k2go_prep_log_panel);
        logPanel.setOnExpandListener(this::seedLog);   // K2GO-374: seed from the snapshot on expand

        logListener = new LogRepository.Listener() {
            @Override public void onAppend(String line) {
                if (!isAdded() || line == null) return;
                if (!terminal) status.setText(line);
                if (logPanel.isExpanded()) { pushLine(line); renderLog(); }
            }
            @Override public void onCleared() {
                logLines.clear();
                if (logPanel.isExpanded()) renderLog();
            }
        };
        LogRepository.get().addListener(logListener);

        return root;
    }

    /** K2GO-394: render (or hide) the subordinate download card from the RPC progress feed. */
    private void renderDownload(org.appdevforall.k2go.maps.domain.MapsDownloadProgress p) {
        if (downloadCard == null) {
            return;
        }
        if (p == null || !p.isRunning()) {
            downloadCard.setVisibility(View.GONE);
            return;
        }
        downloadCard.setVisibility(View.VISIBLE);
        int pct = p.percent();
        dlBar.setIndeterminate(pct < 0);
        if (pct >= 0) {
            dlBar.setProgressCompat(pct, true);
        }
        String bytes = org.appdevforall.k2go.util.ByteFormatter.toHuman(p.completedBytes)
                + " / " + org.appdevforall.k2go.util.ByteFormatter.toHuman(p.totalBytes);
        dlBytes.setText(pct >= 0 ? (pct + "% · " + bytes) : bytes);
        if (p.isPaused()) {
            dlRate.setText(getString(R.string.k2go_dl_paused));
            dlPause.setText(getString(R.string.k2go_dl_resume));
        } else {
            String rate = org.appdevforall.k2go.util.ByteFormatter.toHuman(p.speedBytesPerSec) + "/s";
            String eta = org.appdevforall.k2go.install.presentation.EtaText.of(requireContext(), p.etaSeconds());
            dlRate.setText(eta == null || eta.isEmpty() ? rate : (eta + " · " + rate));
            dlPause.setText(getString(R.string.k2go_dl_pause));
        }
    }

    /** K2GO-374: seed the panel's deque from the current log snapshot when it is expanded. */
    private void seedLog() {
        logLines.clear();
        List<String> snap = LogRepository.get().snapshot();
        int start = Math.max(0, snap.size() - MAX_LOG_LINES);
        for (int i = start; i < snap.size(); i++) logLines.addLast(snap.get(i));
        renderLog();
    }

    private void pushLine(String line) {
        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) sb.append(l).append('\n');
        logPanel.setContent(sb.toString());
    }

    @Override
    public void onDestroyView() {
        if (logListener != null) LogRepository.get().removeListener(logListener);
        super.onDestroyView();
    }
}
