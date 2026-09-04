/*
 * ============================================================================
 * Name        : K2GoChip.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-385. The shared outlined status/meta chip, so the same small pill is defined
 *               ONCE instead of being copied per fragment. A transparent, rounded, colored-outline
 *               Material 3 LabelMedium pill (ADFA-4958 §5.4: a filled teal-on-teal chip was
 *               invisible, hence the outline). Pure UI; no domain/data dependencies.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

public final class K2GoChip {

    private K2GoChip() {}

    /**
     * Build a chip laid out for a horizontal meta-chip row: WRAP content, an 8dp end margin, and
     * the shared outline applied. Callers add it to a {@link LinearLayout} row.
     */
    public static TextView create(Context context, CharSequence text, @ColorRes int colorRes) {
        float d = context.getResources().getDisplayMetrics().density;
        TextView t = new TextView(context);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        int hp = Math.round(10 * d), vp = Math.round(5 * d);
        t.setPadding(hp, vp, hp, vp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(8 * d);
        t.setLayoutParams(lp);
        style(t, text, colorRes);
        return t;
    }

    /**
     * (Re)apply a chip's text and outlined color in place, without rebuilding the view (ADFA-5026:
     * a live status pill recolors between checking/up-to-date/update states as it refreshes).
     */
    public static void style(TextView chip, CharSequence text, @ColorRes int colorRes) {
        float d = chip.getResources().getDisplayMetrics().density;
        int color = ContextCompat.getColor(chip.getContext(), colorRes);
        chip.setText(text);
        chip.setTextColor(color);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(11 * d);
        bg.setStroke(Math.max(1, Math.round(1.4f * d)), color);
        chip.setBackground(bg);
    }
}
