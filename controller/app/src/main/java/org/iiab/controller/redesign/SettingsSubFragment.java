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
            case "authentication": title.setText(getString(R.string.k2go_settings_authentication)); buildAuthentication(ctx, list); break;
            case "auth:calibre": title.setText(getString(R.string.k2go_auth_svc_books)); buildServiceAuth(ctx, list, "calibre"); break;
            case "auth:kolibri": title.setText(getString(R.string.k2go_auth_svc_courses)); buildServiceAuth(ctx, list, "kolibri"); break;
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

    /** The full Debian terminal lives in its own TerminalActivity (ADFA-5192). */
    private void openTerminal(Context ctx) {
        ctx.startActivity(new Intent(ctx, org.iiab.controller.TerminalActivity.class));
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
                        statusIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.k2go_ok_ink));
                        statusIcon.setVisibility(View.VISIBLE);
                        statusCard.setBackgroundResource(R.drawable.k2go_ok_bg);
                        statusText.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_ok_ink));
                        statusText.setText(getString(R.string.dns_status_ok));
                        statusCard.setVisibility(View.VISIBLE);
                        break;
                    case UNREACHABLE:
                        accept.setEnabled(true); accept.setAlpha(1f);
                        spinner.setVisibility(View.GONE);
                        statusIcon.setImageResource(R.drawable.ic_warning_24);
                        statusIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.k2go_warn_ink));
                        statusIcon.setVisibility(View.VISIBLE);
                        statusCard.setBackgroundResource(R.drawable.k2go_warn_bg);
                        statusText.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_warn_ink));
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

    // ---- Authentication (ADFA-5044): manage the admin sign-ins the box uses for Books (Calibre-Web)
    //      and Courses (Kolibri) — the same store the WebView auto-login (ADFA-5043) relies on.
    //      List → per-service editor with the DNS-style "use a custom sign-in" switch (off = box
    //      default, on = reveal fields). Wired to /k2go-api/credentials/:service via CredentialsClient. ----
    private void buildAuthentication(Context ctx, LinearLayout list) {
        SettingsUi.caption(ctx, list, getString(R.string.k2go_auth_hint));
        authServiceRow(ctx, list, "calibre", getString(R.string.k2go_auth_svc_books), "Calibre-Web");
        authServiceRow(ctx, list, "kolibri", getString(R.string.k2go_auth_svc_courses), "Kolibri");
        SettingsUi.caption(ctx, list, getString(R.string.k2go_auth_list_note));
    }

    /** A navigable row per service with a state chip (Default / Custom / Not installed). A service
     *  that isn't reachable is dimmed but stays tappable so the sign-in can be pre-set. */
    private void authServiceRow(Context ctx, LinearLayout list, String service, String name, String platform) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        int p14 = SettingsUi.dp(ctx, 14);
        row.setPadding(p14, p14, p14, p14);
        row.setClickable(true);
        row.setOnClickListener(v -> openSub("auth:" + service));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = SettingsUi.dp(ctx, 8);
        list.addView(row, rlp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(dnsText(ctx, name,
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_ink));
        col.addView(dnsText(ctx, platform,
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted));
        row.addView(col);

        final TextView chip = new TextView(ctx);
        chip.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall);
        chip.setPadding(SettingsUi.dp(ctx, 10), SettingsUi.dp(ctx, 4), SettingsUi.dp(ctx, 10), SettingsUi.dp(ctx, 4));
        chip.setVisibility(View.GONE);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(-2, -2);
        chlp.rightMargin = SettingsUi.dp(ctx, 8);
        row.addView(chip, chlp);

        TextView chev = new TextView(ctx);
        chev.setText("›");
        chev.setTextSize(18);
        chev.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        row.addView(chev);

        row.setAlpha(0.5f);
        probeService(service, reachable -> {
            if (!isAdded()) return;
            row.setAlpha(reachable ? 1f : 0.5f);
            if (!reachable) {
                setChip(chip, getString(R.string.k2go_auth_chip_notinstalled), R.drawable.k2go_pill_bg, R.color.k2go_muted);
                return;
            }
            CredentialsClient.describe(service, new CredentialsClient.DescribeCb() {
                @Override public void onOk(String user, String pass, boolean isDefault) {
                    if (!isAdded()) return;
                    if (isDefault) setChip(chip, getString(R.string.k2go_auth_chip_default), R.drawable.k2go_pill_bg, R.color.k2go_muted);
                    else setChip(chip, getString(R.string.k2go_auth_chip_custom), R.drawable.k2go_pill_teal, R.color.k2go_teal);
                }
                @Override public void onErr() { /* leave the chip hidden on a load error */ }
            });
        });
    }

    /** Style + reveal a state chip (Default / Custom / Not installed). */
    private void setChip(TextView chip, String text, int bgRes, int colorRes) {
        chip.setText(text);
        chip.setBackgroundResource(bgRes);
        chip.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        chip.setVisibility(View.VISIBLE);
    }

    private void buildServiceAuth(Context ctx, LinearLayout list, String service) {
        final int subRes = "calibre".equals(service) ? R.string.k2go_auth_sub_books : R.string.k2go_auth_sub_courses;
        SettingsUi.caption(ctx, list, getString(subRes));

        // Subtle "not running yet" line (no banner) — only shown when the service isn't reachable.
        final TextView note = dnsText(ctx, getString(R.string.k2go_auth_not_running),
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted);
        note.setVisibility(View.GONE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = SettingsUi.dp(ctx, 4);
        list.addView(note, nlp);

        // The single control: "Use a custom sign-in".
        LinearLayout toggleRow = new LinearLayout(ctx);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = SettingsUi.dp(ctx, 16);
        toggleRow.setPadding(p16, p16, p16, p16);
        toggleRow.addView(dnsText(ctx, getString(R.string.k2go_auth_custom_toggle),
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_ink),
                new LinearLayout.LayoutParams(0, -2, 1f));
        final MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setMinimumHeight(0);
        toggleRow.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(-1, -2);
        trlp.topMargin = SettingsUi.dp(ctx, 16);
        list.addView(toggleRow, trlp);

        // OFF group: a subtle status line (check + text) + a short hint. Not a big banner.
        final LinearLayout offGroup = new LinearLayout(ctx);
        offGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams oglp = new LinearLayout.LayoutParams(-1, -2);
        oglp.topMargin = SettingsUi.dp(ctx, 16);
        list.addView(offGroup, oglp);

        LinearLayout statusRow = new LinearLayout(ctx);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView check = dnsText(ctx, "✓",
                com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, R.color.k2go_leaf);
        LinearLayout.LayoutParams cklp = new LinearLayout.LayoutParams(-2, -2);
        cklp.rightMargin = SettingsUi.dp(ctx, 8);
        check.setLayoutParams(cklp);
        statusRow.addView(check);
        statusRow.addView(dnsText(ctx, getString(R.string.k2go_auth_using_default),
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge, R.color.k2go_ink));
        offGroup.addView(statusRow);

        TextView toggleHint = dnsText(ctx, getString(R.string.k2go_auth_toggle_hint),
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted);
        LinearLayout.LayoutParams thlp = new LinearLayout.LayoutParams(-1, -2);
        thlp.topMargin = SettingsUi.dp(ctx, 12);
        toggleHint.setLayoutParams(thlp);
        offGroup.addView(toggleHint);

        // ON group: the edit fields.
        final LinearLayout fields = new LinearLayout(ctx);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setVisibility(View.GONE);
        list.addView(fields, new LinearLayout.LayoutParams(-1, -2));

        fields.addView(dnsFieldLabel(ctx, getString(R.string.k2go_auth_username), ""));
        final EditText username = dnsInput(ctx);
        fields.addView(username);

        fields.addView(dnsFieldLabel(ctx, getString(R.string.k2go_auth_password), ""));
        final EditText password = dnsInput(ctx);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        attachEyeToggle(password);   // eye lives inside the field (no floating "Show")
        fields.addView(password);

        TextView eyeHint = dnsText(ctx, getString(R.string.k2go_auth_eye_hint),
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted);
        LinearLayout.LayoutParams ehlp = new LinearLayout.LayoutParams(-2, -2);
        ehlp.topMargin = SettingsUi.dp(ctx, 6);
        eyeHint.setLayoutParams(ehlp);
        fields.addView(eyeHint);

        final TextView save = new TextView(ctx);
        save.setText(getString(R.string.k2go_auth_save));
        save.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        save.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_on_teal));
        save.setGravity(Gravity.CENTER);
        save.setBackgroundResource(R.drawable.k2go_primary_bg);
        int ap = SettingsUi.dp(ctx, 14);
        save.setPadding(ap, ap, ap, ap);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, -2);
        svlp.topMargin = SettingsUi.dp(ctx, 16);
        fields.addView(save, svlp);

        // Reset = revert to the box default = simply flip the switch off.
        final TextView reset = dnsText(ctx, getString(R.string.k2go_auth_reset),
                com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, R.color.k2go_teal);
        reset.setClickable(true);
        reset.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(-1, -2);
        rslp.topMargin = SettingsUi.dp(ctx, 12);
        reset.setLayoutParams(rslp);
        fields.addView(reset);

        final TextView status = dnsText(ctx, "",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall, R.color.k2go_muted);
        status.setVisibility(View.GONE);
        fields.addView(status);

        // Save is enabled only once a password is typed (the box never returns the stored one).
        final Runnable refreshSave = () -> {
            boolean ok = password.getText().length() > 0;
            save.setEnabled(ok);
            save.setClickable(ok);
            save.setAlpha(ok ? 1f : 0.5f);
        };
        password.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { refreshSave.run(); }
        });
        refreshSave.run();

        final boolean[] suppress = {false};   // avoid the reset side-effect during programmatic toggles
        sw.setOnCheckedChangeListener((b, on) -> {
            fields.setVisibility(on ? View.VISIBLE : View.GONE);
            offGroup.setVisibility(on ? View.GONE : View.VISIBLE);
            if (on) { refreshSave.run(); return; }
            if (suppress[0]) return;
            // Turning custom off = go back to the box default.
            CredentialsClient.reset(service, new CredentialsClient.ResetCb() {
                @Override public void onOk(String user, String pass, boolean isDefault) {
                    if (!isAdded()) return;
                    username.setText(user);
                    // Re-prefill the default sign-in (same as first entry), so re-enabling custom
                    // shows the full pair instead of a blank password.
                    password.setText(pass);
                }
                @Override public void onErr() { /* the box keeps the previous value on error */ }
            });
        });

        save.setOnClickListener(v -> {
            String u = username.getText().toString().trim();
            String p = password.getText().toString();
            if (u.isEmpty() || p.isEmpty()) { setStatus(status, getString(R.string.k2go_auth_need_both), R.color.k2go_clay); return; }
            setStatus(status, getString(R.string.k2go_auth_saving), R.color.k2go_muted);
            CredentialsClient.save(service, u, p, new CredentialsClient.SaveCb() {
                @Override public void onOk(boolean verified) {
                    if (!isAdded()) return;
                    setStatus(status, getString(verified ? R.string.k2go_auth_verified : R.string.k2go_auth_saved), R.color.k2go_teal);
                }
                @Override public void onErr(int code) {
                    if (!isAdded()) return;
                    int msg = code == 401 ? R.string.k2go_auth_bad
                            : code == 403 ? R.string.k2go_auth_noperm : R.string.k2go_auth_failed;
                    setStatus(status, getString(msg), R.color.k2go_clay);
                }
            });
        });

        reset.setOnClickListener(v -> sw.setChecked(false));   // the switch listener does the revert

        // Initial state: switch reflects default (off) vs custom (on); username prefilled either way.
        CredentialsClient.describe(service, new CredentialsClient.DescribeCb() {
            @Override public void onOk(String user, String pass, boolean isDefault) {
                if (!isAdded()) return;
                username.setText(user);
                // The box returns the password only while still at the factory default, so the form
                // can prefill the full sign-in; a custom password never comes back and stays blank.
                if (!pass.isEmpty()) password.setText(pass);
                suppress[0] = true;
                sw.setChecked(!isDefault);
                suppress[0] = false;
                fields.setVisibility(isDefault ? View.GONE : View.VISIBLE);
                offGroup.setVisibility(isDefault ? View.VISIBLE : View.GONE);
                refreshSave.run();
            }
            @Override public void onErr() {
                if (!isAdded()) return;
                // Surface on `note` (always present) — `status` lives inside the fields, hidden in default state.
                note.setText(getString(R.string.k2go_auth_load_failed));
                note.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_clay));
                note.setVisibility(View.VISIBLE);
            }
        });
        probeService(service, reachable -> { if (isAdded()) note.setVisibility(reachable ? View.GONE : View.VISIBLE); });
    }

    /** Wire the Material "visibility" eye as the end drawable inside a password field (tap to toggle).
     *  The icons ship white-filled (repo convention) and are tinted to the muted colour at usage. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void attachEyeToggle(final EditText field) {
        final android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(field.getContext(), R.color.k2go_muted));
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.outline_visibility_24, 0);
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(field, tint);
        field.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != android.view.MotionEvent.ACTION_UP) return false;
            android.graphics.drawable.Drawable d = field.getCompoundDrawablesRelative()[2];
            if (d == null) return false;
            int dw = d.getBounds().width();
            // The end drawable sits on the right in LTR and on the left in RTL; hit-test the correct edge.
            boolean rtl = field.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean hit = rtl
                    ? ev.getX() <= field.getPaddingStart() + dw
                    : ev.getX() >= field.getWidth() - field.getPaddingEnd() - dw;
            if (!hit) return false;
            boolean hidden = field.getTransformationMethod() != null;
            if (hidden) {
                field.setTransformationMethod(null);
                field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.outline_visibility_off_24, 0);
            } else {
                field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
                field.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.outline_visibility_24, 0);
            }
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(field, tint);
            field.setSelection(field.getText().length());
            v.performClick();
            return true;
        });
    }

    /** Best-effort reachability probe of a service page (calibre → /books/, kolibri → /kolibri/). */
    private void probeService(String service, Probe cb) {
        final String endpoint = "calibre".equals(service) ? "books" : service;
        final String url = org.iiab.controller.config.BoxEndpoints.BASE + "/" + endpoint + "/";
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        org.iiab.controller.util.AppExecutors.get().io().execute(() -> {
            boolean ok = false;
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                int code = c.getResponseCode();
                ok = code >= 200 && code < 500;   // any answer (even 401/403) means the service is up
            } catch (Exception ignore) {
            } finally {
                if (c != null) c.disconnect();
            }
            final boolean r = ok;
            main.post(() -> cb.onResult(r));
        });
    }

    private interface Probe { void onResult(boolean reachable); }

    private void setStatus(TextView status, String text, int colorRes) {
        status.setText(text);
        status.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        status.setVisibility(View.VISIBLE);
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
