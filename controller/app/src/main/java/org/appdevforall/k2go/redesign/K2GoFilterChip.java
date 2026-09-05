/*
 * ============================================================================
 * Name        : K2GoFilterChip.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-385 (PR3). The shared FILTER CHIP (pill-roles design decision, board
 *               k2go-chip-vs-button-v1): a selectable/toggle chip -- Popular / Educational, a ZIM
 *               category, a sort order. Per the decision it is an 8dp-corner, ~32dp Material 3 chip
 *               with a leading check when selected -- "8dp corner + check = toggle", distinct on
 *               purpose from the stadium action button, the dot+text status and the 8dp metadata
 *               tag. Built ONCE here instead of the three drifted per-screen chip builders it
 *               replaces (ZimLanding / ZimCategory / BooksLanding). Colour is the app teal (filled +
 *               on-teal text when selected; transparent + teal outline + teal text when not). Pure
 *               UI; no domain/data dependencies.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

import androidx.core.content.ContextCompat;

import com.google.android.material.chip.Chip;

import org.appdevforall.k2go.R;

public final class K2GoFilterChip {

    private K2GoFilterChip() {}

    /**
     * Build a selectable filter chip. {@code selected} sets the checked look (teal fill + check);
     * {@code onClick} fires on tap -- the caller owns the selection model (usually rebuilding the
     * row), so the chip is a view of that state, not the source of truth.
     */
    public static Chip create(Context ctx, CharSequence label, boolean selected, View.OnClickListener onClick) {
        Chip chip = new Chip(ctx);
        chip.setText(label);
        style(chip, selected);
        chip.setOnClickListener(onClick);
        return chip;
    }

    /**
     * Apply the filter-chip look + selected state to an EXISTING chip (e.g. one inflated from XML,
     * like the ZimCategory sort chips). Idempotent -- safe to call on every render. The caller owns
     * the text (so a sort chip can carry its "By size ▲" direction) and the click.
     */
    public static void style(Chip chip, boolean selected) {
        Context ctx = chip.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;
        chip.setCheckable(true);
        chip.setChecked(selected);
        chip.setCheckedIconVisible(true);            // the "check" cue the design calls for
        // A bare new Chip(ctx) picks up the theme's default (Assist) chipStyle, which has NO checkedIcon,
        // so set a plain check explicitly -- otherwise setCheckedIconVisible shows nothing.
        chip.setCheckedIcon(ContextCompat.getDrawable(ctx, R.drawable.ic_check_16));
        chip.setChipCornerRadius(8 * d);             // 8dp corner = chip/toggle (not a stadium pill)
        chip.setChipMinHeight(32 * d);               // the 32dp step on the 4dp role ladder
        chip.setEnsureMinTouchTargetSize(true);      // keep a >=48dp touch target on the 32dp chip
        chip.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);

        int teal = ContextCompat.getColor(ctx, R.color.k2go_teal);
        int onTeal = ContextCompat.getColor(ctx, R.color.k2go_on_teal);
        int[][] states = { new int[]{ android.R.attr.state_checked }, new int[0] };
        chip.setChipBackgroundColor(new ColorStateList(states, new int[]{ teal, Color.TRANSPARENT }));
        chip.setChipStrokeColor(new ColorStateList(states, new int[]{ Color.TRANSPARENT, teal }));
        chip.setChipStrokeWidth(Math.max(1, Math.round(1.4f * d)));
        chip.setTextColor(new ColorStateList(states, new int[]{ onTeal, teal }));
        chip.setCheckedIconTint(ColorStateList.valueOf(onTeal));
    }

    /**
     * Wire a horizontally-scrollable filter-chip row so it never reads as "the last chip": a soft fade
     * at the scrolled edges (right while there is more to the right, left once scrolled), and on
     * (re)build a scroll that reveals the selected chip with its neighbours peeking instead of pinned
     * flush to an edge. Call after (re)populating the row. K2GO-385 (design k2go-chip-scroll-affordance-v1).
     */
    public static void revealSelected(HorizontalScrollView scroll, ViewGroup row) {
        float d = scroll.getResources().getDisplayMetrics().density;
        scroll.setHorizontalFadingEdgeEnabled(true);
        // 34dp (a touch longer than the default) so the edge fade stays legible for low vision, not so
        // faint it can be missed when a chip happens to end right at the row edge.
        scroll.setFadingEdgeLength(Math.round(34 * d));
        scroll.post(() -> {
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                if (c instanceof Chip && ((Chip) c).isChecked()) {
                    int peek = c.getWidth();   // keep one chip of context so the active chip is not pinned
                    scroll.smoothScrollTo(Math.max(0, c.getLeft() - peek), 0);
                    return;
                }
            }
        });
    }
}
