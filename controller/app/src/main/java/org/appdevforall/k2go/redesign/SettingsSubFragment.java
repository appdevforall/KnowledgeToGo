package org.appdevforall.k2go.redesign;

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
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.applang.data.AppLocaleController;
import org.appdevforall.k2go.applang.data.LanguageResolver;
import org.appdevforall.k2go.applang.domain.AppLanguage;
import org.appdevforall.k2go.applang.domain.SupportedAppLanguages;
import org.appdevforall.k2go.delivery.data.OnlineConsent;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.ImageView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import org.appdevforall.k2go.network.presentation.DnsSettingsViewModel;
import org.appdevforall.k2go.network.presentation.DnsSettingsViewModelFactory;
import org.appdevforall.k2go.ui.K2GoHeaderView;
import org.appdevforall.k2go.ui.K2GoText;

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
        K2GoHeaderView header = root.findViewById(R.id.k2go_sub_header);

        String screen = getArguments() != null ? getArguments().getString(ARG, "") : "";
        switch (screen) {
            case "language": header.setTitle(getString(R.string.k2go_settings_language)); buildLanguage(ctx, list); break;
            case "about":    header.setTitle(getString(R.string.k2go_settings_about));    buildAbout(ctx, list);    break;
            case "about:version": header.setTitle(getString(R.string.k2go_settings_app_version)); buildVersionDetails(ctx, list); break;
            case "advanced": header.setTitle(getString(R.string.k2go_settings_advanced)); buildAdvanced(ctx, list); break;
            case "dns":      header.setTitle(getString(R.string.k2go_settings_network_dns)); buildDns(ctx, list); break;
            case "hotspot":  header.setTitle(getString(R.string.k2go_settings_hotspot)); buildHotspot(ctx, list); break;
            case "authentication": header.setTitle(getString(R.string.k2go_settings_authentication)); buildAuthentication(ctx, list); break;
            case "auth:calibre": header.setTitle(getString(R.string.k2go_auth_svc_books)); buildServiceAuth(ctx, list, "calibre"); break;
            case "auth:kolibri": header.setTitle(getString(R.string.k2go_auth_svc_courses)); buildServiceAuth(ctx, list, "kolibri"); break;
            case "auth:forgejo": header.setTitle(getString(R.string.k2go_card_forgejo)); buildServiceAuth(ctx, list, "forgejo"); break;   // K2GO-417
            default:         header.setTitle(getString(R.string.k2go_tab_settings));
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
        SettingsUi.row(ctx, list, getString(R.string.k2go_settings_app_version), null, versionName(ctx),
                v -> openSub("about:version"));
        // ADFA-4984: manual OTA entry ("update on the air"). LibraryActivity owns the UpdateController.
        // K2GO-403: hidden in the fdroid build, which ships no in-app updater (updates come from F-Droid).
        if (org.appdevforall.k2go.BuildConfig.OTA_ENABLED) {
            SettingsUi.row(ctx, list, getString(R.string.k2go_settings_check_updates), null, null, v -> {
                if (getActivity() instanceof LibraryActivity) {
                    org.appdevforall.k2go.update.presentation.UpdateController uc =
                            ((LibraryActivity) getActivity()).updateController();
                    if (uc != null) uc.checkForUpdatesManual();
                }
            });
        }
        SettingsUi.row(ctx, list, getString(R.string.k2go_settings_permissions), null, null, v -> openAppSettings(ctx));
        // K2GO-588: the one privacy choice (usage data where the build has analytics, crash reports,
        // automatic connections). Off stops crash events at once (beforeSend); on starts crash
        // reports at the next launch, because a mid-session Sentry init disturbs the
        // uncaught-handler order that IIABApplication sets at startup.
        SettingsUi.toggle(ctx, list, getString(OnlineConsent.choiceLabel()), OnlineConsent.isEnabled(ctx), checked -> {
            OnlineConsent.setEnabled(ctx, checked);
            org.appdevforall.k2go.analytics.AnalyticsClient.with(ctx).applyConsent();
            if (!checked) io.sentry.Sentry.close();   // nothing more leaves for GlitchTip in this session
        });
        SettingsUi.preview(ctx, list, getString(R.string.k2go_settings_licenses), null);
    }

    // ---- App version details: Android version and the phone / app / library architectures.
    //      Each value has its own source (ADR-K2GO-571); the file read runs off the main thread. ----
    private void buildVersionDetails(Context ctx, LinearLayout list) {
        final Context app = ctx.getApplicationContext();
        org.appdevforall.k2go.util.AppExecutors.get().io().execute(() -> {
            org.appdevforall.k2go.deviceinfo.domain.PlatformInfo p =
                    org.appdevforall.k2go.deviceinfo.data.PlatformInfoReader.read(app);
            list.post(() -> { if (isAdded()) showVersionDetails(ctx, list, p); });
        });
    }

    private void showVersionDetails(Context ctx, LinearLayout list,
                                    org.appdevforall.k2go.deviceinfo.domain.PlatformInfo p) {
        StringBuilder copy = new StringBuilder();
        String phone = getString(R.string.k2go_about_sec_phone);
        SettingsUi.sectionHeader(ctx, list, phone);
        copy.append(phone).append('\n');
        detail(ctx, list, copy, getString(R.string.k2go_about_android),
                getString(R.string.k2go_about_android_value, p.androidRelease, p.sdkInt));
        detail(ctx, list, copy, getString(R.string.k2go_about_processor), bits(p.phoneAbi, false));
        detail(ctx, list, copy, getString(R.string.k2go_about_runs_32),
                getString(p.phoneRuns32Bit ? R.string.k2go_about_yes : R.string.k2go_about_no));

        String app = getString(R.string.k2go_about_sec_app);
        SettingsUi.sectionHeader(ctx, list, app);
        copy.append(app).append('\n');
        detail(ctx, list, copy, getString(R.string.k2go_about_version), versionName(ctx));
        detail(ctx, list, copy, getString(R.string.k2go_about_runs_as), bits(p.appAbi, false));

        String lib = getString(R.string.k2go_about_sec_library);
        SettingsUi.sectionHeader(ctx, list, lib);
        copy.append(lib).append('\n');
        String libValue;
        switch (p.libraryState) {
            case KNOWN:         libValue = bits(p.libraryAbi, true); break;
            case NOT_INSTALLED: libValue = getString(R.string.k2go_about_not_installed); break;
            default:            libValue = getString(R.string.k2go_about_unknown);
        }
        detail(ctx, list, copy, getString(R.string.k2go_about_architecture), libValue);

        final String text = copy.toString().trim();
        SettingsUi.row(ctx, list, getString(R.string.k2go_about_copy), null, null, v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("K2Go", text));
            String msg = getString(R.string.k2go_about_copied);
            com.google.android.material.snackbar.Snackbar.make(v, msg,
                    org.appdevforall.k2go.util.SnackbarDuration.millisForText(msg)).show();
        });
        // Room under the last row so the floating feedback button never covers a value.
        list.addView(new View(ctx), new LinearLayout.LayoutParams(-1, SettingsUi.dp(ctx, 96)));
    }

    private void detail(Context ctx, LinearLayout list, StringBuilder copy, String title, String value) {
        SettingsUi.infoRow(ctx, list, title, value);
        copy.append(title).append(": ").append(value).append('\n');
    }

    /** "64-bit (arm64-v8a)", or for the library "32-bit (Debian armhf)": width and the exact id,
     *  so a reader never has to infer one from the other. */
    private String bits(String abi, boolean debian) {
        if (abi == null || abi.isEmpty() || "unknown".equals(abi)) return getString(R.string.k2go_about_unknown);
        int b = org.appdevforall.k2go.arch.domain.LibraryArch.bitsOf(abi);
        String id = debian ? org.appdevforall.k2go.arch.domain.LibraryArch.debArchOf(abi) : abi;
        if (b == 0 || id == null) return abi;
        return getString(debian ? R.string.k2go_about_bits_debian : R.string.k2go_about_bits_abi, b, id);
    }

    private void openAppSettings(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName())));
        } catch (Exception ignore) { /* no-op */ }
    }

    /** The full Debian terminal lives in its own TerminalActivity (ADFA-5192). */
    private void openTerminal(Context ctx) {
        ctx.startActivity(new Intent(ctx, org.appdevforall.k2go.TerminalActivity.class));
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
    //      already live in org.appdevforall.k2go.network — this is UI only. ----
    private void buildDns(Context ctx, LinearLayout list) {
        final DnsSettingsViewModel vm = new ViewModelProvider(
                this, new DnsSettingsViewModelFactory(ctx)).get(DnsSettingsViewModel.class);

        SettingsUi.caption(ctx, list, getString(R.string.setup_dns_hint));

        LinearLayout toggleRow = new LinearLayout(ctx);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setBackgroundResource(R.drawable.k2go_card_bg);
        int tp = SettingsUi.dp(ctx, 16);   // card padding on the 4dp grid, matching SettingsUi.baseRow
        toggleRow.setPadding(tp, tp, tp, tp);
        LinearLayout tcol = new LinearLayout(ctx);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tcol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        tcol.addView(K2GoText.body(ctx, getString(R.string.setup_dns), R.color.k2go_ink));
        tcol.addView(K2GoText.label(ctx, getString(R.string.k2go_dns_toggle_sub), R.color.k2go_muted));
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
        defaultCard.addView(K2GoText.body(ctx, getString(R.string.k2go_dns_default_title), R.color.k2go_info_ink));
        final TextView defaultLine = K2GoText.body(ctx, "", R.color.k2go_info_ink);
        defaultCard.addView(defaultLine);
        defaultCard.addView(K2GoText.label(ctx, getString(R.string.k2go_dns_default_note), R.color.k2go_info_ink));
        LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(-1, -2);
        dclp.topMargin = SettingsUi.dp(ctx, 8);
        list.addView(defaultCard, dclp);

        final LinearLayout fields = new LinearLayout(ctx);
        fields.setOrientation(LinearLayout.VERTICAL);
        list.addView(fields, new LinearLayout.LayoutParams(-1, -2));

        fields.addView(dnsFieldLabel(ctx, getString(R.string.dns_primary), getString(R.string.k2go_dns_required)));
        final EditText primary = dnsInput(ctx);
        fields.addView(primary);
        final TextView errorText = K2GoText.label(ctx, "", R.color.k2go_clay);
        errorText.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.topMargin = SettingsUi.dp(ctx, 4);
        fields.addView(errorText, elp);

        fields.addView(dnsFieldLabel(ctx, getString(R.string.dns_secondary), getString(R.string.k2go_dns_optional)));
        final EditText secondary = dnsInput(ctx);
        fields.addView(secondary);

        // ADFA-5346: filled M3 button via the shared style overlay (no drawable / color recipe in code).
        final com.google.android.material.button.MaterialButton accept = new com.google.android.material.button.MaterialButton(
                new android.view.ContextThemeWrapper(ctx, R.style.ThemeOverlay_K2Go_Button_Filled), null);
        accept.setText(getString(R.string.dns_accept));
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
        final TextView statusText = K2GoText.body(ctx, "", R.color.k2go_muted);
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
                org.appdevforall.k2go.network.domain.DnsConfig def =
                        org.appdevforall.k2go.network.domain.DnsConfig.defaults();
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
    //      List -> per-service screen (K2GO-552: auth.presentation.ServicePasswordSection). ----
    private void buildAuthentication(Context ctx, LinearLayout list) {
        SettingsUi.caption(ctx, list, getString(R.string.k2go_auth_list_hint));
        // K2GO-552: when no service has an admin account it can manage now (no account, stopped or not
        // installed), the factory reminder has nothing to remind about, so its switch locks. The lock
        // reads only the last live answer of each row; a row that asks again (a tap) reports again.
        final java.util.List<String> services = org.appdevforall.k2go.auth.domain.AuthServices.ALL;
        final java.util.Map<String, Boolean> hasAccount = new java.util.HashMap<>();
        final java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Boolean>> setLocked =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (String id : services) {
            authServiceRow(ctx, list, id, getString(org.appdevforall.k2go.auth.presentation.AuthScreenNames.of(id)),
                    org.appdevforall.k2go.auth.domain.AuthServices.productName(id), has -> {
                        hasAccount.put(id, has);
                        if (hasAccount.size() == services.size() && setLocked.get() != null) {
                            setLocked.get().accept(!hasAccount.containsValue(true));
                        }
                    });
        }
        setLocked.set(factoryReminderRow(ctx, list));   // before any answer: the answers come on a later main-thread turn
    }

    /** K2GO-552: turns the start-up "factory password in use" reminder back on (or off). Turning it
     *  off at start-up asks for a confirmation first (AuthNoticeController).
     *  Returns the lock: locked shows the switch off and disabled without changing the stored choice;
     *  unlocked shows the stored choice again. */
    private java.util.function.Consumer<Boolean> factoryReminderRow(Context ctx, LinearLayout list) {
        final org.appdevforall.k2go.auth.data.FactoryReminderStore store =
                new org.appdevforall.k2go.auth.data.FactoryReminderStore(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = SettingsUi.dp(ctx, 16);
        row.setPadding(p16, p16, p16, p16);
        row.addView(K2GoText.body(ctx, getString(R.string.k2go_auth_reminder_switch), R.color.k2go_ink),
                new LinearLayout.LayoutParams(0, -2, 1f));
        final MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setMinimumHeight(0);
        sw.setChecked(!store.isOff());
        final boolean[] reverting = {false};
        sw.setOnCheckedChangeListener((b, on) -> {
            if (reverting[0]) return;
            if (on) { store.turnOn(); return; }
            // Off asks first, like "Don't show again" at start; "Keep reminders" puts the switch back.
            org.appdevforall.k2go.auth.presentation.AuthNoticeController.confirmStop(ctx, () -> { }, () -> {
                reverting[0] = true;
                sw.setChecked(true);
                reverting[0] = false;
            });
        });
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = SettingsUi.dp(ctx, 16);
        list.addView(row, lp);
        return locked -> {
            reverting[0] = true;
            sw.setChecked(!locked && !store.isOff());
            reverting[0] = false;
            sw.setEnabled(!locked);
            row.setAlpha(locked ? 0.5f : 1f);
        };
    }

    /** A navigable row per service with a state badge (Default / Custom / Not running / Not
     *  installed). A service that does not answer is dimmed, and a tap asks only that service again:
     *  no timer, and no new sign-in to the other services (a failed one counts toward a lock). */
    private void authServiceRow(Context ctx, LinearLayout list, String service, String name, String platform,
                                java.util.function.Consumer<Boolean> hasAccount) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = SettingsUi.dp(ctx, 16);   // card padding on the 4dp grid, matching SettingsUi.baseRow
        row.setPadding(p16, p16, p16, p16);
        row.setClickable(true);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = SettingsUi.dp(ctx, 8);
        list.addView(row, rlp);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(K2GoText.body(ctx, name, R.color.k2go_ink));
        col.addView(K2GoText.label(ctx, platform, R.color.k2go_muted));
        row.addView(col);

        // K2GO-385 (pill roles Q4): the service state (Default / Custom / Not installed) is a STATUS,
        // so it reads as a dot+text badge, not a filled pill. Colours unchanged -- Default/Not installed
        // neutral, Custom teal. Built once, GONE until the async probe resolves.
        final LinearLayout badge = K2GoStatusBadge.create(ctx, "", R.color.k2go_muted);
        badge.setVisibility(View.GONE);
        row.addView(badge);

        TextView chev = new TextView(ctx);
        chev.setText("›");
        chev.setTextSize(18);
        chev.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        row.addView(chev);

        final Runnable[] resolve = {null};
        resolve[0] = () -> {
            row.setAlpha(0.5f);
            badge.setVisibility(View.GONE);
            row.setOnClickListener(null);   // opens only after the service answers
            probeService(service, ev -> {
                if (!isAdded()) return;
                boolean reachable = ev == org.appdevforall.k2go.system.domain.PlatformPresence.Evidence.PRESENT;
                row.setAlpha(reachable ? 1f : 0.5f);
                if (!reachable) {
                    // The shared probe's answer: a 404 is "not installed"; no answer is "not running".
                    boolean absent = ev == org.appdevforall.k2go.system.domain.PlatformPresence.Evidence.ABSENT;
                    setBadge(badge, getString(absent ? R.string.k2go_auth_chip_notinstalled
                            : R.string.k2go_auth_chip_not_running), R.color.k2go_muted);
                    row.setOnClickListener(v -> resolve[0].run());
                    hasAccount.accept(false);
                    return;
                }
                row.setOnClickListener(v -> openSub("auth:" + service));
            // K2GO-552: the badge comes from a live sign-in, so a password changed inside the service shows.
                new org.appdevforall.k2go.auth.data.PasswordRepositoryImpl().status(service,
                        new org.appdevforall.k2go.auth.domain.PasswordRepository.Callback<org.appdevforall.k2go.auth.domain.PasswordState>() {
                    @Override public void onResult(org.appdevforall.k2go.auth.domain.PasswordState st) {
                        if (!isAdded()) return;
                        hasAccount.accept(st.hasAccount());
                        if (st.sync == org.appdevforall.k2go.auth.domain.PasswordState.Sync.MISMATCH) {
                            setBadge(badge, getString(R.string.k2go_auth_chip_mismatch), R.color.k2go_clay);
                        } else if (st.sync == org.appdevforall.k2go.auth.domain.PasswordState.Sync.NO_ACCOUNT) {
                            // K2GO-552: no admin account, nothing to manage: the row says so and does not open.
                            row.setAlpha(0.5f);
                            chev.setVisibility(View.GONE);
                            row.setOnClickListener(v -> org.appdevforall.k2go.util.Snackbars.make(v,
                                    getString(R.string.k2go_auth_no_account_snack, name, platform)).show());
                            setBadge(badge, getString(R.string.k2go_auth_chip_no_account), R.color.k2go_muted);
                        } else if (st.usesFactoryPassword()) {
                            setBadge(badge, getString(R.string.k2go_auth_chip_default), R.color.k2go_muted);
                        } else if (st.sync == org.appdevforall.k2go.auth.domain.PasswordState.Sync.OK) {
                            setBadge(badge, getString(R.string.k2go_auth_chip_custom), R.color.k2go_teal);
                        }
                    }
                    @Override public void onFailure(org.appdevforall.k2go.auth.domain.PasswordRepository.Failure f) {
                        /* leave the badge hidden on a load error */
                        if (isAdded()) hasAccount.accept(true);   // unknown: never lock the reminder on a guess
                    }
                });
            });
        };
        resolve[0].run();
    }

    /** Style + reveal the service state badge (Default / Custom / Not installed). */
    private void setBadge(LinearLayout badge, String text, int colorRes) {
        K2GoStatusBadge.style(badge, text, colorRes);
        badge.setVisibility(View.VISIBLE);
    }

    private void buildServiceAuth(Context ctx, LinearLayout list, String service) {
        final int subRes = "calibre".equals(service) ? R.string.k2go_auth_sub_books
                : "forgejo".equals(service) ? R.string.k2go_auth_sub_hostcode   // K2GO-417
                : R.string.k2go_auth_sub_courses;
        SettingsUi.caption(ctx, list, getString(subRes));
        // K2GO-552: change, repair and reset the admin password inside the service itself.
        org.appdevforall.k2go.auth.presentation.ServicePasswordSection.build(this, list, service);
    }

    // ---- Hotspot (K2GO-127): the local Wi-Fi Direct hotspot's name, password and band. Same
    //      custom-switch / revert pattern as Authentication, but wired to the local
    //      HotspotCredentialsStore (no box): off = the shared factory default, on = owner-set. ----
    private void buildHotspot(Context ctx, LinearLayout list) {
        if (!org.appdevforall.k2go.hotspot.LocalHotspotManager.supportsFixedCredentials(ctx)) {
            // Below Android 10 (or no Wi-Fi Direct) the hotspot uses LocalOnlyHotspot with random
            // credentials, so a custom name/password/band does not apply. Say so instead of offering
            // controls that would silently do nothing.
            SettingsUi.caption(ctx, list, getString(R.string.k2go_hotspot_unsupported));
            return;
        }
        final org.appdevforall.k2go.hotspot.data.HotspotCredentialsStore store =
                new org.appdevforall.k2go.hotspot.data.HotspotCredentialsStore(ctx);

        SettingsUi.caption(ctx, list, getString(R.string.k2go_hotspot_hint));

        // The single control: "Use a custom name and password".
        LinearLayout toggleRow = new LinearLayout(ctx);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);
        toggleRow.setBackgroundResource(R.drawable.k2go_card_bg);
        int p16 = SettingsUi.dp(ctx, 16);
        toggleRow.setPadding(p16, p16, p16, p16);
        toggleRow.addView(K2GoText.body(ctx, getString(R.string.k2go_hotspot_custom_toggle), R.color.k2go_ink),
                new LinearLayout.LayoutParams(0, -2, 1f));
        final MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setMinimumHeight(0);
        toggleRow.addView(sw, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams trlp = new LinearLayout.LayoutParams(-1, -2);
        trlp.topMargin = SettingsUi.dp(ctx, 16);
        list.addView(toggleRow, trlp);

        // OFF group: the default name + password, read-only, plus a hint.
        final LinearLayout offGroup = new LinearLayout(ctx);
        offGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams oglp = new LinearLayout.LayoutParams(-1, -2);
        oglp.topMargin = SettingsUi.dp(ctx, 16);
        list.addView(offGroup, oglp);

        final TextView defaultLine = K2GoText.body(ctx, getString(R.string.k2go_hotspot_default_line,
                        org.appdevforall.k2go.hotspot.domain.HotspotCredentials.name(store.defaultSuffix()),
                        org.appdevforall.k2go.hotspot.domain.HotspotCredentials.DEFAULT_PASSWORD), R.color.k2go_ink);
        offGroup.addView(defaultLine);
        TextView offHint = K2GoText.label(ctx, getString(R.string.k2go_hotspot_default_hint), R.color.k2go_muted);
        LinearLayout.LayoutParams ohlp = new LinearLayout.LayoutParams(-1, -2);
        ohlp.topMargin = SettingsUi.dp(ctx, 12);
        offHint.setLayoutParams(ohlp);
        offGroup.addView(offHint);

        // ON group: editable name suffix + password.
        final LinearLayout fields = new LinearLayout(ctx);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setVisibility(View.GONE);
        list.addView(fields, new LinearLayout.LayoutParams(-1, -2));

        fields.addView(dnsFieldLabel(ctx, getString(R.string.k2go_hotspot_name_label),
                org.appdevforall.k2go.hotspot.domain.HotspotCredentials.PREFIX));
        final EditText nameSuffix = dnsInput(ctx);
        nameSuffix.setHint(getString(R.string.k2go_hotspot_name_hint));
        fields.addView(nameSuffix);

        fields.addView(dnsFieldLabel(ctx, getString(R.string.k2go_hotspot_password_label), ""));
        final EditText password = dnsInput(ctx);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        attachEyeToggle(password);
        fields.addView(password);

        final com.google.android.material.button.MaterialButton save =
                new com.google.android.material.button.MaterialButton(
                        new android.view.ContextThemeWrapper(ctx, R.style.ThemeOverlay_K2Go_Button_Filled), null);
        save.setText(getString(R.string.k2go_hotspot_save));
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(-1, -2);
        svlp.topMargin = SettingsUi.dp(ctx, 16);
        fields.addView(save, svlp);

        final TextView reset = K2GoText.label(ctx, getString(R.string.k2go_hotspot_reset), R.color.k2go_teal);
        reset.setClickable(true);
        reset.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(-1, -2);
        rslp.topMargin = SettingsUi.dp(ctx, 12);
        reset.setLayoutParams(rslp);
        fields.addView(reset);

        final TextView status = K2GoText.label(ctx, "", R.color.k2go_muted);
        status.setVisibility(View.GONE);
        fields.addView(status);

        // Prefill the editable fields: custom values if set, else the current defaults.
        nameSuffix.setText(store.isCustom() && !store.customSuffix().isEmpty()
                ? store.customSuffix() : store.defaultSuffix());
        password.setText(store.isCustom() && !store.customPassphrase().isEmpty()
                ? store.customPassphrase()
                : org.appdevforall.k2go.hotspot.domain.HotspotCredentials.DEFAULT_PASSWORD);

        final boolean[] suppress = {false};
        sw.setOnCheckedChangeListener((b, on) -> {
            fields.setVisibility(on ? View.VISIBLE : View.GONE);
            offGroup.setVisibility(on ? View.GONE : View.VISIBLE);
            if (on || suppress[0]) return;
            // Revert to the default; the now-visible default group is the confirmation (the status view
            // lives inside the hidden fields group, so writing it here would never be seen).
            store.clearCustom();
        });

        save.setOnClickListener(v -> {
            String suffix = nameSuffix.getText().toString().trim();
            String pass = password.getText().toString();
            if (org.appdevforall.k2go.hotspot.domain.HotspotCredentials.nameProblem(suffix)
                    != org.appdevforall.k2go.hotspot.domain.HotspotCredentials.Problem.NONE) {
                setStatus(status, getString(R.string.k2go_hotspot_err_name), R.color.k2go_clay);
                return;
            }
            if (org.appdevforall.k2go.hotspot.domain.HotspotCredentials.passwordProblem(pass)
                    != org.appdevforall.k2go.hotspot.domain.HotspotCredentials.Problem.NONE) {
                setStatus(status, getString(R.string.k2go_hotspot_err_password), R.color.k2go_clay);
                return;
            }
            // Entering the factory default values is not a customization: revert instead of marking custom.
            if (suffix.equals(store.defaultSuffix())
                    && pass.equals(org.appdevforall.k2go.hotspot.domain.HotspotCredentials.DEFAULT_PASSWORD)) {
                store.clearCustom();
            } else {
                store.setCustom(suffix, pass);
            }
            setStatus(status, getString(R.string.k2go_hotspot_saved), R.color.k2go_teal);
        });

        reset.setOnClickListener(v -> sw.setChecked(false));   // the switch listener does the revert

        // Initial state: switch reflects default (off) vs custom (on).
        suppress[0] = true;
        sw.setChecked(store.isCustom());
        suppress[0] = false;
        fields.setVisibility(store.isCustom() ? View.VISIBLE : View.GONE);
        offGroup.setVisibility(store.isCustom() ? View.GONE : View.VISIBLE);

        // ---- Wi-Fi band: 2.4 GHz / 5 GHz / Automatic (default), via a single-choice dialog. ----
        SettingsUi.caption(ctx, list, getString(R.string.k2go_hotspot_band_hint));
        final LinearLayout bandRow = new LinearLayout(ctx);
        bandRow.setOrientation(LinearLayout.HORIZONTAL);
        bandRow.setGravity(Gravity.CENTER_VERTICAL);
        bandRow.setBackgroundResource(R.drawable.k2go_card_bg);
        bandRow.setPadding(p16, p16, p16, p16);
        bandRow.setClickable(true);
        bandRow.addView(K2GoText.body(ctx, getString(R.string.k2go_hotspot_band_label), R.color.k2go_ink),
                new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView bandValue = K2GoText.body(ctx, bandLabel(store.band()), R.color.k2go_teal);
        bandRow.addView(bandValue);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(-1, -2);
        brlp.topMargin = SettingsUi.dp(ctx, 8);
        list.addView(bandRow, brlp);
        bandRow.setOnClickListener(v -> {
            final org.appdevforall.k2go.hotspot.domain.HotspotBand[] bands =
                    org.appdevforall.k2go.hotspot.domain.HotspotBand.values();
            final String[] items = new String[bands.length];
            for (int i = 0; i < bands.length; i++) items[i] = bandLabel(bands[i]);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.k2go_hotspot_band_label))
                    .setSingleChoiceItems(items, store.band().ordinal(), (d, which) -> {
                        store.setBand(bands[which]);
                        bandValue.setText(bandLabel(bands[which]));
                        d.dismiss();
                    })
                    .show();
        });
    }

    private String bandLabel(org.appdevforall.k2go.hotspot.domain.HotspotBand band) {
        switch (band) {
            case FIVE_GHZ:
                return getString(R.string.k2go_hotspot_band_5);
            case AUTO:
                return getString(R.string.k2go_hotspot_band_auto);
            default:
                return getString(R.string.k2go_hotspot_band_24);
        }
    }

    private void attachEyeToggle(final EditText field) { org.appdevforall.k2go.ui.K2GoInputs.attachEyeToggle(field); }

    /** K2GO-552: the service page through the shared probe (the same rule as Home), on a worker
     *  thread; the answer comes back on the main thread. */
    private void probeService(String service,
                              java.util.function.Consumer<org.appdevforall.k2go.system.domain.PlatformPresence.Evidence> cb) {
        final String endpoint = org.appdevforall.k2go.auth.domain.AuthServices.homeEndpoint(service);
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        org.appdevforall.k2go.util.AppExecutors.get().io().execute(() -> {
            final org.appdevforall.k2go.system.domain.PlatformPresence.Evidence ev =
                    org.appdevforall.k2go.system.data.PlatformProbe.probe(endpoint);
            main.post(() -> cb.accept(ev));
        });
    }

    private void setStatus(TextView status, String text, int colorRes) {
        status.setText(text);
        status.setTextColor(ContextCompat.getColor(requireContext(), colorRes));
        status.setVisibility(View.VISIBLE);
    }

    private View dnsFieldLabel(Context ctx, String label, String hintRight) {
        return org.appdevforall.k2go.ui.K2GoInputs.fieldLabel(ctx, label, hintRight);
    }

    private EditText dnsInput(Context ctx) { return org.appdevforall.k2go.ui.K2GoInputs.textInput(ctx); }

    private String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
