/*
 * ============================================================================
 * Name        : ModuleDetailFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. Module detail (Play Store style): 16:9 image, title + subtitle, and a
 *               description of what the module does. Primary "Schedule install" banks the module to
 *               ModuleWishlist and returns to the hub (✓); secondary "Install now" banks it and
 *               proceeds to the install index immediately. No content selector (maps is the only
 *               module that will add one, later) — the description replaces it. Reachable directly
 *               (deep-link) from the hub or, later, from Get More.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.iiab.controller.R;
import org.iiab.controller.util.Snackbars;

public class ModuleDetailFragment extends Fragment {

    private static final String ARG_KEY = "yamlKey";

    public static ModuleDetailFragment newInstance(String yamlBaseKey) {
        ModuleDetailFragment f = new ModuleDetailFragment();
        Bundle b = new Bundle();
        b.putString(ARG_KEY, yamlBaseKey);
        f.setArguments(b);
        return f;
    }

    private String key;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_module_detail, container, false);
        key = getArguments() != null ? getArguments().getString(ARG_KEY) : null;
        final ModuleCards.Card c = ModuleCards.byKey(key);
        if (c == null) {   // unknown module — nothing to show
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return root;
        }

        TextView back = root.findViewById(R.id.k2go_moddet_back);
        back.setText("‹ " + getString(R.string.k2go_mod_back));
        back.setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

        ((ImageView) root.findViewById(R.id.k2go_moddet_image)).setImageResource(c.imageRes);
        ((TextView) root.findViewById(R.id.k2go_moddet_title)).setText(c.detailTitleRes);
        ((TextView) root.findViewById(R.id.k2go_moddet_sub)).setText(c.subRes);
        ((TextView) root.findViewById(R.id.k2go_moddet_desc)).setText(c.descRes);

        Button schedule = root.findViewById(R.id.k2go_moddet_schedule);
        boolean scheduled = ModuleWishlist.contains(requireContext(), c.key());
        schedule.setText(getString(scheduled ? R.string.k2go_mod_unschedule : R.string.k2go_mod_schedule));
        schedule.setOnClickListener(v -> {
            if (ModuleWishlist.contains(requireContext(), c.key())) ModuleWishlist.remove(requireContext(), c.key());
            else ModuleWishlist.add(requireContext(), c.key());
            requireActivity().getOnBackPressedDispatcher().onBackPressed();   // back to the hub (shows ✓)
        });

        if (c.hasSelector) schedule.setVisibility(View.GONE);   // ADFA-4958: maps schedules via its selector, not here

        Button installNow = root.findViewById(R.id.k2go_moddet_install_now);
        installNow.setOnClickListener(v -> {
            if (org.iiab.controller.env.EnvironmentLock.isHeld(requireContext())) { Snackbars.make(v, R.string.k2go_install_busy).show(); return; }
            if (getActivity() instanceof SetupLibraryActivity) {
                if (c.hasSelector) {
                    ((SetupLibraryActivity) getActivity()).openMapsChoose();   // ADFA-4958: maps needs its content selector first
                } else {
                    ModuleWishlist.add(requireContext(), c.key());
                    ((SetupLibraryActivity) getActivity()).openModuleIndex();
                }
            }
        });

        return root;
    }
}
