/*
 * ============================================================================
 * Name        : ZimConfirmFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. ZIM Confirm (screen 3). Cross-category breakdown of the selection
 *               cart (grouped by category: item count + size), total, an honest fit line, and
 *               the "this takes time" warning. "Start download" goes to Preparing. Reads the
 *               cross-category cart from SetupLibraryActivity.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.StatFs;
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

import java.util.LinkedHashMap;
import java.util.Map;

import org.iiab.controller.R;

public class ZimConfirmFragment extends Fragment {

    private int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @SuppressWarnings("unchecked")
    private LinkedHashMap<String, Long> cart() {
        return (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getZimCart() : new LinkedHashMap<>();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_zim_confirm, container, false);

        TextView back = root.findViewById(R.id.k2go_zconf_back);
        back.setText("‹ " + getString(R.string.k2go_zim_title));
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        // Group the cart by category: sum bytes + count.
        LinkedHashMap<String, long[]> byCat = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : cart().entrySet()) {
            String project = e.getKey().split("\\|", 2)[0];
            long[] agg = byCat.get(project);
            if (agg == null) { agg = new long[]{0, 0}; byCat.put(project, agg); }
            agg[0] += e.getValue();
            agg[1] += 1;
        }

        LinearLayout box = root.findViewById(R.id.k2go_zconf_breakdown);
        long total = 0;
        for (Map.Entry<String, long[]> e : byCat.entrySet()) {
            KiwixCategories.Category c = KiwixCategories.byKey(e.getKey());
            String name = c != null ? c.title : e.getKey();
            long bytes = e.getValue()[0];
            int count = (int) e.getValue()[1];
            total += bytes;
            box.addView(row(name, getString(R.string.k2go_zim_items_fmt, count), gb(bytes / (1024L * 1024L)), false));
            box.addView(divider());
        }
        box.addView(row(getString(R.string.k2go_zim_total), "", gb(total / (1024L * 1024L)), true));

        long totalMb = total / (1024L * 1024L);
        long freeMb;
        try { freeMb = new StatFs(requireContext().getFilesDir().getPath()).getAvailableBytes() / (1024L * 1024L); }
        catch (Exception e) { freeMb = 0; }
        boolean fits = freeMb <= 0 || totalMb <= freeMb;
        TextView fitsView = root.findViewById(R.id.k2go_zconf_fits);
        fitsView.setText(fits
                ? getString(R.string.k2go_zim_fits_fmt, gb(totalMb), gb(freeMb))
                : getString(R.string.k2go_zim_nofit_fmt, gb(totalMb), gb(freeMb)));
        fitsView.setTextColor(ContextCompat.getColor(requireContext(), fits ? R.color.k2go_leaf : R.color.k2go_amber_text));
        // Filled banner (never an outline/button); a round check for the "fits" case.
        fitsView.setBackgroundResource(fits ? R.drawable.k2go_ok_bg : R.drawable.k2go_warn_bg);
        fitsView.setCompoundDrawablesRelativeWithIntrinsicBounds(fits ? R.drawable.ic_check_circle : 0, 0, 0, 0);

        // ADFA-5061: asked of the system, not of the door. This used to read isZimWizard(),
        // a field lost on every activity recreation — after a rotation the screen believed
        // it was on the live path and tried to download against a system that did not exist.
        boolean wiz = banksInsteadOfDownloading();
        Button start = root.findViewById(R.id.k2go_zconf_start);
        start.setText(getString(wiz ? R.string.k2go_zim_add_setup_fmt : R.string.k2go_zim_start_fmt, gb(totalMb)));
        start.setEnabled(fits && total > 0);
        start.setOnClickListener(v -> {
            if (!(getActivity() instanceof SetupLibraryActivity)) return;
            SetupLibraryActivity a = (SetupLibraryActivity) getActivity();
            // Asked again at the press: the answer is cheap and the system can have
            // changed since the screen was drawn.
            if (banksInsteadOfDownloading()) a.zimWizardConfirm();   // no box yet: bank it
            else a.openZimPreparing();                               // live: download now
        });

        return root;
    }

    /**
     * ADFA-5061: whether the selection should be written down rather than downloaded —
     * true when there is no box to download against yet, or when the one that is there
     * is about to be replaced.
     */
    private boolean banksInsteadOfDownloading() {
        boolean replacing = (getActivity() instanceof SetupLibraryActivity)
                && ((SetupLibraryActivity) getActivity()).isReplacingSystem();
        return org.iiab.controller.system.data.ContentDoor.banks(
                requireContext(), org.iiab.controller.system.domain.ContentType.ZIM, replacing);
    }

    private View row(String name, String sub, String size, boolean totalRow) {
        LinearLayout r = new LinearLayout(requireContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(10), 0, px(10));

        LinearLayout text = new LinearLayout(requireContext());
        text.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(requireContext());
        n.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        n.setText(name);
        n.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        if (totalRow) n.setTypeface(n.getTypeface(), Typeface.BOLD);
        text.addView(n);
        if (!sub.isEmpty()) {
            TextView subv = new TextView(requireContext());
            subv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            subv.setText(sub);
            subv.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
            text.addView(subv);
        }
        r.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

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
        d.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        d.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.k2go_hairline));
        return d;
    }

    private String gb(long mb) {   // ADFA-4910: one standard size formatter for the whole UI
        return org.iiab.controller.util.ByteFormatter.humanMb(mb);
    }
}
