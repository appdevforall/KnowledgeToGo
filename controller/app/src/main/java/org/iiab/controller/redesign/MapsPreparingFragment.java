/*
 * ============================================================================
 * Name        : MapsPreparingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 (slice 3). Maps Preparing. A CONTAINED placeholder spinner
 *               (independent of the boot/close Lottie) plus a single status line that
 *               mirrors what the background process is doing, like the boot screen shows the
 *               current service. No invented progress bar. The status text is MOCK for now
 *               (cycles the phases); the REST/Ansible backend feeds the real output later.
 *               "Run in background" leaves it running and returns to the Get More hub.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;

public class MapsPreparingFragment extends Fragment {

    // Mock phase feed until the backend streams real process text.
    private final int[] PHASES = {
            R.string.k2go_maps_phase_prepared,
            R.string.k2go_maps_phase_downloading,
            R.string.k2go_maps_phase_building,
            R.string.k2go_maps_phase_finishing,
            R.string.k2go_maps_phase_ready,
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private Runnable tick;
    private int step = 0;
    private TextView status;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_preparing, container, false);

        status = root.findViewById(R.id.k2go_prep_status);

        // Run in background -> back to the Get More hub (drops the whole Maps flow off the
        // back stack), the build keeps going.
        root.findViewById(R.id.k2go_prep_run_bg).setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).backToGetMoreHub();
            }
        });

        startMock();
        return root;
    }

    private void startMock() {
        step = 0;
        tick = new Runnable() {
            @Override
            public void run() {
                status.setText(getString(PHASES[step]));
                if (step < PHASES.length - 1) {
                    step++;
                    main.postDelayed(this, 1500);
                }
            }
        };
        main.post(tick);
    }

    @Override
    public void onDestroyView() {
        if (tick != null) main.removeCallbacks(tick);
        super.onDestroyView();
    }
}
