/*
 * ============================================================================
 * Name        : K2GoChip.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-385. The shared metadata TAG (pill-roles design decision, PR3): a quiet,
 *               read-only, non-interactive label for facts -- version, size, "REST API", "System
 *               core" -- and coloured trait badges that carry valence ("Runs offline" = leaf). An
 *               8dp-corner, monospace, transparent-outline Material 3 tag; the 8dp corner + mono +
 *               no-dot mark it as data, distinct from the dot+text status (K2GoStatusBadge) and the
 *               stadium action button. Colour is neutral by default, semantic only for valence
 *               (the metadata colour rule). Pure UI; no domain/data dependencies.
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

import org.appdevforall.k2go.R;

public final class K2GoChip {

    private K2GoChip() {}

    /** The neutral tag colour. Metadata is neutral by default (the metadata colour rule); pass an
     *  explicit colour to the 3-arg overloads only for a valence trait (e.g. leaf "Runs offline"). */
    @ColorRes private static final int NEUTRAL = R.color.k2go_muted;

    /** A neutral metadata tag (version, size, "REST API", "System core") -- the common case. */
    public static TextView create(Context context, CharSequence text) {
        return create(context, text, NEUTRAL);
    }

    /** Re-apply a neutral metadata tag's text/colour in place. */
    public static void style(TextView chip, CharSequence text) {
        style(chip, text, NEUTRAL);
    }

    /**
     * Build a chip laid out for a horizontal meta-chip row: WRAP content, an 8dp end margin, and
     * the shared outline applied. Callers add it to a {@link LinearLayout} row.
     */
    public static TextView create(Context context, CharSequence text, @ColorRes int colorRes) {
        float d = context.getResources().getDisplayMetrics().density;
        TextView t = new TextView(context);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);   // reads as a quiet data tag (the "mono" cue)
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
        bg.setCornerRadius(8 * d);   // metadata tag corner (was 11dp) -- 8dp signals "tag", not a stadium pill
        bg.setStroke(Math.max(1, Math.round(1.4f * d)), color);
        chip.setBackground(bg);
    }
}
