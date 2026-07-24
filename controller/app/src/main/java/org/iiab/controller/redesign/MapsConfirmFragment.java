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
package org.iiab.controller.redesign;

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

import org.iiab.controller.R;

import java.util.Locale;

public class MapsConfirmFragment extends Fragment {

    private static final String ARG_NAMES = "names", ARG_OPTS = "opts", ARG_MB = "mb";

    public static MapsConfirmFragment newInstance(String[] names, String[] opts, long[] mb) {
        MapsConfirmFragment f = new MapsConfirmFragment();
        Bundle b = new Bundle();
        b.putStringArray(ARG_NAMES, names);
        b.putStringArray(ARG_OPTS, opts);
        b.putLongArray(ARG_MB, mb);
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

        Button start = root.findViewById(R.id.k2go_start_btn);
        start.setText(getString(R.string.k2go_maps_start_building, fmt(total)));
        start.setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).openMapsPreparing();
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

    private String fmt(long mb) {
        if (mb >= 1024) return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        return mb + " MB";
    }
}
