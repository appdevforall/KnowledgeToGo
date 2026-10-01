/*
 * ============================================================================
 * Name        : EdgeToEdge.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Shared system-bar inset handling (K2GO-439). targetSdk 35 forces
 *               edge-to-edge on Android 15+, so app content draws behind the
 *               status bar and the navigation bar. Each screen pads the right
 *               views by the system-bar (and display-cutout) insets so content
 *               keeps clear of the bars. Single source for the rule; the call
 *               site is per-screen because the activities share no base class.
 *               See https://developer.android.com/develop/ui/views/layout/edge-to-edge
 * ============================================================================
 */
package org.appdevforall.k2go.ui;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Stateless helper that pads a view by the system-bar insets, preserving its original padding. */
public final class EdgeToEdge {

    private EdgeToEdge() {}

    /**
     * Activities that manage their own system-bar insets, or want none (immersive, splash),
     * implement this to opt out of the global {@link EdgeToEdgeCallbacks} handler.
     */
    public interface SelfManaged {}

    /** Pad the view's top by the status-bar (and top cutout) inset. */
    public static void padTop(View v) { apply(v, true, false, false, false); }

    /** Pad the view's bottom by the navigation-bar inset. */
    public static void padBottom(View v) { apply(v, false, false, false, true); }

    /** Pad the view on every system-bar edge (top, bottom, and the sides for cutouts/landscape). */
    public static void padAll(View v) { apply(v, true, true, true, true); }

    private static void apply(final View v, final boolean top, final boolean left,
                              final boolean right, final boolean bottom) {
        if (v == null) return;
        // Capture the view's own padding once so repeated inset passes (e.g. rotation) never accumulate.
        final int pl = v.getPaddingLeft();
        final int pt = v.getPaddingTop();
        final int pr = v.getPaddingRight();
        final int pb = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(
                    left ? pl + bars.left : pl,
                    top ? pt + bars.top : pt,
                    right ? pr + bars.right : pr,
                    bottom ? pb + bars.bottom : pb);
            return insets;
        });
        // Insets may have already been delivered before the listener was attached; request a fresh pass.
        ViewCompat.requestApplyInsets(v);
    }
}
