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
    private TextView liveNote;
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
        liveNote = v.findViewById(R.id.k2go_kconf_live_note);
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
            // A narrowed channel is listed by what it will actually bring, and says
            // so: "Whole course" versus "N topics" is the difference between a 60 GB
            // download and a 2 GB one, and the line must not hide which one it is.
            breakdown.addView(row(c.name(),
                    vm.sizeUnknownFor(c) ? getString(R.string.k2go_kolibri_size_unknown)
                            : ByteFormatter.toHuman(vm.bytesFor(c)),
                    false));
            breakdown.addView(scopeNote(c));
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

        // Per the template the forward action carries the SIZE, not the count.
        confirm.setText(getString(R.string.k2go_zim_add_setup_fmt,
                ByteFormatter.toHuman(plan.estimatedBytes())));
        confirm.setOnClickListener(x -> bankAndReturn(chosen));

        if (!isWizard()) {
            // Get More door: browsing and picking work, but there is nothing to hand
            // the order to yet. Banking it would look like the button did nothing —
            // KolibriProvisioner.drain() is only called from SetupProgressActivity,
            // so a wishlist written here would sit until some later install. Refuse
            // visibly instead, and say why — without erasing the fit verdict above,
            // which is the one figure this screen exists to state.
            confirm.setEnabled(false);
            liveNote.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Pre-install (wizard) versus the Get More door; only the forward action differs.
     *
     * <p>Fails <em>closed</em>: an unrecognised host is treated as the live door, so
     * the default is the mode that writes nothing. Defaulting the other way would
     * have an unexpected host persist an order to the wishlist.
     */
    private boolean isWizard() {
        return getActivity() instanceof SetupLibraryActivity
                && ((SetupLibraryActivity) getActivity()).isKolibriWizard();
    }

    /** The scope line under a course: whole thing, or how many topics were picked. */
    private View scopeNote(Channel c) {
        TextView t = new TextView(requireContext());
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        boolean narrowed = vm.isNarrowed(c.id());
        t.setText(narrowed
                ? getString(R.string.k2go_kolibri_narrowed_fmt, vm.subtreesFor(c.id()).size())
                : getString(R.string.k2go_kolibri_whole_course));
        t.setTextColor(ContextCompat.getColor(requireContext(),
                narrowed ? R.color.k2go_teal : R.color.k2go_muted));
        return t;
    }

    private SeedPlan planOf(List<Channel> chosen) {
        List<ChannelSelection> sels = new ArrayList<>();
        Map<String, Long> sizes = new HashMap<>();
        for (Channel c : chosen) {
            // Whole channel or picked subtrees — the view model answers, and the rule
            // that an empty narrowing means "everything" lives in PickedSubtrees, so
            // node_ids: [] (which Kolibri reads as zero nodes) can never be built.
            sels.add(vm.selectionFor(c));
            sizes.put(c.id(), vm.bytesFor(c));
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
            // nodeIds null/empty is written as "no nodeIds key at all", which the
            // provisioner reads as the whole channel — see KolibriWishlist.
            List<String> nodeIds = vm.subtreesFor(c.id()).nodeIds();
            KolibriWishlist.add(requireContext(), c.id(), c.version(), c.name(),
                    vm.bytesFor(c), nodeIds.isEmpty() ? null : nodeIds);
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
