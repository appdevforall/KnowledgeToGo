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
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.install.presentation.ModuleQueueRepository;
import org.iiab.controller.install.presentation.ModuleQueueState;

public class MapsPreparingFragment extends Fragment {

    private static final String ARG_LEVELS = "levels";

    public static MapsPreparingFragment newInstance(String[] levels) {
        MapsPreparingFragment f = new MapsPreparingFragment();
        Bundle b = new Bundle();
        b.putStringArray(ARG_LEVELS, levels);
        f.setArguments(b);
        return f;
    }

    private TextView status;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_preparing, container, false);

        status = root.findViewById(R.id.k2go_prep_status);
        status.setText(getString(R.string.k2go_maps_phase_prepared));

        // Run in background -> back to the Get More hub (drops the whole Maps flow off the
        // back stack), the build keeps going in the foreground service.
        root.findViewById(R.id.k2go_prep_run_bg).setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).backToGetMoreHub();
            }
        });

        // Start the real install only on first entry (not on a config-change recreation, and not
        // if a maps job is already running/done from this session).
        String[] levels = getArguments() != null ? getArguments().getStringArray(ARG_LEVELS) : null;
        if (s == null
                && getActivity() instanceof SetupLibraryActivity
                && !ModuleQueueRepository.get().isRunning()) {
            ((SetupLibraryActivity) getActivity()).startMapsInstall(levels);
        }

        // Follow the real queue state instead of a mock phase cycle.
        ModuleQueueRepository.get().state().observe(getViewLifecycleOwner(), st -> {
            if (st == null) return;
            if (st.phase == ModuleQueueState.Phase.RUNNING) {
                status.setText(getString(R.string.k2go_maps_phase_building));
            } else if (st.phase == ModuleQueueState.Phase.DONE) {
                status.setText(st.failedModules.isEmpty()
                        ? getString(R.string.k2go_maps_phase_ready)
                        : getString(R.string.k2go_maps_phase_failed));
            }
        });

        return root;
    }
}
