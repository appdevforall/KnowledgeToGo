package org.iiab.controller.redesign;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;
import org.iiab.controller.applang.data.ContentLanguage;

/** Step 1 of "Set up your library": choose the system edition (apps) with a live storage bar. */
public class Step1SystemFragment extends Fragment {

    private static final class Edition {
        final InstallationPlanner.Tier tier; final String name; final double sizeGb;
        final String desc; final boolean recommended; ImageView radio;
        Edition(InstallationPlanner.Tier t, String n, double s, String d, boolean r) {
            tier = t; name = n; sizeGb = s; desc = d; recommended = r;
        }
    }

    private final List<Edition> editions = new ArrayList<>();
    private InstallationPlanner.Tier selected = InstallationPlanner.Tier.STANDARD;
    private View barUsed, barSystem, barFree;
    private LinearLayout bar;
    private TextView legend;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_setup_step1, container, false);
        bar = root.findViewById(R.id.k2go_storage_bar);
        barUsed = root.findViewById(R.id.k2go_bar_used);
        barSystem = root.findViewById(R.id.k2go_bar_system);
        barFree = root.findViewById(R.id.k2go_bar_free);
        legend = root.findViewById(R.id.k2go_storage_legend);
        tint(barUsed, R.color.k2go_muted);
        tint(barSystem, R.color.k2go_teal);
        tint(barFree, R.color.k2go_hairline);

        editions.clear();
        editions.add(new Edition(InstallationPlanner.Tier.BASIC, "Basic", 1.2,
                "Wikipedia reader app + Books app", false));
        editions.add(new Edition(InstallationPlanner.Tier.STANDARD, "Standard", 1.4,
                "Basic plus Courses app", true));
        editions.add(new Edition(InstallationPlanner.Tier.FULL, "Full", 2.7,
                "Standard plus Maps app (all apps)", false));

        LinearLayout host = root.findViewById(R.id.k2go_editions);
        for (Edition e : editions) {
            View row = inflater.inflate(R.layout.view_k2go_edition, host, false);
            ((TextView) row.findViewById(R.id.k2go_edition_name)).setText(e.name);
            ((TextView) row.findViewById(R.id.k2go_edition_desc)).setText(e.desc);
            ((TextView) row.findViewById(R.id.k2go_edition_size))
                    .setText(String.format(Locale.US, "~ %.1f GB", e.sizeGb));
            row.findViewById(R.id.k2go_edition_reco)
                    .setVisibility(e.recommended ? View.VISIBLE : View.GONE);
            e.radio = row.findViewById(R.id.k2go_edition_radio);
            row.setOnClickListener(v -> select(e.tier));
            host.addView(row);
        }

        root.findViewById(R.id.k2go_step1_next).setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).setSelectedTier(selected);
                ((SetupLibraryActivity) getActivity()).goToStep2();
            }
        });

        root.findViewById(R.id.k2go_step1_back).setOnClickListener(v -> requireActivity().finish());
        select(selected);
        return root;
    }

    private void select(InstallationPlanner.Tier tier) {
        selected = tier;
        if (getActivity() instanceof SetupLibraryActivity) {
            ((SetupLibraryActivity) getActivity()).setSelectedTier(tier);
        }
        for (Edition e : editions) {
            if (e.radio != null) {
                e.radio.setImageResource(e.tier == tier ? R.drawable.ic_radio_on : R.drawable.ic_radio_off);
            }
        }
        recomputeBar(tier);
    }

    private void recomputeBar(InstallationPlanner.Tier tier) {
        // System size (no content) resolves without a network read.
        InstallationPlanner.calculateProjectedSize(requireContext(), tier, false,
                ContentLanguage.systemDefault(), null,
                new InstallationPlanner.PlanResultListener() {
                    @Override
                    public void onCalculated(InstallationPlanner.StorageProjection projection) {
                        if (isAdded()) applyBar(projection.osSize);
                    }
                    @Override
                    public void onError(String error) { /* keep the last bar on error */ }
                });
    }

    private void applyBar(double systemGb) {
        double total = StorageInfo.totalGb();
        double used = StorageInfo.usedGb();
        double freeAfter = Math.max(0, StorageInfo.freeGb() - systemGb);
        if (total <= 0) total = used + systemGb + freeAfter + 0.01;
        bar.setWeightSum((float) total);
        setWeight(barUsed, (float) used);
        setWeight(barSystem, (float) systemGb);
        setWeight(barFree, (float) freeAfter);
        legend.setText(String.format(Locale.US, "Used %.1f  ·  System %.1f  ·  Free %.1f",
                used, systemGb, freeAfter));
    }

    private void setWeight(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = w;
        v.setLayoutParams(lp);
    }

    private void tint(View v, int colorRes) {
        v.setBackgroundColor(ContextCompat.getColor(requireContext(), colorRes));
    }
}
