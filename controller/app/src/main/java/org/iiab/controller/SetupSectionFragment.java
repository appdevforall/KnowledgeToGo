package org.iiab.controller;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import org.iiab.controller.applang.data.AppLocaleController;
import org.iiab.controller.applang.domain.AppLanguage;
import org.iiab.controller.applang.domain.LocaleMatcher;
import org.iiab.controller.applang.domain.SupportedAppLanguages;
import org.iiab.controller.delivery.data.AnalyticsConsent;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Permissions + language section, shared by the first-run wizard and the settings shell.
 * Gated by {@code ARG_WIZARD}: WIZARD shows a gated "Continue" (enabled only once all
 * required permissions are granted; the host blocks Back); SETTINGS hides Continue,
 * persists the language on selection, and allows free navigation.
 */
public class SetupSectionFragment extends Fragment {

    private static final String ARG_WIZARD = "wizard";
    private static final String DELIVERY_PREFS = "iiab_delivery";
    private static final String KEY_ENROLLMENT_SHOWN = "analytics_enrollment_shown";

    public static SetupSectionFragment newInstance(boolean wizard) {
        SetupSectionFragment f = new SetupSectionFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_WIZARD, wizard);
        f.setArguments(b);
        return f;
    }

    private boolean wizard;

    private SwitchCompat switchNotif, switchStorage, switchBattery, switchOverlay;
    private Button btnContinue;
    private Button btnManageAll;
    private Spinner spinnerLanguage;
    private Spinner spinnerAppLanguage;
    private boolean contentSelectionInitialized = false;

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> storageLauncher;
    private ActivityResultLauncher<Intent> batteryLauncher;
    private ActivityResultLauncher<Intent> overlayLauncher;
    private ActivityResultLauncher<Intent> notifLauncher;

    private static class LocaleItem {
        final Locale locale;
        final String displayName;

        LocaleItem(Locale locale, String displayName) {
            this.locale = locale;
            this.displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wizard = getArguments() == null || getArguments().getBoolean(ARG_WIZARD, true);

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), isGranted -> checkAllPermissions());
        notifLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> checkAllPermissions());
        storageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> checkAllPermissions());
        batteryLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> checkAllPermissions());
        overlayLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> checkAllPermissions());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_setup_section, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);

        TextView welcomeText = v.findViewById(R.id.setup_welcome_text);
        welcomeText.setText(getString(R.string.setup_welcome, getString(R.string.app_name)));

        switchNotif = v.findViewById(R.id.switch_perm_notifications);
        switchStorage = v.findViewById(R.id.switch_perm_storage);
        switchBattery = v.findViewById(R.id.switch_perm_battery);
        switchOverlay = v.findViewById(R.id.switch_perm_overlay);
        btnContinue = v.findViewById(R.id.btn_setup_continue);
        btnManageAll = v.findViewById(R.id.btn_manage_all);
        spinnerLanguage = v.findViewById(R.id.spinner_language);
        spinnerAppLanguage = v.findViewById(R.id.spinner_app_language);
        // Don't let the spinners restore a stale numeric position across activity
        // recreation: the option lists are re-sorted per UI locale, so a restored index
        // would point at a different language. Our setSelection(...) is the source of
        // truth on every (re)creation. See ADFA-4304.
        spinnerLanguage.setSaveEnabled(false);
        spinnerAppLanguage.setSaveEnabled(false);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            switchNotif.setVisibility(View.GONE);
        }

        setupListeners();
        setupAppLanguageSpinner();
        setupLanguageSpinner();
        setupLanguageSectionToggle(v);
        checkAllPermissions();

        if (wizard) {
            btnContinue.setOnClickListener(view -> {
                persistSelectedLanguage();
                maybeShowAnalyticsEnrollment();
            });
        } else {
            btnContinue.setVisibility(View.GONE);
            spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (!contentSelectionInitialized) {
                        // Skip the initial programmatic selection so recreating the
                        // activity (e.g. after an App Language change) never rewrites
                        // the stored content language. Only real user picks persist.
                        contentSelectionInitialized = true;
                        return;
                    }
                    persistSelectedLanguage();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        checkAllPermissions();
    }

    private void persistSelectedLanguage() {
        LocaleItem selectedItem = (LocaleItem) spinnerLanguage.getSelectedItem();
        if (selectedItem == null) {
            return;
        }
        Locale loc = selectedItem.locale;
        SharedPreferences prefs = requireContext().getSharedPreferences(
                getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        prefs.edit()
                .putString("selected_lang_minimal", loc.getLanguage())
                .putString("selected_lang_simple", loc.getLanguage() + "-" + loc.getCountry())
                .putString("selected_lang_full", loc.getLanguage() + "_" + loc.getCountry() + ".UTF-8")
                .apply();
    }

    /**
     * One-time analytics enrollment during first-run setup. Shows a clear, no-dark-pattern
     * choice to share strictly anonymous, operational usage stats; either choice records
     * the consent and proceeds. Shown at most once (guarded by a flag).
     */
    private void maybeShowAnalyticsEnrollment() {
        SharedPreferences delivery = requireContext().getSharedPreferences(
                DELIVERY_PREFS, Context.MODE_PRIVATE);
        if (delivery.getBoolean(KEY_ENROLLMENT_SHOWN, false)) {
            completeSetup();
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.analytics_enroll_title)
                .setMessage(getString(R.string.analytics_enroll_body, getString(R.string.app_name)))
                .setCancelable(false)
                .setPositiveButton(R.string.analytics_enroll_accept, (d, w) -> {
                    AnalyticsConsent.setEnabled(requireContext(), true);
                    finishEnrollment(delivery);
                })
                .setNegativeButton(R.string.analytics_enroll_decline, (d, w) -> {
                    AnalyticsConsent.setEnabled(requireContext(), false);
                    finishEnrollment(delivery);
                })
                .show();
    }

    private void finishEnrollment(SharedPreferences delivery) {
        delivery.edit().putBoolean(KEY_ENROLLMENT_SHOWN, true).apply();
        completeSetup();
    }

    private void completeSetup() {
        SharedPreferences prefs = requireContext().getSharedPreferences(
                getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        prefs.edit().putBoolean(getString(R.string.pref_key_setup_complete), true).apply();
        startActivity(new Intent(requireContext(), MainActivity.class));
        requireActivity().finish();
    }

    /**
     * Collapsible "Language" section (ADFA-4304): collapsed by default so first-run setup
     * stays short. Opening it reveals the App Language + Content Language selectors; the
     * defaults already apply if the operator never opens it.
     */
    private void setupLanguageSectionToggle(View root) {
        View header = root.findViewById(R.id.language_header);
        View options = root.findViewById(R.id.language_options);
        TextView chevron = root.findViewById(R.id.language_chevron);
        chevron.setText("\u25B8"); // collapsed indicator
        header.setOnClickListener(x -> {
            boolean expand = options.getVisibility() != View.VISIBLE;
            options.setVisibility(expand ? View.VISIBLE : View.GONE);
            chevron.setText(expand ? "\u25BE" : "\u25B8");
        });
    }

    /**
     * App UI language selector (ADFA-4304): lets the operator show the app in any shipped
     * language regardless of the phone's locale. "System default" clears the override.
     * Thin — the actual locale switch lives in {@link AppLocaleController}.
     */
    private void setupAppLanguageSpinner() {
        List<AppLanguage> languages =
                SupportedAppLanguages.all(getString(R.string.setup_app_lang_system));
        ArrayAdapter<AppLanguage> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAppLanguage.setAdapter(adapter);
        spinnerAppLanguage.setSelection(
                SupportedAppLanguages.indexOfTag(languages, AppLocaleController.currentTag()), false);

        spinnerAppLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String tag = ((AppLanguage) parent.getItemAtPosition(position)).tag();
                // No-op on the initial/programmatic selection; only apply a real change
                // (applying recreates the activity in the chosen language).
                if (!tag.equals(AppLocaleController.currentTag())) {
                    AppLocaleController.apply(tag);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setupLanguageSpinner() {
        List<LocaleItem> localeItems = new ArrayList<>();
        Set<String> addedNames = new HashSet<>();

        for (Locale locale : Locale.getAvailableLocales()) {
            if (!locale.getLanguage().isEmpty() && !locale.getCountry().isEmpty()) {
                String name = locale.getDisplayName();
                if (!addedNames.contains(name)) {
                    localeItems.add(new LocaleItem(locale, name));
                    addedNames.add(name);
                }
            }
        }

        Collections.sort(localeItems, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));

        ArrayAdapter<LocaleItem> adapter = new ArrayAdapter<>(
                requireContext(), android.R.layout.simple_spinner_item, localeItems);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(adapter);
        spinnerLanguage.setSelection(contentSelectionIndex(localeItems), false);
    }

    /**
     * Content-language selection follows the STORED preference, so it stays put when the
     * app UI language changes (that override alters {@code Locale.getDefault()} on
     * recreation). Only on first run (nothing stored yet) does it fall back to the phone
     * locale. See ADFA-4304.
     */
    private int contentSelectionIndex(List<LocaleItem> items) {
        SharedPreferences prefs = requireContext().getSharedPreferences(
                getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
        String storedSimple = prefs.getString("selected_lang_simple", null); // e.g. "es-ES"
        String lang;
        String country;
        if (storedSimple != null && !storedSimple.isEmpty()) {
            String[] parts = storedSimple.split("-", 2);
            lang = parts[0];
            country = parts.length > 1 ? parts[1] : "";
        } else {
            // First run: fall back to the DEVICE locale, read from the system resources
            // so it is immune to the app UI language override (AppCompat changes
            // Locale.getDefault(), which would otherwise drift the content selection).
            Locale device = deviceLocale();
            lang = device.getLanguage();
            country = device.getCountry();
        }
        List<Locale> locales = new ArrayList<>(items.size());
        for (LocaleItem item : items) {
            locales.add(item.locale);
        }
        return LocaleMatcher.pickIndex(locales, lang, country);
    }

    /** The physical device locale, unaffected by the per-app UI language override. */
    private Locale deviceLocale() {
        android.content.res.Configuration sys = android.content.res.Resources.getSystem().getConfiguration();
        if (sys.getLocales().isEmpty()) {
            return Locale.getDefault();
        }
        return sys.getLocales().get(0);
    }

    private void setupListeners() {
        switchNotif.setOnClickListener(v -> {
            if (hasNotifPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchNotif.isChecked() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
                notifLauncher.launch(intent);
            }
            switchNotif.setChecked(false);
        });

        switchStorage.setOnClickListener(v -> {
            if (hasStoragePermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchStorage.isChecked()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.addCategory("android.intent.category.DEFAULT");
                        intent.setData(Uri.parse(String.format("package:%s", requireContext().getPackageName())));
                        storageLauncher.launch(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        storageLauncher.launch(intent);
                    }
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
            switchStorage.setChecked(false);
        });

        switchBattery.setOnClickListener(v -> {
            if (hasBatteryPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchBattery.isChecked() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                batteryLauncher.launch(intent);
            }
            switchBattery.setChecked(false);
        });

        switchOverlay.setOnClickListener(v -> {
            if (hasOverlayPermission()) {
                handleRevokeAttempt(v);
                return;
            }
            if (switchOverlay.isChecked() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + requireContext().getPackageName()));
                overlayLauncher.launch(intent);
            }
            switchOverlay.setChecked(false);
        });

        btnManageAll.setOnClickListener(v -> openAppSettings());
    }

    private void handleRevokeAttempt(View switchView) {
        ((SwitchCompat) switchView).setChecked(true);
        Animation shake = AnimationUtils.loadAnimation(requireContext(), R.anim.shake);
        switchView.startAnimation(shake);
        Snackbar.make(requireView(), R.string.revoke_permission_warning, Snackbar.LENGTH_LONG)
                .setAction(R.string.settings_label, v -> openAppSettings()).show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
        startActivity(intent);
    }

    private void checkAllPermissions() {
        boolean notif = hasNotifPermission();
        boolean storage = hasStoragePermission();
        boolean battery = hasBatteryPermission();
        boolean overlay = hasOverlayPermission();

        switchNotif.setChecked(notif);
        switchStorage.setChecked(storage);
        switchBattery.setChecked(battery);
        switchOverlay.setChecked(overlay);

        if (!wizard) {
            return;
        }

        boolean allGranted = storage && battery && overlay;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            allGranted = allGranted && notif;
        }
        btnContinue.setEnabled(allGranted);
        btnContinue.setBackgroundTintList(ContextCompat.getColorStateList(requireContext(),
                allGranted ? R.color.btn_explore_ready : R.color.btn_explore_disabled));
    }

    private boolean hasNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(requireContext()).areNotificationsEnabled();
        }
        return true;
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBatteryPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
        }
        return true;
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(requireContext());
        }
        return true;
    }
}
