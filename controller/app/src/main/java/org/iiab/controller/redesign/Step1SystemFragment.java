package org.iiab.controller.redesign;

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

/** Step 1 of "Set up your library": choose the system edition (apps) with a live storage bar.
 *  Edition sizes are resolved from the rootfs .meta4 (live when online); when offline they show
 *  the last-known value from RootfsCatalog — never hardcoded in the UI. */
public class Step1SystemFragment extends Fragment {

    private static final class Edition {
        final InstallationPlanner.Tier tier; final String name;
        final String desc; final boolean recommended;
        ImageView radio; TextView sizeView;
        Edition(InstallationPlanner.Tier t, String n, String d, boolean r) {
            tier = t; name = n; desc = d; recommended = r;
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
        editions.add(new Edition(InstallationPlanner.Tier.BASIC, "Basic",
                "Wikipedia reader app + Books app", false));
        editions.add(new Edition(InstallationPlanner.Tier.STANDARD, "Standard",
                "Basic plus Courses app", true));
        editions.add(new Edition(InstallationPlanner.Tier.FULL, "Full",
                "Standard plus Maps app (all apps)", false));

        LinearLayout host = root.findViewById(R.id.k2go_editions);
        for (Edition e : editions) {
            View row = inflater.inflate(R.layout.view_k2go_edition, host, false);
            ((TextView) row.findViewById(R.id.k2go_edition_name)).setText(e.name);
            ((TextView) row.findViewById(R.id.k2go_edition_desc)).setText(e.desc);
            e.sizeView = row.findViewById(R.id.k2go_edition_size);
            e.sizeView.setText(sizeText(InstallationPlanner.fallbackOsSizeGb(e.tier))); // instant last-known
            row.findViewById(R.id.k2go_edition_reco)
                    .setVisibility(e.recommended ? View.VISIBLE : View.GONE);
            e.radio = row.findViewById(R.id.k2go_edition_radio);
            row.setOnClickListener(v -> select(e.tier));
            host.addView(row);
            resolveEditionSize(e); // refine to the live .meta4 size when online
        }

        root.findViewById(R.id.k2go_step1_next).setOnClickListener(v -> {
            if (getActivity() instanceof SetupLibraryActivity) {
                ((SetupLibraryActivity) getActivity()).setSelectedTier(selected);
                ((SetupLibraryActivity) getActivity()).goToStep2();
            }
        });

        root.findViewById(R.id.k2go_step1_back).setOnClickListener(v -> requireActivity().finish());
        root.findViewById(R.id.k2go_step1_skip_now).setOnClickListener(v -> {
            requireContext().getSharedPreferences(getString(R.string.pref_file_internal), android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean(getString(R.string.pref_key_setup_complete), true).apply();
            startActivity(new android.content.Intent(requireContext(), LibraryActivity.class));
            requireActivity().finish();
        });
        select(selected);
        return root;
    }

    private static String sizeText(double gb) { return String.format(Locale.US, "~ %.1f GB", gb); }

    private void resolveEditionSize(Edition e) {
        InstallationPlanner.calculateProjectedSize(requireContext(), e.tier, false,
                ContentLanguage.systemDefault(), null,
                new InstallationPlanner.PlanResultListener() {
                    @Override
                    public void onCalculated(InstallationPlanner.StorageProjection p) {
                        if (isAdded() && e.sizeView != null) e.sizeView.setText(sizeText(p.osSize));
                    }
                    @Override public void onError(String error) { /* keep last-known */ }
                });
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
