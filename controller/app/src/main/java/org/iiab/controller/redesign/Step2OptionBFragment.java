package org.iiab.controller.redesign;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.MultiResourceGaugeView;
import org.iiab.controller.R;
import org.iiab.controller.SystemStateEvaluator;
import org.iiab.controller.applang.data.ContentLanguage;
import org.iiab.controller.install.presentation.InstallService;
import org.json.JSONObject;

/**
 * Step 2 Content — Option B (5-step map + arc Gauge). Wikipedia has a full screen of its own
 * (step 1 of 5) hosting the shared {@link WikiVersionPicker} (List/Grouped, multi-select, min
 * one). Maps is fixed; Books/Courses WIP. v1 installs the primary of the selection.
 */
public class Step2OptionBFragment extends Fragment {

    private static final String[] NAMES = {"Wikipedia", "Books", "Maps", "Courses", "Review"};
    private static final String[] INFO = {
            "", "Books — coming soon (no content manager yet · 0 GB).", "Maps — included with your edition.",
            "Courses — set up on the device after install.", "Review your library, then download."};

    private int step = 0;
    private String lang;
    private JSONObject langData;
    private boolean wikiIncluded = true;
    private double lastTotal = 0, pOs = 1.4, pMaps = 0.2, pKiwix = 4.6;

    private Set<String> sel;
    private WikiVersionPicker picker;

    private final TextView[] dots = new TextView[5];
    private TextView caption, legend, btnBack, btnNext, btnSkip, info, wikiHint;
    private CheckBox wikiCheck;
    private MultiResourceGaugeView gauge;
    private View wikiBlock;
    private ViewGroup versions;

    private SetupLibraryActivity act() {
        return (getActivity() instanceof SetupLibraryActivity) ? (SetupLibraryActivity) getActivity() : null;
    }
    private InstallationPlanner.Tier tier() { return act() != null ? act().getSelectedTier() : InstallationPlanner.Tier.STANDARD; }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_k2go_setup_step2b, c, false);
        lang = ContentLanguage.systemDefault();
        sel = (act() != null) ? act().getWikiVariants() : new java.util.LinkedHashSet<>();
        if (act() != null) wikiIncluded = act().isWikiIncluded();

        int[] ids = {R.id.k2go_sd0, R.id.k2go_sd1, R.id.k2go_sd2, R.id.k2go_sd3, R.id.k2go_sd4};
        for (int i = 0; i < 5; i++) dots[i] = v.findViewById(ids[i]);
        caption = v.findViewById(R.id.k2go_step_caption);
        gauge = v.findViewById(R.id.k2go_gauge);
        legend = v.findViewById(R.id.k2go_legend);
        info = v.findViewById(R.id.k2go_info_text);
        btnBack = v.findViewById(R.id.k2go_btn_back);
        btnNext = v.findViewById(R.id.k2go_btn_next);
        btnSkip = v.findViewById(R.id.k2go_btn_skip);
        wikiCheck = v.findViewById(R.id.k2go_wiki_check);
        wikiBlock = v.findViewById(R.id.k2go_wiki_block);
        versions = v.findViewById(R.id.k2go_wiki_versions);
        wikiHint = v.findViewById(R.id.k2go_wiki_hint);

        picker = new WikiVersionPicker(requireContext(), versions, sel,
                act() != null ? act().getWikiView() : "list", () -> { updateHint(); refreshProjection(); });

        wikiCheck.setChecked(wikiIncluded);
        wikiCheck.setOnClickListener(x -> {
            wikiIncluded = wikiCheck.isChecked();
            persist();
            versions.setVisibility(wikiIncluded ? View.VISIBLE : View.GONE);
            updateHint();
            refreshProjection();
        });
        btnBack.setOnClickListener(x -> {
            if (step > 0) { step--; render(); }
            else if (getActivity() != null) getActivity().getSupportFragmentManager().popBackStack();
        });
        btnSkip.setOnClickListener(x -> {
            if (step == 0) {
                wikiIncluded = false;
                wikiCheck.setChecked(false);
                versions.setVisibility(View.GONE);
                persist();
                updateHint();
                refreshProjection();
            }
            if (step < 4) { step++; render(); }
        });
        btnNext.setOnClickListener(x -> {
            if (step == 0 && wikiIncluded && sel.isEmpty()) {
                updateHint();
                Toast.makeText(requireContext(), R.string.k2go_wiki_pick_one, Toast.LENGTH_SHORT).show();
                return;
            }
            if (step < 4) { step++; render(); } else startDownload();
        });
        AbFlip.attach(v.findViewById(R.id.k2go_step2_title), () -> { if (act() != null) act().flipAbTest(); });

        InstallationPlanner.getOrFetchCatalog(requireContext(), new InstallationPlanner.CacheListener() {
            @Override public void onReady(JSONObject catalog) {
                if (!isAdded()) return;
                langData = catalog.optJSONObject(lang);
                if (langData == null) langData = catalog.optJSONObject("en");
                picker.setLangData(langData);
                if (wikiIncluded) picker.selectDefaultIfEmpty();
                picker.render();
                updateHint();
                refreshProjection();
            }
            @Override public void onError(String e) { }
        });

        render();
        refreshProjection();
        return v;
    }

    private void persist() {
        if (act() != null) { act().setWikiIncluded(wikiIncluded); act().setWikiView(picker.getMode()); }
    }

    private void render() {
        for (int i = 0; i < 5; i++) {
            boolean on = i == step;
            dots[i].setBackgroundResource(on ? R.drawable.k2go_primary_bg : R.drawable.k2go_card_bg);
            dots[i].setTextColor(ContextCompat.getColor(requireContext(), on ? R.color.k2go_on_teal : R.color.k2go_muted));
        }
        caption.setText(NAMES[step]);
        wikiBlock.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        versions.setVisibility(step == 0 && wikiIncluded ? View.VISIBLE : View.GONE);
        info.setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        info.setText(INFO[step]);
        btnBack.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        btnSkip.setVisibility(step < 4 ? View.VISIBLE : View.GONE);
        updateHint();
        updateNextLabel();
    }

    private void updateHint() {
        wikiHint.setVisibility(step == 0 && wikiIncluded && sel.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateNextLabel() {
        btnNext.setText(step == 4 ? String.format(Locale.US, "Download library · %.1f GB", lastTotal) : "Next");
    }

    private void refreshProjection() {
        String variant = wikiIncluded ? WikiVariants.primary(sel) : null;
        InstallationPlanner.calculateProjectedSize(requireContext(), tier(), true, lang, variant,
                new InstallationPlanner.PlanResultListener() {
                    @Override
                    public void onCalculated(InstallationPlanner.StorageProjection p) {
                        if (!isAdded()) return;
                        pOs = p.osSize; pMaps = p.mapsSize; pKiwix = p.kiwixSize;
                        double picks = ((wikiIncluded && variant != null) ? pKiwix : 0) + pMaps;
                        lastTotal = pOs + picks;
                        updateGauge(pOs, picks);
                        updateNextLabel();
                    }
                    @Override public void onError(String e) { }
                });
    }

    private void updateGauge(double systemGb, double picksGb) {
        double total = StorageInfo.totalGb();
        double used = StorageInfo.usedGb();
        if (total <= 0) total = used + systemGb + picksGb + 0.01;
        double freeAfter = Math.max(0, total - used - systemGb - picksGb);
        List<MultiResourceGaugeView.Segment> segs = new ArrayList<>();
        segs.add(new MultiResourceGaugeView.Segment((float) (used / total * 100f), ContextCompat.getColor(requireContext(), R.color.k2go_muted)));
        segs.add(new MultiResourceGaugeView.Segment((float) (systemGb / total * 100f), ContextCompat.getColor(requireContext(), R.color.k2go_teal)));
        segs.add(new MultiResourceGaugeView.Segment((float) (picksGb / total * 100f), ContextCompat.getColor(requireContext(), R.color.k2go_leaf)));
        if (gauge != null) {
            gauge.updateData(segs, String.format(Locale.US, "%.1fG", used + systemGb + picksGb),
                    ContextCompat.getColor(requireContext(), R.color.k2go_ink), "projected use", "");
        }
        legend.setText(String.format(Locale.US, "Used %.1f · System %.1f · Picks %.1f · Free %.1f",
                used, systemGb, picksGb, freeAfter));
    }

    private void startDownload() {
        String variant = wikiIncluded ? WikiVariants.primary(sel) : null;
        Intent i = new Intent(requireContext(), InstallService.class);
        i.setAction(InstallService.ACTION_START);
        i.putExtra(InstallService.EXTRA_TIER, tier().name());
        i.putExtra(InstallService.EXTRA_COMPANION, true);
        i.putExtra(InstallService.EXTRA_ARCH, SystemStateEvaluator.termuxArch(requireContext()));
        i.putExtra(InstallService.EXTRA_KIWIX_LANG, lang);
        i.putExtra(InstallService.EXTRA_KIWIX_VARIANT, variant);
        i.putExtra(InstallService.EXTRA_REINSTALL, false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) requireContext().startForegroundService(i);
        else requireContext().startService(i);
        Toast.makeText(requireContext(), "Downloading your library…", Toast.LENGTH_LONG).show();
        startActivity(new Intent(requireContext(), LibraryActivity.class));
        requireActivity().finish();
    }
}
