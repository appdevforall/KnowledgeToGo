package org.iiab.controller.redesign;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.util.Locale;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.R;
import org.iiab.controller.SystemStateEvaluator;
import org.iiab.controller.applang.data.ContentLanguage;
import org.iiab.controller.install.presentation.InstallService;
import org.json.JSONObject;

/**
 * Step 2 Content — Option A (Expandable + storage Bar). Wikipedia coverage/detail compose a
 * Kiwix variant with real catalog sizes; the bar + Download total come from InstallationPlanner;
 * Download starts the real InstallService (tier + companion + lang + variant).
 */
public class Step2OptionAFragment extends Fragment {

    private boolean everything = false;   // false = Popular
    private boolean pictures = true;      // true = With pictures
    private String lang;
    private JSONObject langData;          // catalog entry for the language (nullable)

    private View barUsed, barSystem, barPicks, barFree;
    private LinearLayout bar;
    private TextView legend, wikiSize, download;
    private TextView covPopular, covEverything, detPictures, detText;

    private InstallationPlanner.Tier tier() {
        return (getActivity() instanceof SetupLibraryActivity)
                ? ((SetupLibraryActivity) getActivity()).getSelectedTier()
                : InstallationPlanner.Tier.STANDARD;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_k2go_setup_step2a, c, false);
        lang = ContentLanguage.systemDefault();
        if (getActivity() instanceof SetupLibraryActivity) {
            everything = ((SetupLibraryActivity) getActivity()).isEverything();
            pictures = ((SetupLibraryActivity) getActivity()).isPictures();
        }
        AbFlip.attach(v.findViewById(R.id.k2go_step2_title), () -> {
            if (getActivity() instanceof SetupLibraryActivity) ((SetupLibraryActivity) getActivity()).flipAbTest();
        });
        bar = v.findViewById(R.id.k2go_bar);
        barUsed = v.findViewById(R.id.k2go_bar_used);
        barSystem = v.findViewById(R.id.k2go_bar_system);
        barPicks = v.findViewById(R.id.k2go_bar_picks);
        barFree = v.findViewById(R.id.k2go_bar_free);
        legend = v.findViewById(R.id.k2go_legend);
        wikiSize = v.findViewById(R.id.k2go_wiki_size);
        download = v.findViewById(R.id.k2go_download);
        covPopular = v.findViewById(R.id.k2go_cov_popular);
        covEverything = v.findViewById(R.id.k2go_cov_everything);
        detPictures = v.findViewById(R.id.k2go_det_pictures);
        detText = v.findViewById(R.id.k2go_det_text);
        tintBg(barUsed, R.color.k2go_muted);
        tintBg(barSystem, R.color.k2go_teal);
        tintBg(barPicks, R.color.k2go_leaf);
        tintBg(barFree, R.color.k2go_hairline);

        covPopular.setOnClickListener(x -> { everything = false; refresh(); });
        covEverything.setOnClickListener(x -> { everything = true; refresh(); });
        detPictures.setOnClickListener(x -> { pictures = true; refresh(); });
        detText.setOnClickListener(x -> { pictures = false; refresh(); });
        download.setOnClickListener(x -> startDownload());

        // Real Kiwix catalog for this language (cached; network only if stale).
        InstallationPlanner.getOrFetchCatalog(requireContext(), new InstallationPlanner.CacheListener() {
            @Override
            public void onReady(JSONObject catalog) {
                if (!isAdded()) return;
                langData = catalog.optJSONObject(lang);
                if (langData == null) langData = catalog.optJSONObject("en");
                refresh();
            }
            @Override
            public void onError(String error) { /* keep "—"; Download still resolves the variant */ }
        });
        refresh();
        return v;
    }

    private String popularBase() {
        return (langData != null && langData.has("top1m_maxi")) ? "top1m" : "top";
    }
    private String coverageBase() { return everything ? "all" : popularBase(); }
    private String variantKey() { return coverageBase() + "_" + (pictures ? "maxi" : "nopic"); }

    private double sizeOf(String variant) {
        if (langData == null) return -1;
        JSONObject o = langData.optJSONObject(variant);
        return o == null ? -1 : o.optDouble("size", -1);
    }
    private static String gb(double s) { return s >= 0 ? String.format(Locale.US, "%.1fG", s) : "—"; }

    private void refresh() {
        if (getActivity() instanceof SetupLibraryActivity) {
            ((SetupLibraryActivity) getActivity()).setEverything(everything);
            ((SetupLibraryActivity) getActivity()).setPictures(pictures);
        }
        // toggle labels with live sizes
        label(covPopular, "Popular", sizeOf(popularBase() + "_" + (pictures ? "maxi" : "nopic")), !everything);
        label(covEverything, "Everything", sizeOf("all_" + (pictures ? "maxi" : "nopic")), everything);
        label(detPictures, "With pictures", sizeOf(coverageBase() + "_maxi"), pictures);
        label(detText, "Text only", sizeOf(coverageBase() + "_nopic"), !pictures);

        InstallationPlanner.calculateProjectedSize(requireContext(), tier(), true, lang, variantKey(),
                new InstallationPlanner.PlanResultListener() {
                    @Override
                    public void onCalculated(InstallationPlanner.StorageProjection p) {
                        if (!isAdded()) return;
                        wikiSize.setText(gb(p.kiwixSize));
                        applyBar(p.osSize, p.mapsSize + p.kiwixSize);
                        download.setText(String.format(Locale.US, "Download library · %.1f GB", p.totalSize));
                    }
                    @Override public void onError(String e) { }
                });
    }

    private void label(TextView t, String name, double size, boolean on) {
        t.setText(name + "  " + gb(size));
        t.setBackgroundResource(on ? R.drawable.k2go_primary_bg : R.drawable.k2go_card_bg);
        t.setTextColor(ContextCompat.getColor(requireContext(), on ? R.color.k2go_on_teal : R.color.k2go_ink));
    }

    private void applyBar(double systemGb, double picksGb) {
        double total = StorageInfo.totalGb();
        double used = StorageInfo.usedGb();
        double freeAfter = Math.max(0, StorageInfo.freeGb() - systemGb - picksGb);
        if (total <= 0) total = used + systemGb + picksGb + freeAfter + 0.01;
        bar.setWeightSum((float) total);
        setW(barUsed, (float) used);
        setW(barSystem, (float) systemGb);
        setW(barPicks, (float) picksGb);
        setW(barFree, (float) freeAfter);
        legend.setText(String.format(Locale.US, "Used %.1f · System %.1f · Picks %.1f · Free %.1f",
                used, systemGb, picksGb, freeAfter));
    }

    private void startDownload() {
        Intent i = new Intent(requireContext(), InstallService.class);
        i.setAction(InstallService.ACTION_START);
        i.putExtra(InstallService.EXTRA_TIER, tier().name());
        i.putExtra(InstallService.EXTRA_COMPANION, true);
        i.putExtra(InstallService.EXTRA_ARCH, SystemStateEvaluator.termuxArch(requireContext()));
        i.putExtra(InstallService.EXTRA_KIWIX_LANG, lang);
        i.putExtra(InstallService.EXTRA_KIWIX_VARIANT, variantKey());
        i.putExtra(InstallService.EXTRA_REINSTALL, false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            requireContext().startForegroundService(i);
        } else {
            requireContext().startService(i);
        }
        Toast.makeText(requireContext(), "Downloading your library…", Toast.LENGTH_LONG).show();
        startActivity(new Intent(requireContext(), LibraryActivity.class));
        requireActivity().finish();
    }

    private void setW(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        lp.weight = w; v.setLayoutParams(lp);
    }
    private void tintBg(View v, int colorRes) {
        v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes)));
    }
}
