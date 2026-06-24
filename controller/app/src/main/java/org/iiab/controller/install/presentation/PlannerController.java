/*
 * ============================================================================
 * Name        : PlannerController.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Install "planner" presentation logic carved out of DeployFragment
 *               (strangler-fig): tier selection, the module checkbox grid, the
 *               storage-size projection (RootfsViewModel + observe) and the Kiwix
 *               settings dialog. Being a non-Fragment class, it removes this large
 *               call graph -- including the LiveData observe() -- from the Fragment
 *               that the androidx lint detectors walk. No behaviour change.
 *               See controller/docs/TECH_DEBT_PLAN.md (ADFA-4434).
 * ============================================================================
 */
package org.iiab.controller.install.presentation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.snackbar.Snackbar;

import org.iiab.controller.InstallationPlanner;
import org.iiab.controller.MainActivity;
import org.iiab.controller.ModuleRegistry;
import org.iiab.controller.MultiResourceGaugeView;
import org.iiab.controller.R;
import org.iiab.controller.rootfs.domain.RootfsAbi;
import org.iiab.controller.rootfs.domain.RootfsTier;
import org.iiab.controller.rootfs.presentation.RootfsUiState;
import org.iiab.controller.rootfs.presentation.RootfsViewModel;
import org.iiab.controller.rootfs.presentation.RootfsViewModelFactory;
import org.iiab.controller.util.ByteFormatter;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class PlannerController {

    private final Fragment fragment;
    private final PlannerHost host;

    // Borrowed views (declared on the Fragment; set in bind()).
    private LinearLayout rolesContainer;
    private MultiResourceGaugeView storageGauge;
    private Button btnTierBasic;
    private Button btnTierStandard;
    private Button btnTierFull;
    private TextView txtLegendIiab;
    private TextView txtLegendMaps;
    private TextView txtLegendKiwix;
    private TextView txtLegendFree;
    private TextView txtOfflineEstimate;
    private ImageButton btnKiwixSettings;
    private CheckBox chkCompanionData;

    // Owned by the planner (nothing else uses it).
    private RootfsViewModel rootfsViewModel;

    public PlannerController(Fragment fragment, PlannerHost host) {
        this.fragment = fragment;
        this.host = host;
    }

    /** Wire the tier buttons, the size-projection observer and the Kiwix dialog. */
    public void bind(LinearLayout rolesContainer, MultiResourceGaugeView storageGauge,
                     Button btnTierBasic, Button btnTierStandard, Button btnTierFull,
                     TextView txtLegendIiab, TextView txtLegendMaps, TextView txtLegendKiwix,
                     TextView txtLegendFree, TextView txtOfflineEstimate,
                     ImageButton btnKiwixSettings, CheckBox chkCompanionData) {
        this.rolesContainer = rolesContainer;
        this.storageGauge = storageGauge;
        this.btnTierBasic = btnTierBasic;
        this.btnTierStandard = btnTierStandard;
        this.btnTierFull = btnTierFull;
        this.txtLegendIiab = txtLegendIiab;
        this.txtLegendMaps = txtLegendMaps;
        this.txtLegendKiwix = txtLegendKiwix;
        this.txtLegendFree = txtLegendFree;
        this.txtOfflineEstimate = txtOfflineEstimate;
        this.btnKiwixSettings = btnKiwixSettings;
        this.chkCompanionData = chkCompanionData;
        setupPlannerListeners();
    }

    public void createModulesGrid() {
        if (rolesContainer == null || fragment.getContext() == null) return;
        rolesContainer.removeAllViews();
        host.moduleCheckboxes().clear();

        boolean isServerRunning = false;
        if (fragment.getActivity() instanceof MainActivity) {
            isServerRunning = ((MainActivity) fragment.getActivity()).isServerAlive;
        }

        String termuxArch = host.getTermuxArch();
        boolean is64Bit = termuxArch != null && termuxArch.contains("64");

        List<ModuleRegistry.IiabModule> activeModules = new ArrayList<>();
        for (ModuleRegistry.IiabModule module : ModuleRegistry.MASTER_ROSTER) {
            if (module.requires64Bit && !is64Bit) continue;
            activeModules.add(module);
        }

        int numCols = 3;
        int numRows = (int) Math.ceil((double) activeModules.size() / numCols);
        int ledSizePx = (int) (12 * fragment.getResources().getDisplayMetrics().density);

        for (int row = 0; row < numRows; row++) {
            LinearLayout rowLayout = new LinearLayout(fragment.requireContext());
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rowLayout.setBaselineAligned(false);
            rowLayout.setWeightSum(numCols);
            rowLayout.setPadding(0, 0, 0, 16);

            for (int col = 0; col < numCols; col++) {
                int index = (row * numCols) + col;
                LinearLayout cell = new LinearLayout(fragment.requireContext());
                LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                int margin = 10;
                if (col == 0) cellParams.setMargins(0, 0, margin, 0);
                else if (col == 1) cellParams.setMargins(margin / 2, 0, margin / 2, 0);
                else cellParams.setMargins(margin, 0, 0, 0);

                cell.setLayoutParams(cellParams);

                if (index < activeModules.size()) {
                    ModuleRegistry.IiabModule currentMod = activeModules.get(index);

                    cell.setOrientation(LinearLayout.HORIZONTAL);
                    cell.setBackgroundResource(R.drawable.rounded_button);
                    cell.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.dash_module_bg)));
                    cell.setPadding(16, 28, 16, 28);
                    cell.setGravity(android.view.Gravity.CENTER);

                    int boxSizePx = (int) (24 * fragment.getResources().getDisplayMetrics().density);
                    android.widget.FrameLayout indicatorContainer = new android.widget.FrameLayout(fragment.requireContext());
                    LinearLayout.LayoutParams indParams = new LinearLayout.LayoutParams(boxSizePx, boxSizePx);
                    indicatorContainer.setLayoutParams(indParams);

                    View led = new View(fragment.requireContext());
                    android.widget.FrameLayout.LayoutParams ledParams = new android.widget.FrameLayout.LayoutParams(ledSizePx, ledSizePx, android.view.Gravity.CENTER);
                    led.setLayoutParams(ledParams);
                    led.setBackgroundResource(R.drawable.led_off);

                    CheckBox checkBox = new CheckBox(fragment.requireContext());
                    checkBox.setScaleX(0.85f);
                    checkBox.setScaleY(0.85f);
                    checkBox.setPadding(0, 0, 0, 0);
                    android.widget.FrameLayout.LayoutParams cbParams = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER);
                    checkBox.setLayoutParams(cbParams);
                    checkBox.setVisibility(View.GONE);

                    if (isServerRunning) {
                        checkBox.setEnabled(false);
                        cell.setAlpha(0.6f);
                    } else {
                        checkBox.setEnabled(true);
                        cell.setAlpha(1.0f);
                    }

                    indicatorContainer.addView(led);
                    indicatorContainer.addView(checkBox);

                    TextView name = new TextView(fragment.requireContext());
                    name.setText(fragment.getString(currentMod.nameResId));
                    name.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_primary));
                    name.setTextSize(12f);
                    name.setSingleLine(true);
                    LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    textParams.setMargins(16, 0, 0, 0);
                    name.setLayoutParams(textParams);

                    cell.addView(indicatorContainer);
                    cell.addView(name);
                    cell.setTag(currentMod);
                } else {
                    cell.setVisibility(View.INVISIBLE);
                }
                rowLayout.addView(cell);
            }
            rolesContainer.addView(rowLayout);
        }
    }

    private void setupPlannerListeners() {
        // Presentation layer: the projection UI consumes the OS rootfs size from
        // RootfsViewModel (live, with offline fallback) instead of having
        // InstallationPlanner resolve it. The observer completes each projection
        // once the size is resolved.
        rootfsViewModel = new ViewModelProvider(fragment, new RootfsViewModelFactory()).get(RootfsViewModel.class);
        rootfsViewModel.state().observe(fragment.getViewLifecycleOwner(), this::onRootfsSizeResolved);

        btnTierBasic.setAlpha(0.5f);
        btnTierBasic.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_success)));
        btnTierStandard.setAlpha(0.5f);
        btnTierStandard.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.btn_neutral)));
        btnTierFull.setAlpha(0.5f);
        btnTierFull.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.btn_neutral)));

        View.OnClickListener tierClickListener = v -> {
            btnTierBasic.setAlpha(1.0f);
            btnTierStandard.setAlpha(1.0f);
            btnTierFull.setAlpha(1.0f);
            btnTierBasic.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.btn_neutral)));
            btnTierStandard.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.btn_neutral)));
            btnTierFull.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.btn_neutral)));
            v.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(fragment.requireContext(), R.color.status_success)));

            if (v.getId() == R.id.btn_tier_basic) host.setSelectedTier(InstallationPlanner.Tier.BASIC);
            else if (v.getId() == R.id.btn_tier_standard)
                host.setSelectedTier(InstallationPlanner.Tier.STANDARD);
            else if (v.getId() == R.id.btn_tier_full) host.setSelectedTier(InstallationPlanner.Tier.FULL);

            host.setOverrideKiwixVariant(null);
            recalculateProjection();
        };

        btnTierBasic.setOnClickListener(tierClickListener);
        btnTierStandard.setOnClickListener(tierClickListener);
        btnTierFull.setOnClickListener(tierClickListener);

        chkCompanionData.setOnCheckedChangeListener((buttonView, isChecked) -> {
            btnKiwixSettings.setColorFilter(ContextCompat.getColor(fragment.requireContext(), isChecked ? R.color.colorAccent : R.color.dash_text_secondary));
            recalculateProjection();
        });

        btnKiwixSettings.setOnClickListener(v -> showKiwixSettingsDialog());
        recalculateProjection();
    }

    private void recalculateProjection() {
        InstallationPlanner.Tier evalTier = (host.getSelectedTier() != null) ? host.getSelectedTier() : InstallationPlanner.Tier.BASIC;
        // Ask the presentation layer for the OS rootfs size. onRootfsSizeResolved()
        // (registered as an observer in setupPlannerListeners) reacts and finishes
        // the projection with the resolved size.
        if (rootfsViewModel != null) {
            // When we already know we're offline, skip the live fetch (avoids the ~6s
            // network timeout) and go straight to the hardcoded fallback size.
            rootfsViewModel.load(toRootfsTier(evalTier), detectRootfsAbi(), host.hasInternet());
        }
    }

    private void onRootfsSizeResolved(RootfsUiState rootfsState) {
        if (!fragment.isAdded() || rootfsState == null) return;
        if (rootfsState.status == RootfsUiState.Status.LOADING) return;

        final double osGiB = (rootfsState.rootfs != null)
                ? ByteFormatter.toGiB(rootfsState.rootfs.sizeBytes())
                : 0.0;

        // Show the "estimated (offline)" caption whenever the size is a fallback
        // (no live value), so the user knows the projection isn't server-confirmed.
        if (txtOfflineEstimate != null) {
            txtOfflineEstimate.setVisibility(rootfsState.live ? View.GONE : View.VISIBLE);
        }

        android.content.SharedPreferences prefs = fragment.requireContext().getSharedPreferences(fragment.getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        String targetLang = (host.getOverrideKiwixLang() != null) ? host.getOverrideKiwixLang() : prefs.getString("selected_lang_minimal", "en");
        InstallationPlanner.Tier evalTier = (host.getSelectedTier() != null) ? host.getSelectedTier() : InstallationPlanner.Tier.BASIC;

        InstallationPlanner.calculateProjectedSize(fragment.requireContext(), evalTier, chkCompanionData.isChecked(), targetLang, host.getOverrideKiwixVariant(), osGiB, new InstallationPlanner.PlanResultListener() {
            @Override
            public void onCalculated(InstallationPlanner.StorageProjection projection) {
                if (!fragment.isAdded()) return;

                File path = android.os.Environment.getDataDirectory();
                double freeSpaceGb = path.getFreeSpace() / (1024.0 * 1024.0 * 1024.0);
                double totalSpaceGb = path.getTotalSpace() / (1024.0 * 1024.0 * 1024.0);
                double usedSpaceGb = totalSpaceGb - freeSpaceGb;

                double pOs = (host.getSelectedTier() == null) ? 0.0 : projection.osSize;
                double pMaps = (host.getSelectedTier() == null) ? 0.0 : projection.mapsSize;
                // --- DETECT ARCHITECTURE ---
                String arch = host.getTermuxArch();
                boolean is64Bit = arch != null && arch.contains("64");

                // --- FORCE KIWIX TO ZERO IN 32-BITS (change if kiwix gets support for 32bits somehow) ---
                double pKiwix = (host.getSelectedTier() == null || !is64Bit) ? 0.0 : projection.kiwixSize;
                double pTotal = pOs + pMaps + pKiwix;

                host.setStorageSafe(pTotal <= (freeSpaceGb - 5.0));

                if (txtLegendIiab != null)
                    txtLegendIiab.setText(String.format(java.util.Locale.US, "%.1fG", pOs));
                if (txtLegendMaps != null)
                    txtLegendMaps.setText(String.format(java.util.Locale.US, "%.1fG", pMaps));
                if (txtLegendKiwix != null)
                    txtLegendKiwix.setText(String.format(java.util.Locale.US, "%.1fG", pKiwix));

                TextView lblWiki = fragment.getView().findViewById(R.id.txt_legend_kiwix).getRootView().findViewWithTag("label_kiwix");
                if (lblWiki == null && txtLegendKiwix != null) {
                    ViewGroup parent = (ViewGroup) txtLegendKiwix.getParent();
                    lblWiki = (TextView) parent.getChildAt(1);
                }
                // --- HIDE UI OF KIWIX IF IT IS 32-BITS ---
                if (!is64Bit) {
                    // We force "N/A" in the size text
                    if (txtLegendKiwix != null) {
                        txtLegendKiwix.setText(fragment.getString(R.string.install_msg_backup_na)); // Use the "N/A" string you already have
                        txtLegendKiwix.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_secondary));
                    }

                    if (lblWiki != null) {
                        lblWiki.setText(fragment.getString(R.string.install_legend_wiki_plain));
                        // We apply gray
                        lblWiki.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_secondary));
                        // (Optional) We can cross it out to make it clear that it is disabled
                        lblWiki.setPaintFlags(lblWiki.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                    }

                    // We hide the gear so it cannot interact
                    if (btnKiwixSettings != null) btnKiwixSettings.setVisibility(View.GONE);
                } else if (lblWiki != null) {
                    // We clean the strikethrough (in case the view is recycled)
                    lblWiki.setPaintFlags(lblWiki.getPaintFlags() & (~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG));

                    // Normal logic for 64-bit
                    if (chkCompanionData.isChecked()) {
                        lblWiki.setText(fragment.getString(R.string.install_legend_wiki_lang, projection.resolvedLang.toUpperCase()));
                        lblWiki.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.status_info));
                    } else {
                        lblWiki.setText(fragment.getString(R.string.install_legend_wiki_plain));
                        lblWiki.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_secondary));
                    }
                }

                if (txtLegendFree != null) {
                    if (host.isStorageSafe()) {
                        txtLegendFree.setText(String.format(java.util.Locale.US, "%.1fG", (freeSpaceGb - pTotal)));
                        txtLegendFree.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_inverted));
                    } else {
                        txtLegendFree.setText("OVERLOAD");
                        txtLegendFree.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.status_danger));
                    }
                }

                if (storageGauge != null) {
                    List<MultiResourceGaugeView.Segment> segments = new ArrayList<>();
                    float otherUsedPct = (totalSpaceGb > 0) ? (float) (usedSpaceGb / totalSpaceGb) * 100f : 0f;
                    float osPct = (totalSpaceGb > 0) ? (float) (pOs / totalSpaceGb) * 100f : 0f;
                    float mapsPct = (totalSpaceGb > 0) ? (float) (pMaps / totalSpaceGb) * 100f : 0f;
                    float kiwixPct = (totalSpaceGb > 0) ? (float) (pKiwix / totalSpaceGb) * 100f : 0f;
                    float totalDrawn = 0f;

                    if (otherUsedPct > 0) {
                        float draw = Math.min(otherUsedPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, ContextCompat.getColor(fragment.requireContext(), R.color.chart_track)));
                        totalDrawn += draw;
                    }
                    if (osPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(osPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, ContextCompat.getColor(fragment.requireContext(), R.color.chart_os)));
                        totalDrawn += draw;
                    }
                    if (mapsPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(mapsPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, ContextCompat.getColor(fragment.requireContext(), R.color.chart_maps)));
                        totalDrawn += draw;
                    }
                    if (kiwixPct > 0 && totalDrawn < 100f) {
                        float draw = Math.min(kiwixPct, 100f - totalDrawn);
                        segments.add(new MultiResourceGaugeView.Segment(draw, ContextCompat.getColor(fragment.requireContext(), R.color.chart_wiki)));
                    }

                    int centerColor = (host.getSelectedTier() == null || host.isStorageSafe()) ? ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_inverted) : ContextCompat.getColor(fragment.requireContext(), R.color.status_danger);
                    storageGauge.updateData(segments, String.format(java.util.Locale.US, "%.1fG", pTotal), centerColor, "Projected", "Storage");
                }

                if (fragment.getActivity() != null)
                    fragment.getActivity().runOnUiThread(() -> host.updateDynamicButtons());
            }

            @Override
            public void onError(String error) {
                if (fragment.isAdded() && txtLegendFree != null) txtLegendFree.setText("Error");
            }
        });
    }

    private void showKiwixSettingsDialog() {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(fragment.requireContext()).create();
        View view = fragment.getLayoutInflater().inflate(R.layout.dialog_install_planner_settings, null);
        dialog.setView(view);

        android.widget.Spinner spinnerLang = view.findViewById(R.id.spinner_kiwix_lang);
        Button btnWipe = view.findViewById(R.id.btn_wipe_cache);
        Button btnSelect = view.findViewById(R.id.btn_select_variant);
        android.widget.RadioGroup rgVariants = view.findViewById(R.id.rg_kiwix_variants);

        btnWipe.setOnClickListener(v -> {
            InstallationPlanner.wipeCache(fragment.requireContext());
            rgVariants.removeAllViews();
            spinnerLang.setAdapter(null);
            host.setOverrideKiwixVariant(null);
            Snackbar.make(fragment.getView(), R.string.kiwix_cache_wiped, Snackbar.LENGTH_SHORT).show();
        });

        InstallationPlanner.getOrFetchCatalog(fragment.requireContext(), new InstallationPlanner.CacheListener() {
            @Override
            public void onReady(JSONObject catalog) {
                if (!fragment.isAdded()) return;

                List<String> langKeys = new ArrayList<>();
                java.util.Iterator<String> keys = catalog.keys();
                while (keys.hasNext()) langKeys.add(keys.next());
                java.util.Collections.sort(langKeys);

                List<String> displayNames = new ArrayList<>();
                int selectedIndex = 0;
                android.content.SharedPreferences prefs = fragment.requireContext().getSharedPreferences(fragment.getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
                String currentTarget = (host.getOverrideKiwixLang() != null) ? host.getOverrideKiwixLang() : prefs.getString("selected_lang_minimal", "en");

                for (int i = 0; i < langKeys.size(); i++) {
                    String code = langKeys.get(i);
                    java.util.Locale loc = new java.util.Locale(code);
                    String name = loc.getDisplayLanguage(loc);
                    displayNames.add(name.substring(0, 1).toUpperCase() + name.substring(1) + " / " + loc.getDisplayLanguage(java.util.Locale.US));
                    if (code.equals(currentTarget)) selectedIndex = i;
                }

                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(fragment.requireContext(), android.R.layout.simple_spinner_dropdown_item, displayNames);
                spinnerLang.setAdapter(adapter);
                spinnerLang.setSelection(selectedIndex);

                spinnerLang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                        String selectedCode = langKeys.get(position);
                        rgVariants.removeAllViews();

                        JSONObject variants = catalog.optJSONObject(selectedCode);
                        if (variants != null) {
                            java.util.Iterator<String> vKeys = variants.keys();
                            while (vKeys.hasNext()) {
                                String vk = vKeys.next();
                                JSONObject vData = variants.optJSONObject(vk);
                                double size = (vData != null) ? vData.optDouble("size", 0.0) : 0.0;

                                android.widget.RadioButton rb = new android.widget.RadioButton(fragment.requireContext());
                                rb.setId(View.generateViewId());
                                rb.setText(String.format(java.util.Locale.US, "%-22s %5.1f GB", vk, size));
                                rb.setTextColor(ContextCompat.getColor(fragment.requireContext(), R.color.dash_text_primary));
                                rb.setTypeface(Typeface.MONOSPACE);
                                rb.setTag(vk);
                                rgVariants.addView(rb);

                                if (vk.equals(host.getOverrideKiwixVariant())) rb.setChecked(true);
                            }
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });

                btnSelect.setOnClickListener(v -> {
                    int checkedId = rgVariants.getCheckedRadioButtonId();
                    if (checkedId != -1) {
                        android.widget.RadioButton rb = rgVariants.findViewById(checkedId);
                        host.setOverrideKiwixVariant((String) rb.getTag());
                        host.setOverrideKiwixLang(langKeys.get(spinnerLang.getSelectedItemPosition()));
                        recalculateProjection();
                        dialog.dismiss();
                    } else {
                        Snackbar.make(fragment.getView(), R.string.kiwix_select_variant_error, Snackbar.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                if (fragment.isAdded())
                    Snackbar.make(fragment.getView(), fragment.getString(R.string.kiwix_catalog_error, error), Snackbar.LENGTH_LONG).show();
            }
        });
        dialog.show();
    }

    private RootfsTier toRootfsTier(InstallationPlanner.Tier tier) {
        switch (tier) {
            case STANDARD:
                return RootfsTier.STANDARD;
            case FULL:
                return RootfsTier.FULL;
            case BASIC:
            default:
                return RootfsTier.BASIC;
        }
    }

    private RootfsAbi detectRootfsAbi() {
        String arch = host.getTermuxArch();
        return (arch != null && arch.contains("64")) ? RootfsAbi.ARM64_V8A : RootfsAbi.ARMEABI_V7A;
    }
}
