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
        // ADFA-5105: shared free-space probe instead of a per-screen StatFs copy.
        Long fb = org.iiab.controller.storage.StorageProbe.freeBytes(requireContext());
        long freeMb = (fb == null ? 0L : fb) / (1024L * 1024L);
        boolean fits = freeMb <= 0 || totalMb <= freeMb;
        TextView fitsView = root.findViewById(R.id.k2go_zconf_fits);
        fitsView.setText(fits
                ? getString(R.string.k2go_zim_fits_fmt, gb(totalMb), gb(freeMb))
                : getString(R.string.k2go_zim_nofit_fmt, gb(totalMb), gb(freeMb)));
        fitsView.setTextColor(ContextCompat.getColor(requireContext(), fits ? R.color.k2go_ok_ink : R.color.k2go_warn_ink));
        // Filled banner (never an outline/button); a round check for the "fits" case.
        fitsView.setBackgroundResource(fits ? R.drawable.k2go_ok_bg : R.drawable.k2go_warn_bg);
        fitsView.setCompoundDrawablesRelativeWithIntrinsicBounds(fits ? R.drawable.ic_check_circle : 0, 0, 0, 0);

        // ADFA-5061: asked of the system, not of the door. This used to read isZimWizard(),
        // a field lost on every activity recreation — the screen came back believing it was
        // on the live path and tried to download against a system that did not exist yet.
        // NOT on rotation: SetupLibraryActivity declares orientation|screenSize, so it is not
        // recreated for that. It IS recreated by a light/dark change (uiMode is absent from
        // that list), by "Don't keep activities", and by a process death with task restore.
        // Resolved once here and reused: the label and the action are then the same answer
        // by construction, and it is re-derived on every recreation because this method runs
        // again. A field is only dangerous when navigation writes it.
        final boolean banks = org.iiab.controller.system.data.ContentDoor.banks(
                requireContext(), org.iiab.controller.system.domain.ContentType.ZIM,
                SetupLibraryActivity.replacingSystem(this));
        Button start = root.findViewById(R.id.k2go_zconf_start);
        start.setText(getString(banks ? R.string.k2go_zim_add_setup_fmt : R.string.k2go_zim_start_fmt, gb(totalMb)));
        start.setEnabled(fits && total > 0);
        start.setOnClickListener(v -> {
            if (!(getActivity() instanceof SetupLibraryActivity)) return;
            SetupLibraryActivity a = (SetupLibraryActivity) getActivity();
            if (banks) a.zimWizardConfirm();   // no box yet: bank it
            else a.startZimDownload();         // ADFA-5074: start it, then land on the index
        });

        return root;
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
