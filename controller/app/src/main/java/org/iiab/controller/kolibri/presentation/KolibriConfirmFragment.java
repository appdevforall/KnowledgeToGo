/*
 * ============================================================================
 * Name        : KolibriConfirmFragment.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4954. Review the chosen channels, show the total, and decide
 *               whether it fits — in three states, not two.
 * ============================================================================
 */
package org.iiab.controller.kolibri.presentation;

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
import androidx.lifecycle.ViewModelProvider;

import org.iiab.controller.R;
import org.iiab.controller.kolibri.data.KolibriWishlist;
import org.iiab.controller.kolibri.domain.Channel;
import org.iiab.controller.kolibri.domain.ChannelSelection;
import org.iiab.controller.kolibri.domain.SeedPlan;
import org.iiab.controller.redesign.SetupLibraryActivity;
import org.iiab.controller.util.ByteFormatter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The consent step: what was chosen, how much it is, and whether it fits.
 *
 * <p><b>Three fit states, not two.</b> The ZIM equivalent disables its button
 * whenever the total exceeds free space, which is right when every size is exact.
 * Here it is not: a channel Studio publishes without a size contributes nothing
 * to the total, so the total can be a floor rather than a figure.
 * {@link SeedPlan#fitsIn} returns {@code null} for exactly that case, and
 * presenting "cannot tell" as "does not fit" would block downloads that would
 * have succeeded. So: fits, does not fit, and proceed-with-a-warning.
 *
 * <p>Banking only. Nothing downloads from here: the order goes to
 * {@link KolibriWishlist} and {@code KolibriProvisioner} drains it once the
 * system is installed and the server is up.
 */
public final class KolibriConfirmFragment extends Fragment {

    private KolibriCatalogViewModel vm;

    private LinearLayout breakdown;
    private TextView fits;
    private Button confirm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_k2go_kolibri_confirm, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        breakdown = v.findViewById(R.id.k2go_kconf_breakdown);
        fits = v.findViewById(R.id.k2go_kconf_fits);
        confirm = v.findViewById(R.id.k2go_kconf_confirm);
        v.findViewById(R.id.k2go_kconf_back).setOnClickListener(x -> back());

        vm = new ViewModelProvider(requireActivity(),
                new KolibriCatalogViewModelFactory(requireContext()))
                .get(KolibriCatalogViewModel.class);

        render();
    }

    private void render() {
        List<Channel> chosen = vm.selection();
        breakdown.removeAllViews();

        for (Channel c : chosen) {
            breakdown.addView(row(c.name(),
                    c.hasKnownSize() ? ByteFormatter.toHuman(c.publishedSize())
                            : getString(R.string.k2go_kolibri_size_unknown),
                    false));
            breakdown.addView(divider());
        }
        breakdown.addView(row(getString(R.string.k2go_zim_total),
                ByteFormatter.toHuman(vm.selectionBytes()), true));

        applyFitState(chosen);
    }

    /**
     * Renders the fit decision. The plan is built through the domain so the rule
     * lives in one place: this screen only turns its answer into words.
     */
    private void applyFitState(List<Channel> chosen) {
        SeedPlan plan = planOf(chosen);
        Long freeBytes = readFreeBytes();
        Boolean verdict = plan.fitsIn(freeBytes);

        if (Boolean.TRUE.equals(verdict)) {
            fits.setText(getString(R.string.k2go_zim_fits_fmt,
                    ByteFormatter.toHuman(plan.requiredBytes()),
                    freeBytes == null ? "" : ByteFormatter.toHuman(freeBytes)));
            paint(R.color.k2go_leaf, R.drawable.k2go_ok_bg);
            confirm.setEnabled(!chosen.isEmpty());
        } else if (Boolean.FALSE.equals(verdict)) {
            fits.setText(getString(R.string.k2go_zim_nofit_fmt,
                    ByteFormatter.toHuman(plan.requiredBytes()),
                    freeBytes == null ? "" : ByteFormatter.toHuman(freeBytes)));
            paint(R.color.k2go_amber_text, R.drawable.k2go_warn_bg);
            confirm.setEnabled(false);
        } else {
            // Cannot tell: a channel with no published size, or no readable free
            // space. Warn and let it through — refusing here would block a
            // download that has every chance of succeeding.
            fits.setText(R.string.k2go_kolibri_fit_unknown);
            paint(R.color.k2go_amber_text, R.drawable.k2go_warn_bg);
            confirm.setEnabled(!chosen.isEmpty());
        }

        confirm.setText(getString(R.string.k2go_zim_add_setup_fmt,
                getString(R.string.k2go_zim_items_fmt, chosen.size())));
        confirm.setOnClickListener(x -> bankAndReturn(chosen));
    }

    private SeedPlan planOf(List<Channel> chosen) {
        List<ChannelSelection> sels = new ArrayList<>();
        Map<String, Long> sizes = new HashMap<>();
        for (Channel c : chosen) {
            // C1 queues whole channels only; subtree selection is a later change.
            sels.add(ChannelSelection.wholeChannel(c.id()));
            sizes.put(c.id(), c.publishedSize());
        }
        return SeedPlan.of(sels, sizes);
    }

    /** Free bytes on the data partition, or null when it cannot be read. */
    private Long readFreeBytes() {
        try {
            return new StatFs(requireContext().getFilesDir().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Writes the order and goes back to the hub. Nothing is downloaded: the wizard
     * has no server yet, so this is the "leave a food order" half of ADR-4853.
     */
    private void bankAndReturn(List<Channel> chosen) {
        for (Channel c : chosen) {
            KolibriWishlist.add(requireContext(), c.id(), c.version(), c.name(),
                    c.publishedSize(), null);
        }
        vm.clearSelection();
        if (getActivity() instanceof SetupLibraryActivity) {
            ((SetupLibraryActivity) getActivity()).kolibriWizardConfirm();
        }
    }

    private void paint(int textColor, int background) {
        fits.setTextColor(ContextCompat.getColor(requireContext(), textColor));
        fits.setBackgroundResource(background);
    }

    private View row(String left, String right, boolean bold) {
        LinearLayout r = new LinearLayout(requireContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(10), 0, px(10));

        TextView l = new TextView(requireContext());
        l.setTextAppearance(bold
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        l.setText(left);
        l.setMaxLines(2);
        l.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        r.addView(l, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView rt = new TextView(requireContext());
        rt.setTextAppearance(bold
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                : com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        rt.setText(right);
        rt.setTextColor(ContextCompat.getColor(requireContext(),
                bold ? R.color.k2go_ink : R.color.k2go_muted));
        r.addView(rt);
        return r;
    }

    private View divider() {
        View d = new View(requireContext());
        d.setBackgroundResource(R.color.k2go_hairline);
        d.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return d;
    }

    private void back() {
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }

    private int px(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
