package org.iiab.controller.redesign;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.MultiResourceGaugeView;
import org.iiab.controller.R;
import org.iiab.controller.SystemStateEvaluator;
import org.iiab.controller.applang.data.ContentLanguage;
import org.iiab.controller.install.presentation.InstallService;

/**
 * Step 2 Content — Option B (5-step map + arc Gauge). Guided: one source per step, a
 * projected-use gauge (reuses the old UI's MultiResourceGaugeView), Wikipedia coverage/detail
 * on step 1, Download on Review. Same install payload as Option A; picks shared via the Activity.
 */
public class Step2OptionBFragment extends Fragment {

    private static final String[] NAMES = {"Wikipedia", "Books", "Maps", "Courses", "Review"};
    private static final String[] INFO = {
            "", "Books — included with your edition.", "Maps — included with your edition.",
            "Courses — set up on the device after install.", "Review your library, then download."};

    private int step = 0;
    private String lang;
    private boolean everything = false, pictures = true;
    private double lastTotal = 0;

    private final TextView[] dots = new TextView[5];
    private TextView caption, legend, btnBack, btnNext, info;
    private MultiResourceGaugeView gauge;
    private TextView covPop, covEvery, detPic, detTxt;
    private View wikiBlock;

    private SetupLibraryActivity act() {
        return (getActivity() instanceof SetupLibraryActivity) ? (SetupLibraryActivity) getActivity() : null;
    }
    private InstallationPlanner.Tier tier() { return act() != null ? act().getSelectedTier() : InstallationPlanner.Tier.STANDARD; }
    private String variantKey() { return (everything ? "all" : "top1m") + "_" + (pictures ? "maxi" : "nopic"); }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_k2go_setup_step2b, c, false);
        lang = ContentLanguage.systemDefault();
        if (act() != null) { everything = act().isEverything(); pictures = act().isPictures(); }

        int[] ids = {R.id.k2go_sd0, R.id.k2go_sd1, R.id.k2go_sd2, R.id.k2go_sd3, R.id.k2go_sd4};
        for (int i = 0; i < 5; i++) dots[i] = v.findViewById(ids[i]);
        caption = v.findViewById(R.id.k2go_step_caption);
        gauge = v.findViewById(R.id.k2go_gauge);
        legend = v.findViewById(R.id.k2go_legend);
        info = v.findViewById(R.id.k2go_info_text);
        btnBack = v.findViewById(R.id.k2go_btn_back);
        btnNext = v.findViewById(R.id.k2go_btn_next);
        wikiBlock = v.findViewById(R.id.k2go_wiki_block);
        covPop = v.findViewById(R.id.k2go_cov_popular);
        covEvery = v.findViewById(R.id.k2go_cov_everything);
        detPic = v.findViewById(R.id.k2go_det_pictures);
        detTxt = v.findViewById(R.id.k2go_det_text);

        covPop.setOnClickListener(x -> { everything = false; persist(); refreshProjection(); });
        covEvery.setOnClickListener(x -> { everything = true; persist(); refreshProjection(); });
        detPic.setOnClickListener(x -> { pictures = true; persist(); refreshProjection(); });
        detTxt.setOnClickListener(x -> { pictures = false; persist(); refreshProjection(); });
        btnBack.setOnClickListener(x -> {
            if (step > 0) { step--; render(); }
            else if (getActivity() != null) getActivity().getSupportFragmentManager().popBackStack();
        });
        btnNext.setOnClickListener(x -> { if (step < 4) { step++; render(); } else startDownload(); });
        AbFlip.attach(v.findViewById(R.id.k2go_step2_title), () -> { if (act() != null) act().flipAbTest(); });

        render();
        refreshProjection();
        return v;
    }

    private void persist() {
        if (act() != null) { act().setEverything(everything); act().setPictures(pictures); }
        toggleLabels();
    }

    private void render() {
        for (int i = 0; i < 5; i++) {
            boolean on = i == step;
            dots[i].setBackgroundResource(on ? R.drawable.k2go_primary_bg : R.drawable.k2go_card_bg);
            dots[i].setTextColor(ContextCompat.getColor(requireContext(), on ? R.color.k2go_on_teal : R.color.k2go_muted));
        }
        caption.setText(NAMES[step]);
        wikiBlock.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        info.setVisibility(step == 0 ? View.GONE : View.VISIBLE);
        info.setText(INFO[step]);
        btnBack.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        toggleLabels();
        updateNextLabel();
    }

    private void toggleLabels() {
        hi(covPop, !everything); hi(covEvery, everything);
        hi(detPic, pictures); hi(detTxt, !pictures);
    }
    private void hi(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.k2go_primary_bg : R.drawable.k2go_card_bg);
        t.setTextColor(ContextCompat.getColor(requireContext(), on ? R.color.k2go_on_teal : R.color.k2go_ink));
    }

    private void updateNextLabel() {
        btnNext.setText(step == 4
                ? String.format(Locale.US, "Download library · %.1f GB", lastTotal)
                : "Next");
    }

    private void refreshProjection() {
        InstallationPlanner.calculateProjectedSize(requireContext(), tier(), true, lang, variantKey(),
                new InstallationPlanner.PlanResultListener() {
                    @Override
                    public void onCalculated(InstallationPlanner.StorageProjection p) {
                        if (!isAdded()) return;
                        lastTotal = p.totalSize;
                        updateGauge(p.osSize, p.mapsSize + p.kiwixSize);
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
        Intent i = new Intent(requireContext(), InstallService.class);
        i.setAction(InstallService.ACTION_START);
        i.putExtra(InstallService.EXTRA_TIER, tier().name());
        i.putExtra(InstallService.EXTRA_COMPANION, true);
        i.putExtra(InstallService.EXTRA_ARCH, SystemStateEvaluator.termuxArch(requireContext()));
        i.putExtra(InstallService.EXTRA_KIWIX_LANG, lang);
        i.putExtra(InstallService.EXTRA_KIWIX_VARIANT, variantKey());
        i.putExtra(InstallService.EXTRA_REINSTALL, false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) requireContext().startForegroundService(i);
        else requireContext().startService(i);
        Toast.makeText(requireContext(), "Downloading your library…", Toast.LENGTH_LONG).show();
        startActivity(new Intent(requireContext(), LibraryActivity.class));
        requireActivity().finish();
    }
}
