package org.iiab.controller.redesign;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import org.iiab.controller.R;
import org.iiab.controller.applang.data.AppLocaleController;
import org.iiab.controller.applang.data.LanguageResolver;
import org.iiab.controller.applang.domain.AppLanguage;
import org.iiab.controller.applang.domain.SupportedAppLanguages;
import org.iiab.controller.delivery.data.AnalyticsConsent;

/** Settings sub-screen host: Language (functional — UI + content), About (version, permissions,
 *  usage-stats consent), Advanced (power-user features, preview for now). Keeps the bottom nav;
 *  the ‹ back returns to the Settings top level. */
public class SettingsSubFragment extends Fragment {

    private static final String ARG = "screen";
    private static final String PREF_CONTENT_TAG = "content_lang_tag";

    private TextView contentValue;
    private String contentTag = "";   // "" = same as app language

    private final ActivityResultLauncher<Intent> appPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                    String tag = r.getData().getStringExtra(WizardLanguagePickerActivity.EXTRA_TAG);
                    applyAppLanguage(requireContext(), tag == null ? "" : tag);
                }
            });

    private final ActivityResultLauncher<Intent> contentPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                    String tag = r.getData().getStringExtra(WizardLanguagePickerActivity.EXTRA_TAG);
                    contentTag = tag == null ? "" : tag;
                    persistContent(requireContext());
                    if (contentValue != null) contentValue.setText(contentLabel());
                }
            });

    static SettingsSubFragment newInstance(String screen) {
        SettingsSubFragment f = new SettingsSubFragment();
        Bundle b = new Bundle();
        b.putString(ARG, screen);
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_settings_sub, c, false);
        Context ctx = requireContext();
        LinearLayout list = root.findViewById(R.id.k2go_sub_list);
        TextView title = root.findViewById(R.id.k2go_sub_title);
        root.findViewById(R.id.k2go_sub_back).setOnClickListener(v ->
                requireActivity().getSupportFragmentManager().popBackStack());

        String screen = getArguments() != null ? getArguments().getString(ARG, "") : "";
        switch (screen) {
            case "language": title.setText(getString(R.string.k2go_settings_language)); buildLanguage(ctx, list); break;
            case "about":    title.setText(getString(R.string.k2go_settings_about));    buildAbout(ctx, list);    break;
            case "advanced": title.setText(getString(R.string.k2go_settings_advanced)); buildAdvanced(ctx, list); break;
            default:         title.setText(getString(R.string.k2go_tab_settings));
        }
        return root;
    }

    // ---- Language: two independent selectors (ADFA-4798). App language drives the UI locale;
    //      content language is the default for downloaded content. Both open the same searchable
    //      picker. Content "" means "same as app language" and tracks the app tag. ----
    private void buildLanguage(Context ctx, LinearLayout list) {
        SettingsUi.caption(ctx, list, getString(R.string.k2go_lang_settings_sub));
        String appTag = AppLocaleController.currentTag();
        contentTag = readContentTag(ctx);

        SettingsUi.selector(ctx, list,
                getString(R.string.k2go_lang_app_label),
                appLabel(appTag),
                getString(R.string.k2go_lang_app_helper),
                v -> appPicker.launch(pickerIntent(ctx,
                        getString(R.string.k2go_lang_choose_title),
                        getString(R.string.k2go_lang_follow_system),
                        AppLocaleController.currentTag())));

        contentValue = SettingsUi.selector(ctx, list,
                getString(R.string.k2go_lang_content_label),
                contentLabel(),
                getString(R.string.k2go_lang_content_helper),
                v -> contentPicker.launch(pickerIntent(ctx,
                        getString(R.string.k2go_lang_choose_content_title),
                        getString(R.string.k2go_lang_same_as_app),
                        contentTag)));

        SettingsUi.note(ctx, list, getString(R.string.k2go_lang_content_note));
    }

    private Intent pickerIntent(Context ctx, String title, String pinned, String current) {
        return new Intent(ctx, WizardLanguagePickerActivity.class)
                .putExtra(WizardLanguagePickerActivity.EXTRA_TITLE, title)
                .putExtra(WizardLanguagePickerActivity.EXTRA_PINNED, pinned)
                .putExtra(WizardLanguagePickerActivity.EXTRA_TAG, current);
    }

    /** App language: applying the UI locale recreates the activities (AppCompat persists it).
     *  Recompute the content code first so a "same as app" content choice tracks the new app. */
    private void applyAppLanguage(Context ctx, String tag) {
        prefs(ctx).edit()
                .putString("selected_lang_minimal", LanguageResolver.contentCode(tag, contentTag))
                .apply();
        AppLocaleController.apply(tag);
    }

    /** Content language: independent of the UI locale, no recreate. Persists the choice and the
     *  resolved content code the installer consumes. */
    private void persistContent(Context ctx) {
        String appTag = AppLocaleController.currentTag();
        prefs(ctx).edit()
                .putString(PREF_CONTENT_TAG, contentTag)
                .putString("selected_lang_minimal", LanguageResolver.contentCode(appTag, contentTag))
                .apply();
    }

    private String readContentTag(Context ctx) {
        String t = prefs(ctx).getString(PREF_CONTENT_TAG, "");
        return t == null ? "" : t;
    }

    private SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
    }

    /** Endonym shown in the app box; "" -> the "follow system" label. */
    private String appLabel(String tag) {
        if (tag == null || tag.isEmpty()) return getString(R.string.k2go_lang_follow_system);
        return endonymOf(tag);
    }

    /** Endonym shown in the content box; "" -> the "same as app" label. */
    private String contentLabel() {
        if (contentTag == null || contentTag.isEmpty()) return getString(R.string.k2go_lang_same_as_app);
        return endonymOf(contentTag);
    }

    /** A tag's endonym from the canonical list, or the tag itself if not found. */
    private String endonymOf(String tag) {
        for (AppLanguage l : SupportedAppLanguages.forPicker("")) {
            if (l.tag().equals(tag)) return l.toString();
        }
        return tag;
    }

    // ---- About ----
    private void buildAbout(Context ctx, LinearLayout list) {
        SettingsUi.infoRow(ctx, list, getString(R.string.k2go_settings_app_version), versionName(ctx));
        SettingsUi.row(ctx, list, getString(R.string.k2go_settings_permissions), null, null, v -> openAppSettings(ctx));
        SettingsUi.toggle(ctx, list, getString(R.string.k2go_settings_usage_stats), AnalyticsConsent.isEnabled(ctx), checked -> {
            AnalyticsConsent.setEnabled(ctx, checked);
            org.iiab.controller.analytics.AnalyticsClient.with(ctx).applyConsent();
        });
        SettingsUi.preview(ctx, list, getString(R.string.k2go_settings_licenses), null);
        SettingsUi.preview(ctx, list, getString(R.string.k2go_settings_privacy), null);
    }

    private void openAppSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Exception ignore) { /* no-op */ }
    }

    /** The full Debian terminal lives in MainActivity; EXTRA_OPEN_TERMINAL opens it directly. */
    private void openTerminal(Context ctx) {
        Intent i = new Intent(ctx, org.iiab.controller.MainActivity.class);
        i.putExtra(org.iiab.controller.MainActivity.EXTRA_OPEN_TERMINAL, true);
        i.putExtra(org.iiab.controller.MainActivity.EXTRA_TERMINAL_ONLY, true);
        ctx.startActivity(i);
    }

    // ---- Advanced (power-user features — preview for now) ----
    private void buildAdvanced(Context ctx, LinearLayout list) {
        SettingsUi.caption(ctx, list, getString(R.string.k2go_settings_power_users));
        SettingsUi.sectionHeader(ctx, list, getString(R.string.k2go_settings_sec_system));
        SettingsUi.preview(ctx, list, getString(R.string.k2go_settings_module_mgmt), getString(R.string.k2go_settings_module_mgmt_sub));
        SettingsUi.preview(ctx, list, getString(R.string.k2go_settings_backups), null);
        SettingsUi.sectionHeader(ctx, list, getString(R.string.k2go_settings_sec_developer));
        SettingsUi.preview(ctx, list, "ADB", null);
        SettingsUi.preview(ctx, list, getString(R.string.k2go_settings_network_dns), null);
        SettingsUi.row(ctx, list, "Terminal (Debian)", null, null, v -> openTerminal(ctx));
    }

    private String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
