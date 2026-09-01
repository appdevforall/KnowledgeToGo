/*
 * ============================================================================
 * Name        : MapsConfirmFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4848 (slice 3). Maps Confirm — the breakdown of the chosen layers +
 *               total + an honest "this takes time" warning, before the (long) build.
 *               Receives the selection from MapsChooseFragment via arguments.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.util.Snackbars;


public class MapsConfirmFragment extends Fragment {

    private static final String ARG_NAMES = "names", ARG_OPTS = "opts", ARG_MB = "mb", ARG_LEVELS = "levels";

    public static MapsConfirmFragment newInstance(String[] names, String[] opts, long[] mb, String[] levels) {
        MapsConfirmFragment f = new MapsConfirmFragment();
        Bundle b = new Bundle();
        b.putStringArray(ARG_NAMES, names);
        b.putStringArray(ARG_OPTS, opts);
        b.putLongArray(ARG_MB, mb);
        b.putStringArray(ARG_LEVELS, levels);   // ADFA-4900: per-layer machine keys (null = off)
        f.setArguments(b);
        return f;
    }

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_maps_confirm, container, false);

        TextView back = root.findViewById(R.id.k2go_confirm_back);
        back.setText("‹ " + getString(R.string.k2go_maps_scr_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        Bundle a = getArguments();
        String[] names = a != null ? a.getStringArray(ARG_NAMES) : new String[0];
        String[] opts = a != null ? a.getStringArray(ARG_OPTS) : new String[0];
        long[] mb = a != null ? a.getLongArray(ARG_MB) : new long[0];
        if (names == null) names = new String[0];
        if (opts == null) opts = new String[0];
        if (mb == null) mb = new long[0];

        LinearLayout box = root.findViewById(R.id.k2go_confirm_breakdown);
        long total = 0;
        for (int i = 0; i < names.length; i++) {
            long m = i < mb.length ? mb[i] : 0;
            total += m;
            box.addView(row(names[i], i < opts.length ? opts[i] : "", m > 0 ? fmt(m) : "—", false));
            box.addView(divider());
        }
        box.addView(row(getString(R.string.k2go_maps_total), "", fmt(total), true));

        final long totalMb = total;   // ADFA-4910: banked so Get More "Your picks" counts maps
        final String[] levels = a != null ? a.getStringArray(ARG_LEVELS) : null;

        // ADFA-4900: in the wizard (pre-install) Maps banks the selection; post-install it installs.
        // ADFA-5061: which of the two is decided from the system rather than from a flag set when
        // the flow was opened. Maps is STOPPED content — a runrole, not a download — so once a
        // system exists the dispatcher answers RUN_STOPPED; only DEFER means "bank it", which is
        // exactly what the flag used to say. Resolved once so the label and the action agree.
        final boolean wizard = org.appdevforall.k2go.system.data.ContentDoor.banks(
                requireContext(), org.appdevforall.k2go.system.domain.ContentType.MAPS,
                SetupLibraryActivity.replacingSystem(this));

        Button start = root.findViewById(R.id.k2go_start_btn);
        start.setText(getString(wizard ? R.string.k2go_maps_add_setup_fmt : R.string.k2go_maps_start_building, fmt(total)));
        start.setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                SetupLibraryActivity act = (SetupLibraryActivity) getActivity();
                if (wizard) {
                    act.mapsWizardConfirm(levels, totalMb);
                } else if (org.appdevforall.k2go.env.EnvironmentLock.isHeld(requireContext())) {
                    // ADFA-4919/4951: proot (Maps) acts on the live system, so it must not overlap ANY
                    // deep-env op (runrole, download, backup/restore/clone). Refuse and tell the user to wait.
                    Snackbars.make(v, org.appdevforall.k2go.util.BusyMessage.resFor(requireContext())).show();
                } else {
                    // ADFA-4919: route Get More Maps through the install index (gated), same as the
                    // wizard — banks to MapsWishlist and opens SetupProgressActivity. Replaces the old
                    // standalone openMapsPreparing() so there is a single, gated way to install maps.
                    act.openMapsIndex(levels, totalMb);
                }
            }
        });

        return root;
    }

    private View row(String name, String opt, String size, boolean totalRow) {
        LinearLayout r = new LinearLayout(requireContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(10), 0, px(10));

        TextView n = new TextView(requireContext());
        n.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        n.setText(name);
        n.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        if (totalRow) n.setTypeface(n.getTypeface(), Typeface.BOLD);
        r.addView(n);

        if (!opt.isEmpty()) {
            TextView o = new TextView(requireContext());
            o.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            o.setText(opt);
            o.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            olp.leftMargin = px(8);
            r.addView(o, olp);
        }

        View spacer = new View(requireContext());
        r.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView sz = new TextView(requireContext());
        sz.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        sz.setText(size);
        sz.setTextColor(ContextCompat.getColor(requireContext(), totalRow ? R.color.k2go_teal : R.color.k2go_ink));
        sz.setTypeface(sz.getTypeface(), Typeface.BOLD);
        r.addView(sz);

        return r;
    }

    private View divider() {
        View d = new View(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, px(1) / 2 == 0 ? 1 : 1));
        d.setLayoutParams(lp);
        d.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.k2go_hairline));
        return d;
    }

    private String fmt(long mb) {   // ADFA-4910: one standard size formatter for the whole UI
        return org.appdevforall.k2go.util.ByteFormatter.humanMb(mb);
    }
}
