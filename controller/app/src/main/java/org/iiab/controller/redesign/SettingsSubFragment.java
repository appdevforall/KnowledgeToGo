package org.iiab.controller.redesign;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import java.util.List;
import org.iiab.controller.R;
import org.iiab.controller.applang.data.AppLocaleController;
import org.iiab.controller.applang.data.ContentLanguage;
import org.iiab.controller.applang.domain.AppLanguage;
import org.iiab.controller.applang.domain.SupportedAppLanguages;
import org.iiab.controller.delivery.data.AnalyticsConsent;

/** Settings sub-screen host: Language (functional — UI + content), About (version, permissions,
 *  usage-stats consent), Advanced (power-user features, preview for now). Keeps the bottom nav;
 *  the ‹ back returns to the Settings top level. */
public class SettingsSubFragment extends Fragment {

    private static final String ARG = "screen";

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

    // ---- Language: one choice sets BOTH the app UI locale and the content language ----
    private void buildLanguage(Context ctx, LinearLayout list) {
        SettingsUi.caption(ctx, list, "Sets the app language and the default content language.");
        List<AppLanguage> langs = SupportedAppLanguages.all(getString(R.string.setup_app_lang_system));
        String current = AppLocaleController.currentTag();
        for (AppLanguage lang : langs) {
            boolean isCurrent = lang.tag().equals(current);
            SettingsUi.row(ctx, list, lang.toString(), null, isCurrent ? "✓" : null,
                    v -> applyLanguage(ctx, lang.tag()));
        }
    }

    private void applyLanguage(Context ctx, String tag) {
        // Content language = the base subtag (ru-RU -> ru), normalized to the Kiwix form;
        // empty tag (follow system) -> derive from the system locale.
        String content = (tag == null || tag.isEmpty())
                ? ContentLanguage.systemDefault()
                : ContentLanguage.normalize(tag.split("-")[0]);
        ctx.getSharedPreferences(getString(R.string.pref_file_internal), Context.MODE_PRIVATE)
                .edit().putString("selected_lang_minimal", content).apply();
        // Applying the UI locale recreates the activities (AppCompat persists the choice).
        AppLocaleController.apply(tag);
    }

    // ---- About ----
    private void buildAbout(Context ctx, LinearLayout list) {
        SettingsUi.infoRow(ctx, list, "App version", versionName(ctx));
        SettingsUi.row(ctx, list, "Permissions", null, null, v -> openAppSettings(ctx));
        SettingsUi.toggle(ctx, list, "Share usage statistics", AnalyticsConsent.isEnabled(ctx), checked -> {
            AnalyticsConsent.setEnabled(ctx, checked);
            org.iiab.controller.analytics.AnalyticsClient.with(ctx).applyConsent();
        });
        SettingsUi.preview(ctx, list, "Open-source licenses", null);
        SettingsUi.preview(ctx, list, "Privacy", null);
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
        SettingsUi.caption(ctx, list, "For power users.");
        SettingsUi.sectionHeader(ctx, list, "SYSTEM");
        SettingsUi.preview(ctx, list, "Module management", "Tier, add modules, hide");
        SettingsUi.preview(ctx, list, "Backups & recovery", null);
        SettingsUi.sectionHeader(ctx, list, "DEVELOPER");
        SettingsUi.preview(ctx, list, "ADB", null);
        SettingsUi.preview(ctx, list, "Network & DNS", null);
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
