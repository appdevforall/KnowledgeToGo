/*
 * ============================================================================
 * Name        : K2GoStatusBadge.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-385 (PR3). The shared read-only status badge: a semantic-coloured dot + label,
 *               the pattern LibraryHomeFragment already uses on the home cards. Per the pill-roles
 *               design decision a lifecycle state (Installed / Not installed / Failed / Scheduled)
 *               reads as a STATUS — a dot + text, no ripple — distinct on purpose from an action
 *               button (a tap target) and a metadata tag (neutral facts, see K2GoChip). Built once
 *               here instead of the overloaded ModuleHubFragment.statePill it replaces. Pure UI; no
 *               domain/data dependencies.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;

public final class K2GoStatusBadge {

    private K2GoStatusBadge() {}

    /**
     * Build a [dot + text] status badge for a horizontal row: a 9dp semantic-coloured dot, a 6dp
     * gap, and a Material 3 LabelMedium label in the same colour (dot and text match at AA). Not
     * clickable — a status, not an action. The caller sets the badge's own LayoutParams via
     * {@code addView(badge, params)}.
     */
    public static LinearLayout create(Context context, CharSequence text, @ColorRes int colorRes) {
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout badge = new LinearLayout(context);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(context);
        int size = Math.round(9 * d);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(size, size);
        dp.rightMargin = Math.round(6 * d);
        badge.addView(dot, dp);

        TextView label = new TextView(context);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        badge.addView(label);

        // Self-space for a horizontal chip/flow row (8dp end gap, like K2GoChip); a caller that needs a
        // different margin passes its own LayoutParams to addView, which overrides this.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Math.round(8 * d);
        badge.setLayoutParams(lp);

        style(badge, text, colorRes);
        return badge;
    }

    /**
     * (Re)apply the badge's text and semantic colour in place — recolours the dot fill and the
     * label without rebuilding the view (mirrors {@link K2GoChip#style} for a live-updating status).
     */
    public static void style(LinearLayout badge, CharSequence text, @ColorRes int colorRes) {
        int color = ContextCompat.getColor(badge.getContext(), colorRes);
        View dot = badge.getChildAt(0);
        TextView label = (TextView) badge.getChildAt(1);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        dot.setBackground(circle);
        label.setText(text);
        label.setTextColor(color);
    }
}
