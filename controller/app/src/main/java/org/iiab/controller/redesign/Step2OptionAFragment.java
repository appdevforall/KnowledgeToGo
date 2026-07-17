package org.iiab.controller.redesign;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
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
 * Step 2 Content — Option A (Expandable + Bar). Collapsible sources: Wikipedia (real Kiwix
 * variant, skippable), Books (Coming soon, cosmetic skip), Maps (fixed 0.2 GB), Courses
 * (disabled). Bar + total reflect the picks; Download drives InstallService (companion +
 * Wikipedia variant). Skip everything -> just the system.
 */
public class Step2OptionAFragment extends Fragment {

    private static final double BOOKS_GB = 0.0, MAPS_GB = 0.2, WIKI_FALLBACK = 4.6;

    private boolean everything = false, pictures = true;
    private boolean wikiInc = true, booksInc = true;
    private final boolean mapsInc = true; // Maps content is fixed for now
    private String lang;
    private JSONObject langData;
    private double osGb = 1.4;

    private CheckBox wikiCheck, booksCheck, mapsCheck;
    private View wikiBody, booksBody, mapsBody;
    private TextView wikiChevron, booksChevron, mapsChevron, wikiSkip, booksSkip;
    private View wikiCard, booksCard;
    private TextView wikiSize, booksSize, legend, download;
    private TextView covPop, covEvery, detPic, detTxt;
    private LinearLayout bar;
    private View bU, bS, bP, bF;

    private SetupLibraryActivity act() {
        return (getActivity() instanceof SetupLibraryActivity) ? (SetupLibraryActivity) getActivity() : null;
    }
    private InstallationPlanner.Tier tier() { return act() != null ? act().getSelectedTier() : InstallationPlanner.Tier.STANDARD; }
    private String popularBase() { return (langData != null && langData.has("top1m_maxi")) ? "top1m" : "top"; }
    private String coverageBase() { return everything ? "all" : popularBase(); }
    private String variantKey() { return coverageBase() + "_" + (pictures ? "maxi" : "nopic"); }
    private double sizeOf(String variant) {
        if (langData == null) return -1;
        JSONObject o = langData.optJSONObject(variant);
        return o == null ? -1 : o.optDouble("size", -1);
    }
    private double wikiSizeGb() {
        double s = sizeOf(variantKey());
        return s >= 0 ? s : WIKI_FALLBACK;
    }
    private static String gb(double s) { return s >= 0 ? String.format(Locale.US, "%.1fG", s) : "—"; }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View v = inflater.inflate(R.layout.fragment_k2go_setup_step2a, c, false);
        lang = ContentLanguage.systemDefault();
        if (act() != null) { everything = act().isEverything(); pictures = act().isPictures(); }

        bar = v.findViewById(R.id.k2go_bar);
        bU = v.findViewById(R.id.k2go_bar_used); bS = v.findViewById(R.id.k2go_bar_system);
        bP = v.findViewById(R.id.k2go_bar_picks); bF = v.findViewById(R.id.k2go_bar_free);
        legend = v.findViewById(R.id.k2go_legend);
        download = v.findViewById(R.id.k2go_download);
        wikiCheck = v.findViewById(R.id.k2go_wiki_check); booksCheck = v.findViewById(R.id.k2go_books_check); mapsCheck = v.findViewById(R.id.k2go_maps_check);
        wikiBody = v.findViewById(R.id.k2go_wiki_body); booksBody = v.findViewById(R.id.k2go_books_body); mapsBody = v.findViewById(R.id.k2go_maps_body);
        wikiChevron = v.findViewById(R.id.k2go_wiki_chevron); booksChevron = v.findViewById(R.id.k2go_books_chevron); mapsChevron = v.findViewById(R.id.k2go_maps_chevron);
        wikiSkip = v.findViewById(R.id.k2go_wiki_skip); booksSkip = v.findViewById(R.id.k2go_books_skip);
        wikiCard = v.findViewById(R.id.k2go_wiki_card); booksCard = v.findViewById(R.id.k2go_books_card);
        wikiSize = v.findViewById(R.id.k2go_wiki_size); booksSize = v.findViewById(R.id.k2go_books_size);
        covPop = v.findViewById(R.id.k2go_cov_popular); covEvery = v.findViewById(R.id.k2go_cov_everything);
        detPic = v.findViewById(R.id.k2go_det_pictures); detTxt = v.findViewById(R.id.k2go_det_text);
        colorSeg(bU, R.color.k2go_muted); colorSeg(bS, R.color.k2go_teal); colorSeg(bP, R.color.k2go_leaf); colorSeg(bF, R.color.k2go_hairline);

        AbFlip.attach(v.findViewById(R.id.k2go_step2_title), () -> { if (act() != null) act().flipAbTest(); });

        v.findViewById(R.id.k2go_wiki_header).setOnClickListener(x -> toggle(wikiBody, wikiChevron));
        v.findViewById(R.id.k2go_books_header).setOnClickListener(x -> toggle(booksBody, booksChevron));
        v.findViewById(R.id.k2go_maps_header).setOnClickListener(x -> toggle(mapsBody, mapsChevron));

        wikiCheck.setOnClickListener(x -> setWiki(wikiCheck.isChecked()));
        wikiSkip.setOnClickListener(x -> setWiki(!wikiInc));
        booksCheck.setOnClickListener(x -> setBooks(booksCheck.isChecked()));
        booksSkip.setOnClickListener(x -> setBooks(!booksInc));
        mapsCheck.setOnClickListener(x -> mapsCheck.setChecked(true)); // fixed

        covPop.setOnClickListener(x -> { everything = false; persist(); refresh(); });
        covEvery.setOnClickListener(x -> { if (covEvery.isEnabled()) { everything = true; persist(); refresh(); } });
        detPic.setOnClickListener(x -> { pictures = true; persist(); refresh(); });
        detTxt.setOnClickListener(x -> { pictures = false; persist(); refresh(); });

        download.setOnClickListener(x -> startDownload());
        v.findViewById(R.id.k2go_step2a_back).setOnClickListener(x -> {
            if (getActivity() != null) getActivity().getSupportFragmentManager().popBackStack();
        });

        // system size (no network) and Kiwix catalog (cached) both drive the numbers.
        InstallationPlanner.calculateProjectedSize(requireContext(), tier(), false, lang, null,
                new InstallationPlanner.PlanResultListener() {
                    @Override public void onCalculated(InstallationPlanner.StorageProjection p) { if (isAdded()) { osGb = p.osSize; refresh(); } }
                    @Override public void onError(String e) { }
                });
        InstallationPlanner.getOrFetchCatalog(requireContext(), new InstallationPlanner.CacheListener() {
            @Override public void onReady(JSONObject catalog) {
                if (!isAdded()) return;
                langData = catalog.optJSONObject(lang);
                if (langData == null) langData = catalog.optJSONObject("en");
                refresh();
            }
            @Override public void onError(String e) { }
        });

        refresh();
        return v;
    }

    private void persist() { if (act() != null) { act().setEverything(everything); act().setPictures(pictures); } }

    private void toggle(View body, TextView chevron) {
        boolean show = body.getVisibility() != View.VISIBLE;
        body.setVisibility(show ? View.VISIBLE : View.GONE);
        chevron.setText(show ? "▾" : "▸");
    }

    private void setWiki(boolean inc) {
        wikiInc = inc;
        wikiCheck.setChecked(inc);
        wikiCard.setAlpha(inc ? 1f : 0.55f);
        wikiSkip.setText(inc ? R.string.k2go_skip : R.string.k2go_add);
        if (!inc) { wikiBody.setVisibility(View.GONE); wikiChevron.setText("▸"); }
        refresh();
    }
    private void setBooks(boolean inc) {
        booksInc = inc;
        booksCheck.setChecked(inc);
        booksCard.setAlpha(inc ? 1f : 0.55f);
        booksSkip.setText(inc ? R.string.k2go_skip : R.string.k2go_add);
        if (!inc) { booksBody.setVisibility(View.GONE); booksChevron.setText("▸"); }
        refresh();
    }

    private void refresh() {
        double free = StorageInfo.freeGb();
        // Wikipedia variant labels + "Everything won't fit" guard
        boolean everyFits = sizeOf("all_" + (pictures ? "maxi" : "nopic")) < 0
                || sizeOf("all_" + (pictures ? "maxi" : "nopic")) <= (free - osGb);
        label(covPop, "Popular", sizeOf(popularBase() + "_" + (pictures ? "maxi" : "nopic")), !everything, true);
        label(covEvery, "Everything", sizeOf("all_" + (pictures ? "maxi" : "nopic")), everything, everyFits);
        label(detPic, "With pictures", sizeOf(coverageBase() + "_maxi"), pictures, true);
        label(detTxt, "Text only", sizeOf(coverageBase() + "_nopic"), !pictures, true);
        wikiSize.setText(wikiInc ? gb(wikiSizeGb()) : getString(R.string.k2go_skipped));
        booksSize.setText("0 GB");

        double picks = (wikiInc ? wikiSizeGb() : 0) + (booksInc ? BOOKS_GB : 0) + (mapsInc ? MAPS_GB : 0);
        applyBar(osGb, picks);
        download.setText(String.format(Locale.US, "Download library · %.1f GB", osGb + picks));
    }

    private void label(TextView t, String name, double size, boolean on, boolean enabled) {
        t.setText(name + "  " + gb(size));
        t.setEnabled(enabled);
        t.setAlpha(enabled ? 1f : 0.4f);
        t.setBackgroundResource(on && enabled ? R.drawable.k2go_primary_bg : R.drawable.k2go_card_bg);
        t.setTextColor(ContextCompat.getColor(requireContext(), on && enabled ? R.color.k2go_on_teal : R.color.k2go_ink));
    }

    private void applyBar(double systemGb, double picksGb) {
        double total = StorageInfo.totalGb();
        double used = StorageInfo.usedGb();
        double freeAfter = Math.max(0, StorageInfo.freeGb() - systemGb - picksGb);
        if (total <= 0) total = used + systemGb + picksGb + freeAfter + 0.01;
        bar.setWeightSum((float) total);
        setW(bU, (float) used); setW(bS, (float) systemGb); setW(bP, (float) picksGb); setW(bF, (float) freeAfter);
        legend.setText(String.format(Locale.US, "Used %.1f · System %.1f · Your picks %.1f · Free %.1f", used, systemGb, picksGb, freeAfter));
    }

    private void startDownload() {
        boolean companion = wikiInc || booksInc || mapsInc;
        Intent i = new Intent(requireContext(), InstallService.class);
        i.setAction(InstallService.ACTION_START);
        i.putExtra(InstallService.EXTRA_TIER, tier().name());
        i.putExtra(InstallService.EXTRA_COMPANION, companion);
        i.putExtra(InstallService.EXTRA_ARCH, SystemStateEvaluator.termuxArch(requireContext()));
        i.putExtra(InstallService.EXTRA_KIWIX_LANG, lang);
        i.putExtra(InstallService.EXTRA_KIWIX_VARIANT, wikiInc ? variantKey() : null);
        i.putExtra(InstallService.EXTRA_REINSTALL, false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) requireContext().startForegroundService(i);
        else requireContext().startService(i);
        Toast.makeText(requireContext(), companion ? "Downloading your library…" : "Installing the system…", Toast.LENGTH_LONG).show();
        startActivity(new Intent(requireContext(), LibraryActivity.class));
        requireActivity().finish();
    }

    private void setW(View v, float w) { LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams(); lp.weight = w; v.setLayoutParams(lp); }
    private void colorSeg(View v, int colorRes) { v.setBackgroundColor(ContextCompat.getColor(requireContext(), colorRes)); }
}
