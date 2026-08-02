/*
 * ============================================================================
 * Name        : MapsLandingFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 (slice 2) / ADFA-4910 (landing v3). Maps entry in Get More.
 *               Basic maps + "Rebuild the full maps" always show; the Full-Quality
 *               Regions card is dismissible (x) and its state is persisted, so
 *               returning users get the compact "Show" row. "Rebuild" opens the
 *               layers & quality chooser; "Continue" leaves with basic maps (back to
 *               the Get More hub). Hosted in SetupLibraryActivity.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import org.iiab.controller.R;

public class MapsLandingFragment extends Fragment {

    // ADFA-4910: persisted so a returning user sees the compact Full-Quality Regions row.
    private static final String PREF_FQR_DISMISSED = "maps_fqr_dismissed";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_landing, container, false);

        TextView back = root.findViewById(R.id.k2go_maps_back);
        back.setText("‹ " + getString(R.string.k2go_gm_hub_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        final View fqrCard = root.findViewById(R.id.k2go_maps_fqr_card);
        final View fqrCompact = root.findViewById(R.id.k2go_maps_fqr_compact_row);
        applyFqrState(fqrCard, fqrCompact, prefs().getBoolean(PREF_FQR_DISMISSED, false));

        root.findViewById(R.id.k2go_maps_fqr_dismiss).setOnClickListener(v -> {
            prefs().edit().putBoolean(PREF_FQR_DISMISSED, true).apply();
            applyFqrState(fqrCard, fqrCompact, true);
        });
        root.findViewById(R.id.k2go_maps_fqr_show).setOnClickListener(v -> {
            prefs().edit().putBoolean(PREF_FQR_DISMISSED, false).apply();
            applyFqrState(fqrCard, fqrCompact, false);
        });

        // "Rebuild the full maps" -> the layers & quality chooser (the previous single action).
        root.findViewById(R.id.k2go_maps_rebuild_card).setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openMapsChoose();
            }
        });

        // "Continue" -> leave with basic maps: pop the whole maps sub-flow, back to the Get More hub.
        root.findViewById(R.id.k2go_maps_continue).setOnClickListener(v ->
                requireActivity().getSupportFragmentManager()
                        .popBackStack("getmore_maps", FragmentManager.POP_BACK_STACK_INCLUSIVE));

        return root;
    }

    private void applyFqrState(View card, View compact, boolean dismissed) {
        card.setVisibility(dismissed ? View.GONE : View.VISIBLE);
        compact.setVisibility(dismissed ? View.VISIBLE : View.GONE);
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(
                getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
    }
}
