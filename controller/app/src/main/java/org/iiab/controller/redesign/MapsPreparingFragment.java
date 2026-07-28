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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_preparing, container, false);

        fromIndex = getArguments() != null && getArguments().getBoolean(ARG_FROM_INDEX, false);

        status = root.findViewById(R.id.k2go_prep_status);
        status.setText(getString(R.string.k2go_maps_phase_prepared));

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
