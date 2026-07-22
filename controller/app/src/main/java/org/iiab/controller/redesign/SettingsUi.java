package org.iiab.controller.redesign;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.iiab.controller.R;

/** Programmatic row builders shared by the Settings top level and its sub-screens. */
final class SettingsUi {
    private SettingsUi() {}

    interface OnToggle { void changed(boolean checked); }

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static void sectionHeader(Context c, LinearLayout list, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(ContextCompat.getColor(c, R.color.k2go_teal));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(c, 18); lp.bottomMargin = dp(c, 4);
        list.addView(t, lp);
    }

    static void caption(Context c, LinearLayout list, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(ContextCompat.getColor(c, R.color.k2go_muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(c, 2);
        list.addView(t, lp);
    }

    static View row(Context c, LinearLayout list, String title, String subtitle, String value, View.OnClickListener onClick) {
        LinearLayout row = baseRow(c, list);
        if (onClick != null) { row.setClickable(true); row.setOnClickListener(onClick); }
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(title(c, title));
        if (subtitle != null && !subtitle.isEmpty()) col.addView(sub(c, subtitle));
        row.addView(col);
        if (value != null && !value.isEmpty()) {
            TextView v = sub(c, value);
            v.setPadding(0, 0, dp(c, 8), 0);
            row.addView(v);
        }
        if (onClick != null) {
            TextView chev = new TextView(c);
            chev.setText("›");
            chev.setTextSize(18);
            chev.setTextColor(ContextCompat.getColor(c, R.color.k2go_muted));
            row.addView(chev);
        }
        return row;
    }

    /**
     * A labelled language "box" (ADFA-4798): a section label, a bordered value row with a
     * ▾ affordance, and an optional helper line below. Returns the value {@link TextView} so
     * the caller can update it in place after a pick (no full rebuild needed).
     */
    static TextView selector(Context c, LinearLayout list, String label, String value,
                             String helper, View.OnClickListener onClick) {
        TextView lbl = new TextView(c);
        lbl.setText(label);
        lbl.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        lbl.setTextColor(ContextCompat.getColor(c, R.color.k2go_teal));
        lbl.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = dp(c, 18);
        list.addView(lbl, llp);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.k2go_lang_box_bg);
        box.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        if (onClick != null) { box.setClickable(true); box.setFocusable(true); box.setOnClickListener(onClick); }
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(c, 8);
        list.addView(box, blp);

        TextView val = new TextView(c);
        val.setText(value);
        val.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        val.setTextColor(ContextCompat.getColor(c, R.color.k2go_ink));
        val.setTypeface(val.getTypeface(), Typeface.BOLD);
        box.addView(val, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView chev = new TextView(c);
        chev.setText("▾");
        chev.setTextColor(ContextCompat.getColor(c, R.color.k2go_teal));
        chev.setTextSize(18);
        box.addView(chev);

        if (helper != null && !helper.isEmpty()) {
            TextView h = new TextView(c);
            h.setText(helper);
            h.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            h.setTextColor(ContextCompat.getColor(c, R.color.k2go_muted));
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.topMargin = dp(c, 8);
            list.addView(h, hlp);
        }
        return val;
    }

    /** A soft note / callout box (ADFA-4798). */
    static void note(Context c, LinearLayout list, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setTextColor(ContextCompat.getColor(c, R.color.k2go_muted));
        t.setBackgroundResource(R.drawable.k2go_card_bg);
        t.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 20);
        list.addView(t, lp);
    }

    static void preview(Context c, LinearLayout list, String title, String subtitle) {
        LinearLayout row = baseRow(c, list);
        row.setAlpha(0.5f);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(title(c, title));
        if (subtitle != null && !subtitle.isEmpty()) col.addView(sub(c, subtitle));
        row.addView(col);
        row.addView(sub(c, c.getString(R.string.k2go_soon)));
    }

    static void toggle(Context c, LinearLayout list, String titleText, boolean checked, OnToggle cb) {
        LinearLayout row = baseRow(c, list);
        TextView t = title(c, titleText);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(t);
        // ADFA-4802: Material 3 switch. The old SwitchCompat rendered its ON track as
        // colorControlActivated at ~30% alpha, so on the K2Go surfaces the track vanished and
        // only the teal thumb showed (both light and dark). MaterialSwitch fills the ON track
        // (primary) with an onPrimary thumb, so the on/off state is clearly visible in both themes.
        MaterialSwitch sw = new MaterialSwitch(c);
        sw.setChecked(checked);
        sw.setMinimumHeight(0);
        sw.setOnCheckedChangeListener((b, isChk) -> cb.changed(isChk));
        row.addView(sw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    static void infoRow(Context c, LinearLayout list, String titleText, String value) {
        LinearLayout row = baseRow(c, list);
        TextView t = title(c, titleText);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(t);
        row.addView(sub(c, value));
    }

    private static TextView title(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        t.setTextColor(ContextCompat.getColor(c, R.color.k2go_ink));
        return t;
    }
    private static TextView sub(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        t.setTextColor(ContextCompat.getColor(c, R.color.k2go_muted));
        return t;
    }
    private static LinearLayout baseRow(Context c, LinearLayout list) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.k2go_card_bg);
        row.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(c, 8);
        list.addView(row, lp);
        return row;
    }
}
