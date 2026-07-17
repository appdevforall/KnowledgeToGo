package org.iiab.controller.redesign;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
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

    static void preview(Context c, LinearLayout list, String title, String subtitle) {
        LinearLayout row = baseRow(c, list);
        row.setAlpha(0.5f);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(title(c, title));
        if (subtitle != null && !subtitle.isEmpty()) col.addView(sub(c, subtitle));
        row.addView(col);
        row.addView(sub(c, "Soon"));
    }

    static void toggle(Context c, LinearLayout list, String titleText, boolean checked, OnToggle cb) {
        LinearLayout row = baseRow(c, list);
        TextView t = title(c, titleText);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(t);
        SwitchCompat sw = new SwitchCompat(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, isChk) -> cb.changed(isChk));
        row.addView(sw);
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
