/*
 * ============================================================================
 * Name        : ModuleInstallFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. Shared per-module install/status detail, hosted inside the install index
 *               (observe only, like the maps Preparing card). Shows the module's name, a spinner, a
 *               status line that follows the module queue for THIS module (queued / installing / done
 *               / failed), and the collapsible LogRepository terminal (the real Ansible output — a
 *               single shared stream, dominated by the module currently running). The module's state
 *               is derived from the durable batch order (ModuleBatch) + the queue (ModuleQueueState),
 *               since the queue only reports the current module + how many remain.
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

public class ModuleInstallFragment extends Fragment {

    private static final String ARG_KEY = "yamlKey";
    private static final int MAX_LOG_LINES = 500;

    public static ModuleInstallFragment newInstance(String yamlBaseKey) {
        ModuleInstallFragment f = new ModuleInstallFragment();
        Bundle b = new Bundle();
        b.putString(ARG_KEY, yamlBaseKey);
        f.setArguments(b);
        return f;
    }

    private String key;
    private TextView status;
    private View progressRow;                                                                 // ADFA-5228
    private com.google.android.material.progressindicator.LinearProgressIndicator progress;
    private TextView progressPct, progressEta;
    private org.appdevforall.k2go.widget.LiveLogPanel logPanel;   // K2GO-374: shared details panel
    private boolean terminalDone = false;   // this module reached done/failed → stop live status
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private LogRepository.Listener logListener;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_module_install, container, false);
        key = getArguments() != null ? getArguments().getString(ARG_KEY) : null;

        TextView title = root.findViewById(R.id.k2go_modinst_title);
        ModuleCards.Card c = ModuleCards.byKey(key);
        title.setText(c != null ? getString(c.detailTitleRes) : (key == null ? "" : key));

        status = root.findViewById(R.id.k2go_modinst_status);
        progressRow = root.findViewById(R.id.k2go_modinst_progress_row);   // ADFA-5228
        progress = root.findViewById(R.id.k2go_modinst_progress);
        progressPct = root.findViewById(R.id.k2go_modinst_progress_pct);
        progressEta = root.findViewById(R.id.k2go_modinst_progress_eta);
        // ADFA-5074: the key is passed so a module that earns its own art can be matched.
        org.appdevforall.k2go.util.ProgressVisuals.applyForModule(root, key);
        logPanel = root.findViewById(R.id.k2go_modinst_log_panel);
        logPanel.setOnExpandListener(this::seedLog);   // K2GO-374: seed from the snapshot on expand

        updateStatus();
        ModuleQueueRepository.get().state().observe(getViewLifecycleOwner(), st -> updateStatus());
        // ADFA-4898 P4: surface a "seems stalled" hint over the frozen status line while this module is
        // the one running and no movement (log line / write-dir growth) has arrived for the stall window.
        // Surface only — the install keeps going; a new log line or updateStatus restores the live line.
        ModuleQueueRepository.get().stalled().observe(getViewLifecycleOwner(), isStalled -> {
            if (Boolean.TRUE.equals(isStalled) && installing() && !terminalDone) {
                status.setText(getString(R.string.k2go_mod_phase_stalled));
            } else {
                updateStatus();
            }
        });

        logListener = new LogRepository.Listener() {
            @Override public void onAppend(String line) {
                if (!isAdded() || line == null) return;
                if (!terminalDone && installing()) status.setText(line);
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

    /** True while THIS module is the one currently running. */
    private boolean installing() {
        ModuleQueueState mq = ModuleQueueRepository.get().current();
        return key != null && key.equals(mq.currentModule)
                && mq.phase == ModuleQueueState.Phase.RUNNING;
    }

    /** Derive this module's state from the batch order + the queue, and update the status line. */
    private void updateStatus() {
        if (status == null || !isAdded()) return;
        ModuleQueueState mq = ModuleQueueRepository.get().current();

        // ADFA-5228: determinate bar above the status line while THIS module installs; hidden when
        // it isn't running or has no task table (percent < 0), leaving the animation alone.
        if (progressRow != null) {
            boolean showBar = installing() && mq.percent >= 0;
            progressRow.setVisibility(showBar ? View.VISIBLE : View.GONE);
            if (showBar) {
                progress.setProgressCompat(mq.percent, true);
                progressPct.setText(mq.percent + "%");
                progressEta.setText(org.appdevforall.k2go.install.presentation.EtaText.of(requireContext(), mq.etaSeconds));
            }
        }

        // ADFA-4898: one predicate for "did this module fail" across every surface (hub pill, detail
        // Retry/Back, this progress line) — ModuleQueueState.didFail(key). Was hand-rolled here as
        // failedModules.contains(...); routed through the shared atom so the three can't drift apart.
        if (mq.didFail(key)) {
            terminalDone = true;
            status.setText(getString(R.string.k2go_mod_phase_failed));
            return;
        }
        if (mq.phase == ModuleQueueState.Phase.DONE) {
            terminalDone = true;
            status.setText(getString(R.string.k2go_mod_phase_done));
            return;
        }
        if (installing()) {
            // ADFA-4898: a retry from this card brought the module back to RUNNING — clear the terminal
            // latch so the live log resumes driving the status line (it stops again on the next terminal).
            terminalDone = false;
            // live status is driven by the log; keep the phase text only until the first line arrives.
            if (logLines.isEmpty()) status.setText(getString(R.string.k2go_mod_phase_installing));
            return;
        }
        // Not current and not done: either already processed (earlier in the batch) or still queued.
        String[] batch = ModuleBatch.keys(requireContext());
        int me = indexOf(batch, key);
        int cur = indexOf(batch, mq.currentModule);
        if (me >= 0 && cur >= 0 && me < cur) {
            status.setText(getString(R.string.k2go_mod_phase_done));   // the queue moved past this one
        } else {
            status.setText(getString(R.string.k2go_mod_phase_queued));
        }
    }

    private static int indexOf(String[] arr, String v) {
        if (arr == null || v == null) return -1;
        for (int i = 0; i < arr.length; i++) if (v.equals(arr[i])) return i;
        return -1;
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
