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
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.ArrayDeque;
import java.util.List;

import org.iiab.controller.LogRepository;
import org.iiab.controller.R;
import org.iiab.controller.install.presentation.ModuleQueueRepository;
import org.iiab.controller.install.presentation.ModuleQueueState;

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
    private TextView status, logText, logLabel;
    private ScrollView logScroll;
    private ImageView logChevron;
    private boolean logExpanded = false;
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
        logLabel = root.findViewById(R.id.k2go_modinst_log_label);
        logChevron = root.findViewById(R.id.k2go_modinst_log_chevron);
        logScroll = root.findViewById(R.id.k2go_modinst_log_scroll);
        logText = root.findViewById(R.id.k2go_modinst_log_text);
        root.findViewById(R.id.k2go_modinst_log_toggle).setOnClickListener(v -> toggleLog());

        updateStatus();
        ModuleQueueRepository.get().state().observe(getViewLifecycleOwner(), st -> updateStatus());

        logListener = new LogRepository.Listener() {
            @Override public void onAppend(String line) {
                if (!isAdded() || line == null) return;
                if (!terminalDone && installing()) status.setText(line);
                if (logExpanded) { pushLine(line); renderLog(); }
            }
            @Override public void onCleared() {
                logLines.clear();
                if (logExpanded) renderLog();
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

        if (mq.failedModules != null && mq.failedModules.contains(key)) {
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

    private void toggleLog() {
        logExpanded = !logExpanded;
        logLabel.setText(getString(logExpanded ? R.string.k2go_maps_log_hide : R.string.k2go_maps_log_show));
        logChevron.setRotation(logExpanded ? 90f : 0f);
        logScroll.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
        if (logExpanded) {
            logLines.clear();
            List<String> snap = LogRepository.get().snapshot();
            int start = Math.max(0, snap.size() - MAX_LOG_LINES);
            for (int i = start; i < snap.size(); i++) logLines.addLast(snap.get(i));
            renderLog();
        }
    }

    private void pushLine(String line) {
        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) logLines.removeFirst();
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) sb.append(l).append('\n');
        logText.setText(sb.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onDestroyView() {
        if (logListener != null) LogRepository.get().removeListener(logListener);
        super.onDestroyView();
    }
}
