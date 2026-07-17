package org.iiab.controller.redesign;

import android.os.Bundle;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import org.iiab.controller.R;

/** Settings tab (ADFA-4725): Basics (About live) + Advanced (visual) + Turn off K2Go. */
public class SettingsFragment extends Fragment {

    private LinearLayout list;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup c, @Nullable Bundle s) {
        View root = inflater.inflate(R.layout.fragment_k2go_settings, c, false);
        list = root.findViewById(R.id.k2go_settings_list);

        View.OnClickListener soon = v ->
                Toast.makeText(requireContext(), "Coming soon", Toast.LENGTH_SHORT).show();

        header("BASICS");
        row("Language", "", soon);
        row("Permissions", "", soon);
        row("Theme", "Light / Follow system / Dark", soon);
        row("Share usage statistics", "On", soon);
        row("About", versionName(), soon);
        row("Send feedback", "", soon);

        header("ADVANCED · for power users");
        row("System & modules", "", soon);
        row("Backups & recovery", "", soon);
        row("Developer tools (ADB)", "", soon);
        row("Network & DNS", "", soon);
        row("Terminal (Debian)", "", soon);

        turnOffRow();
        return root;
    }

    private String versionName() {
        try {
            return "v" + requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

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
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(dp(14), dp(14), dp(14), dp(14));
        row.setClickable(true);
        row.setOnClickListener(onClick);

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
