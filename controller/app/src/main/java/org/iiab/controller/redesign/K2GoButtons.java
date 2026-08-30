/*
 * ============================================================================
 * Name        : K2GoButtons.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5346. Shared helpers for the K2Go Material 3 buttons, so the small bits of dynamic
 *               button styling live in ONE place instead of being copied per fragment. The static look
 *               (shape, size, colors) belongs to the styles in themes_k2go.xml + the ThemeOverlays; this
 *               only covers a runtime STATE toggle that a style can't express on its own.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.content.res.ColorStateList;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import org.iiab.controller.R;

public final class K2GoButtons {

    private K2GoButtons() {}

    /**
     * Toggle a filled CTA between full emphasis (filled teal) and low emphasis (teal text, no fill),
     * keeping the button's shape and size from its style. This is a STATE change, not a second style —
     * the "advance / continue" buttons in the Clone and Connect flows use it so the same two lines don't
     * live in two fragments.
     */
    public static void setFilledEmphasis(MaterialButton button, boolean filled) {
        int fill = filled ? R.color.k2go_teal : android.R.color.transparent;
        int text = filled ? R.color.k2go_on_teal : R.color.k2go_teal;
        button.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(button.getContext(), fill)));
        button.setTextColor(ContextCompat.getColor(button.getContext(), text));
    }
}
