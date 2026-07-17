package org.iiab.controller.redesign;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import org.iiab.controller.R;
import org.iiab.controller.feedback.presentation.FeedbackFragment;

/** Settings v3 top level: Language · Theme · Help · Send feedback · About · Advanced, with a
 *  pinned "Turn off K2Go" footer that stays visible outside the scroll. Advanced/About/Language
 *  open as sub-screens (bottom nav stays); Theme + Language + Send feedback are functional. */
public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_settings, c, false);
        Context ctx = requireContext();
        LinearLayout list = root.findViewById(R.id.k2go_settings_list);
        LinearLayout footer = root.findViewById(R.id.k2go_settings_footer);

        SettingsUi.row(ctx, list, "Language", null, null, v -> openSub("language"));
        SettingsUi.row(ctx, list, "Theme", null, themeLabel(), v -> chooseTheme());
        SettingsUi.preview(ctx, list, "Help", null);
        SettingsUi.row(ctx, list, "Send feedback", null, null, v -> openFeedback());
        SettingsUi.row(ctx, list, "About", null, versionName(), v -> openSub("about"));
        SettingsUi.row(ctx, list, "Advanced", "System, backups, developer tools", null, v -> openSub("advanced"));

        buildFooter(ctx, footer);
        return root;
    }

    private void openSub(String screen) {
        if (getActivity() instanceof LibraryActivity) {
            ((LibraryActivity) getActivity()).openSettingsSub(SettingsSubFragment.newInstance(screen));
        }
    }

    private void openFeedback() {
        if (getActivity() instanceof LibraryActivity) {
            ((LibraryActivity) getActivity()).openSettingsSub(new FeedbackFragment());
        }
    }

    private void chooseTheme() {
        final String[] labels = {"Light", "Follow system", "Dark"};
        final int[] modes = {
                AppCompatDelegate.MODE_NIGHT_NO,
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
                AppCompatDelegate.MODE_NIGHT_YES};
        new AlertDialog.Builder(requireContext())
                .setTitle("Theme")
                .setItems(labels, (d, w) -> {
                    prefs().edit().putInt("k2go_theme", modes[w]).apply();
                    AppCompatDelegate.setDefaultNightMode(modes[w]);
                })
                .show();
    }

    private String themeLabel() {
        switch (prefs().getInt("k2go_theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)) {
            case AppCompatDelegate.MODE_NIGHT_NO: return "Light";
            case AppCompatDelegate.MODE_NIGHT_YES: return "Dark";
            default: return "Follow system";
        }
    }

    private void buildFooter(Context ctx, LinearLayout footer) {
        TextView note = new TextView(ctx);
        note.setText("K2Go keeps running in the background until you turn it off.");
        note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        note.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_muted));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.bottomMargin = SettingsUi.dp(ctx, 8);
        footer.addView(note, np);

        TextView off = new TextView(ctx);
        off.setText("Turn off K2Go");
        off.setGravity(Gravity.CENTER);
        off.setPadding(SettingsUi.dp(ctx, 16), SettingsUi.dp(ctx, 16), SettingsUi.dp(ctx, 16), SettingsUi.dp(ctx, 16));
        off.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        off.setTextColor(ContextCompat.getColor(ctx, R.color.k2go_clay));
        off.setBackgroundResource(R.drawable.k2go_getmore_bg);
        off.setClickable(true);
        off.setOnClickListener(v -> confirmTurnOff());
        footer.addView(off, new LinearLayout.LayoutParams(-1, -2));
    }

    private void confirmTurnOff() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Turn off K2Go?")
                .setMessage("This stops the library and closes the app. (Home or back only minimize it — it keeps running in the background.)")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Turn off", (d, w) -> {
                    if (getActivity() instanceof LibraryActivity) {
                        ((LibraryActivity) getActivity()).turnOffK2Go();
                    }
                })
                .show();
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(
                getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
    }

    private String versionName() {
        try {
            return requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
