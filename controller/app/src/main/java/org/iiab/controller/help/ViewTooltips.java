/*
 * ============================================================================
 * Name        : ViewTooltips.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One-line wiring helper: attach a three-tier tooltip to a view
 *               so a long-press shows it. (Java analog of Code On the Go's
 *               View.displayTooltipOnLongPress extension.)
 * ============================================================================
 */
package org.iiab.controller.help;

import android.view.View;

public final class ViewTooltips {

    private ViewTooltips() {}

    /**
     * Show the tooltip for {@code tag} when {@code view} is long-pressed.
     * Safe to call with a null view (no-op).
     */
    public static void attachLongPress(final View view, final String category, final String tag) {
        if (view == null) return;
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                TooltipManager.showTooltip(v.getContext(), v, category, tag);
                return true;
            }
        });
    }
}
