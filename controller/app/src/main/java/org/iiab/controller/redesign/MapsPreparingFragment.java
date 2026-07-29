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
     *  host provides Back/Finish). Mirrors ZimPreparingFragment.newInstance(fromIndex). */
    public static MapsPreparingFragment newInstance(boolean fromIndex) {
        MapsPreparingFragment f = new MapsPreparingFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_FROM_INDEX, fromIndex);
        f.setArguments(b);
        return f;
    }

    private TextView status;
    private boolean fromIndex = false;  // ADFA-4901: hosted by the Finishing-setup index (observe only)
    private boolean launched = false;   // ADFA-4900: guard against re-launching maps on view recreation

    // ADFA-4901: collapsible live log (LogRepository) — the reusable terminal for proot module installs.
    private static final int MAX_LOG_LINES = 500;   // bound the on-screen buffer (repo keeps more)
    private TextView logText, logLabel;
    private ScrollView logScroll;
    private ImageView logChevron;
    private boolean logExpanded = false;
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

        // ADFA-4910: intentionally NO "Run in background" button. A maps (module) install runs on
        // the live system through proot, so it cannot be sent to the background — leaving this
        // screen (or tapping other cards) could error or corrupt the system. The flow stays gated
        // here until it finishes; Back is provided by the wizard host. (Content downloads that CAN
        // background — ZIM/Books — keep their own button.)

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
            if (st.phase == ModuleQueueState.Phase.RUNNING) {
                if (logLines.isEmpty()) status.setText(getString(R.string.k2go_maps_phase_building));
            } else if (st.phase == ModuleQueueState.Phase.DONE) {
                terminal = true;
                status.setText(st.failedModules.isEmpty()
                        ? getString(R.string.k2go_maps_phase_ready)
                        : getString(R.string.k2go_maps_phase_failed));
            }
        });

        // ADFA-4901: collapsible live log. The status line mirrors the latest line; expanding shows
        // the full terminal (LogRepository snapshot + live appends), auto-scrolled.
        logLabel = root.findViewById(R.id.k2go_prep_log_label);
        logChevron = root.findViewById(R.id.k2go_prep_log_chevron);
        logScroll = root.findViewById(R.id.k2go_prep_log_scroll);
        logText = root.findViewById(R.id.k2go_prep_log_text);
        root.findViewById(R.id.k2go_prep_log_toggle).setOnClickListener(v -> toggleLog());

        logListener = new LogRepository.Listener() {
            @Override public void onAppend(String line) {
                if (!isAdded() || line == null) return;
                if (!terminal) status.setText(line);
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

    /** Expand/collapse the terminal. On expand, seed from the current LogRepository snapshot. */
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
