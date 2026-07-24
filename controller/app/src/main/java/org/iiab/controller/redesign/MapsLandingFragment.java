/*
 * ============================================================================
 * Name        : MapsLandingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 (slice 2). Maps entry in Get More — the install/update entry
 *               into the layers & quality chooser. No FQR fork: full-quality regions move
 *               into the maps WebView later (the map already draws + generates the code;
 *               we only wire its backend execution via the REST engine). Hosted in
 *               SetupLibraryActivity.
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

public class MapsLandingFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_landing, container, false);

        TextView back = root.findViewById(R.id.k2go_maps_back);
        back.setText("‹ " + getString(R.string.k2go_gm_hub_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        root.findViewById(R.id.k2go_maps_choose_btn).setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openMapsChoose();
            }
        });
        return root;
    }
}
