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
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import org.iiab.controller.network.presentation.DnsSettingsViewModel;
import org.iiab.controller.network.presentation.DnsSettingsViewModelFactory;

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
            case "dns":      title.setText(getString(R.string.k2go_settings_network_dns)); buildDns(ctx, list); break;
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
        // ADFA-4984: manual OTA entry ("update on the air"). LibraryActivity owns the UpdateController.
        SettingsUi.row(ctx, list, getString(R.string.k2go_settings_check_updates), null, null, v -> {
            if (getActivity() instanceof LibraryActivity) {
                org.iiab.controller.update.presentation.UpdateController uc =
                        ((LibraryActivity) getActivity()).updateController();
                if (uc != null) uc.checkForUpdatesManual();
            }
        });
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
        // ADFA-4968: System (Module management, Backups & recovery) moved to the Settings top level.
        // With System gone the DEVELOPER header is redundant, so Advanced is just the developer tools.
        SettingsUi.caption(ctx, list, getString(R.string.k2go_settings_power_users));
        SettingsUi.preview(ctx, list, "ADB", null);
        SettingsUi.row(ctx, list, getString(R.string.k2go_settings_network_dns), getString(R.string.setup_dns_hint), null, v -> openSub("dns"));
        SettingsUi.row(ctx, list, "Terminal (Debian)", null, null, v -> openTerminal(ctx));
    }

    private void openSub(String screen) {
        if (getActivity() instanceof LibraryActivity) {
            ((LibraryActivity) getActivity()).openSettingsSub(SettingsSubFragment.newInstance(screen));
        }
    }

    // ---- Network & DNS (ADFA-4955): reactivate the DNS chooser in the redesign, bound to the
    //      existing DnsSettingsViewModel. Validation, the reachability probe and apply-at-boot
    //      already live in org.iiab.controller.network — this is UI only. ----
    private void buildDns(Context ctx, LinearLayout list) {
        final DnsSettingsViewModel vm = new ViewModelProvider(
                this, new DnsSettingsViewModelFactory(ctx)).get(DnsSettingsViewModel.class);

        SettingsUi.caption(ctx, list, getString(R.string.setup_dns_hint));

        LinearLayout toggleRow = new LinearLayout(ctx);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setBackgroundResource(R.drawable.k2go_card_bg);
        int p14 = SettingsUi.dp(ctx, 14);
        toggleRow.setPadding(p14, p14, p14, p14);
        LinearLayout tcol = new LinearLayout(ctx);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tcol.addView(dnsText(ctx, getString(R.string.setup_dns),
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, R.color.k2go_ink));
        tcol.addView(dnsText(ctx, getString(R.string.k2go_dns_toggle_sub),
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted));
        toggleRow.addView(tcol);
        final MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setMinimumHeight(0);
        toggleRow.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(-1, -2);
        trlp.topMargin = SettingsUi.dp(ctx, 8);
        list.addView(toggleRow, trlp);

        final LinearLayout defaultCard = new LinearLayout(ctx);
        defaultCard.setOrientation(LinearLayout.VERTICAL);
        defaultCard.setBackgroundResource(R.drawable.k2go_info_bg);
        int p16 = SettingsUi.dp(ctx, 16);
        defaultCard.setPadding(p16, p16, p16, p16);
        defaultCard.addView(dnsText(ctx, getString(R.string.k2go_dns_default_title),
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, R.color.k2go_info_ink));
        final TextView defaultLine = dnsText(ctx, "",
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_info_ink);
        defaultCard.addView(defaultLine);
        defaultCard.addView(dnsText(ctx, getString(R.string.k2go_dns_default_note),
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_info_ink));
        LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(-1, -2);
        dclp.topMargin = SettingsUi.dp(ctx, 8);
        list.addView(defaultCard, dclp);

        final LinearLayout fields = new LinearLayout(ctx);
        fields.setOrientation(LinearLayout.VERTICAL);
        list.addView(fields, new LinearLayout.LayoutParams(-1, -2));

        fields.addView(dnsFieldLabel(ctx, getString(R.string.dns_primary), getString(R.string.k2go_dns_required)));
        final EditText primary = dnsInput(ctx);
        fields.addView(primary);
        final TextView errorText = dnsText(ctx, "",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_clay);
        errorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = SettingsUi.dp(ctx, 4);
        fields.addView(errorText, elp);

        fields.addView(dnsFieldLabel(ctx, getString(R.string.dns_secondary), getString(R.string.k2go_dns_optional)));
        final EditText secondary = dnsInput(ctx);
        fields.addView(secondary);

        final TextView accept = new TextView(ctx);
        accept.setText(getString(R.string.dns_accept));
        accept.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        accept.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_on_teal));
        accept.setGravity(Gravity.CENTER);
        accept.setBackgroundResource(R.drawable.k2go_primary_bg);
        int ap = SettingsUi.dp(ctx, 14);
        accept.setPadding(ap, ap, ap, ap);
        accept.setClickable(true);
        accept.setFocusable(true);
        LinearLayout.LayoutParams aclp = new LinearLayout.LayoutParams(-1, -2);
        aclp.topMargin = SettingsUi.dp(ctx, 20);
        fields.addView(accept, aclp);

        final LinearLayout statusCard = new LinearLayout(ctx);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(p16, SettingsUi.dp(ctx, 12), p16, SettingsUi.dp(ctx, 12));
        final CircularProgressIndicator spinner = new CircularProgressIndicator(ctx);
        spinner.setIndeterminate(true);
        spinner.setIndicatorSize(SettingsUi.dp(ctx, 18));
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(-2, -2);
        splp.rightMargin = SettingsUi.dp(ctx, 10);
        statusCard.addView(spinner, splp);
        final ImageView statusIcon = new ImageView(ctx);
        LinearLayout.LayoutParams silp = new LinearLayout.LayoutParams(SettingsUi.dp(ctx, 20), SettingsUi.dp(ctx, 20));
        silp.rightMargin = SettingsUi.dp(ctx, 10);
        statusIcon.setVisibility(View.GONE);
        statusCard.addView(statusIcon, silp);
        final TextView statusText = dnsText(ctx, "",
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, R.color.k2go_muted);
        statusCard.addView(statusText);
        statusCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(-1, -2);
        sclp.topMargin = SettingsUi.dp(ctx, 12);
        list.addView(statusCard, sclp);

        final boolean[] suppress = {false};
        sw.setOnCheckedChangeListener((b, isChk) -> { if (!suppress[0]) vm.onSetupToggled(isChk); });
        accept.setOnClickListener(v -> vm.onAccept(
                primary.getText().toString().trim(), secondary.getText().toString().trim()));

        list.post(() -> {
            if (!isAdded()) return;
            vm.state().observe(getViewLifecycleOwner(), st -> {
                suppress[0] = true; sw.setChecked(st.customEnabled); suppress[0] = false;
                fields.setVisibility(st.customEnabled ? View.VISIBLE : View.GONE);
                defaultCard.setVisibility(st.customEnabled ? View.GONE : View.VISIBLE);
                org.iiab.controller.network.domain.DnsConfig def =
                        org.iiab.controller.network.domain.DnsConfig.defaults();
                defaultLine.setText(def.primary() + "   ·   " + def.secondary());
                if (!primary.getText().toString().equals(st.primary)) primary.setText(st.primary);
                if (!secondary.getText().toString().equals(st.secondary)) secondary.setText(st.secondary);
                errorText.setVisibility(View.GONE);
                switch (st.status) {
                    case TESTING:
                        accept.setEnabled(false); accept.setAlpha(0.5f);
                        spinner.setVisibility(View.VISIBLE);
                        statusIcon.setVisibility(View.GONE);
                        statusCard.setBackground(null);
                        statusText.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
                        statusText.setText(getString(R.string.k2go_dns_testing));
                        statusCard.setVisibility(View.VISIBLE);
                        break;
                    case APPLIED:
                        accept.setEnabled(true); accept.setAlpha(1f);
                        spinner.setVisibility(View.GONE);
                        statusIcon.setImageResource(R.drawable.ic_check_circle);
                        statusIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.k2go_leaf));
                        statusIcon.setVisibility(View.VISIBLE);
                        statusCard.setBackgroundResource(R.drawable.k2go_ok_bg);
                        statusText.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_leaf));
                        statusText.setText(getString(R.string.dns_status_ok));
                        statusCard.setVisibility(View.VISIBLE);
                        break;
                    case UNREACHABLE:
                        accept.setEnabled(true); accept.setAlpha(1f);
                        spinner.setVisibility(View.GONE);
                        statusIcon.setImageResource(R.drawable.ic_warning_24);
                        statusIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.k2go_amber_text));
                        statusIcon.setVisibility(View.VISIBLE);
                        statusCard.setBackgroundResource(R.drawable.k2go_warn_bg);
                        statusText.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_amber_text));
                        statusText.setText(st.message == null ? "" : st.message);
                        statusCard.setVisibility(View.VISIBLE);
                        break;
                    case INVALID:
                        accept.setEnabled(true); accept.setAlpha(1f);
                        statusCard.setVisibility(View.GONE);
                        errorText.setText(st.message == null ? "" : st.message);
                        errorText.setVisibility(View.VISIBLE);
                        break;
                    case IDLE:
                    default:
                        accept.setEnabled(true); accept.setAlpha(1f);
                        statusCard.setVisibility(View.GONE);
                        break;
                }
            });
            vm.load();
        });
    }

    private TextView dnsText(Context ctx, String s, int appearance, int colorRes) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextAppearance(appearance);
        t.setTextColor(ContextCompat.getColor(ctx, colorRes));
        return t;
    }

    private View dnsFieldLabel(Context ctx, String label, String hintRight) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = dnsText(ctx, label,
                com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, R.color.k2go_teal);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(l);
        row.addView(dnsText(ctx, hintRight,
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = SettingsUi.dp(ctx, 16);
        row.setLayoutParams(lp);
        return row;
    }

    private EditText dnsInput(Context ctx) {
        EditText e = new EditText(ctx);
        e.setBackgroundResource(R.drawable.k2go_lang_box_bg);
        int p = SettingsUi.dp(ctx, 14);
        e.setPadding(p, p, p, p);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ink));
        e.setHintTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = SettingsUi.dp(ctx, 8);
        e.setLayoutParams(lp);
        return e;
    }

    private String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
