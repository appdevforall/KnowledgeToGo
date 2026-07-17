package org.iiab.controller.redesign;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import org.iiab.controller.R;

/** Settings tab (ADFA-4725): Basics (Language/Theme/About wired; rest marked as preview) +
 *  Advanced (preview) + Turn off K2Go. Preview rows are visibly non-functional so nothing
 *  looks like a working button that does nothing. */
public class SettingsFragment extends Fragment {

    private LinearLayout list;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_settings, c, false);
        list = root.findViewById(R.id.k2go_settings_list);

        header("BASICS");
        row("Language", "", v -> openLanguageSettings());
        preview("Permissions", "");
        row("Theme", themeLabel(), v -> chooseTheme());
        preview("Share usage statistics", "On");
        row("About", versionName(), v -> showAbout());
        preview("Send feedback", "");

        header("ADVANCED · for power users");
        preview("System & modules", "");
        preview("Backups & recovery", "");
        preview("Developer tools (ADB)", "");
        preview("Network & DNS", "");
        preview("Terminal (Debian)", "");

        turnOffRow();
        return root;
    }

    // ---- wired actions ----

    private void openLanguageSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                startActivity(new Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                        Uri.parse("package:" + requireContext().getPackageName())));
                return;
            }
        } catch (Exception ignore) { /* fall through */ }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + requireContext().getPackageName())));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Language settings unavailable on this device", Toast.LENGTH_SHORT).show();
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

    private void showAbout() {
        new AlertDialog.Builder(requireContext())
                .setTitle("About")
                .setMessage("Knowledge To Go (K2Go)\nVersion " + versionName()
                        + "\n" + requireContext().getPackageName())
                .setPositiveButton("OK", null)
                .show();
    }

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(
                getString(R.string.pref_file_internal), Context.MODE_PRIVATE);
    }

    private String versionName() {
        try {
            return "v" + requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---- rows ----

    private void header(String text) {
        TextView t = new TextView(requireContext());
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_teal));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(4);
        list.addView(t, lp);
    }

    private void row(String title, String value, View.OnClickListener onClick) {
        buildRow(title, value, onClick, false);
    }

    /** A visibly non-functional row: dimmed, tagged "Soon", not clickable. */
    private void preview(String title, String value) {
        buildRow(title, (value == null || value.isEmpty()) ? "Soon" : value + " · Soon", null, true);
    }

    private void buildRow(String title, String value, View.OnClickListener onClick, boolean isPreview) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        if (isPreview) {
            row.setAlpha(0.5f);
        } else {
            row.setClickable(true);
            row.setOnClickListener(onClick);
        }

        TextView t = new TextView(requireContext());
        t.setText(title);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_ink));
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = new TextView(requireContext());
        val.setText(value == null ? "" : value);
        val.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        val.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        row.addView(val, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        list.addView(row, lp);
    }

    private void turnOffRow() {
        TextView note = new TextView(requireContext());
        note.setText("Home/back only minimize — the library keeps running. Turn off to fully stop it.");
        note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        note.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_muted));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(24);
        list.addView(note, np);

        TextView off = new TextView(requireContext());
        off.setText("Turn off K2Go");
        off.setGravity(Gravity.CENTER);
        off.setPadding(dp(16), dp(16), dp(16), dp(16));
        off.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        off.setTextColor(ContextCompat.getColor(requireContext(), R.color.k2go_clay));
        off.setBackgroundResource(R.drawable.k2go_getmore_bg);
        off.setClickable(true);
        off.setOnClickListener(v -> confirmTurnOff());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        list.addView(off, lp);
    }

    private void confirmTurnOff() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Turn off K2Go?")
                .setMessage("This stops the library and closes the app.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Turn off", (d, w) -> {
                    if (getActivity() instanceof LibraryActivity) {
                        ((LibraryActivity) getActivity()).turnOffK2Go();
                    }
                })
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
