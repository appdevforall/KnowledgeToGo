/*
 * ============================================================================
 * Name        : M3Text.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5027. Apply a Material 3 type-scale role to a TextView.
 * ============================================================================
 */
package org.appdevforall.k2go.util;

import android.widget.TextView;

/**
 * Puts overlay/native text on the M3 type scale (Title/Body/Label roles via a
 * {@code TextAppearance_Material3_*} style) instead of fixed sp sizes + manual
 * bold. {@code setTextAppearance} also sets a colour, so the theme colour is
 * re-applied afterwards (see ADFA-4961). Shared so the native overlays don't
 * each grow their own copy of this helper.
 */
public final class M3Text {

    private M3Text() {}

    /**
     * @param t            target view
     * @param appearanceRes an M3 {@code TextAppearance_Material3_*} style
     * @param color        theme colour to keep after the appearance is applied
     */
    public static void apply(TextView t, int appearanceRes, int color) {
        t.setTextAppearance(appearanceRes);
        t.setTextColor(color);
    }
}
