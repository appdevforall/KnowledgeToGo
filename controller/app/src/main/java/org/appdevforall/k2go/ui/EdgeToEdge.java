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
     * Activities that manage their own system-bar insets, that want none (a full-screen splash),
     * or whose layout already consumes insets (android:fitsSystemWindows, or an inset listener of
     * their own) implement this to opt out of the global {@link EdgeToEdgeCallbacks} handler, so the
     * insets are never applied twice.
     */
    public interface SelfManaged {}

    /** Pad the view's top by the status-bar (and top cutout) inset. */
    public static void padTop(View v) { apply(v, true, false, false, false, false); }

    /** Pad the view's bottom by the navigation-bar inset. */
    public static void padBottom(View v) { apply(v, false, false, false, true, false); }

    /** Pad the view on every system-bar edge (top, bottom, and the sides for cutouts/landscape). */
    public static void padAll(View v) { apply(v, true, true, true, true, false); }

    /**
     * Like {@link #padAll} but the bottom edge also clears the soft keyboard (IME), so a focused
     * field is not hidden. Use for a screen's own content, not for a bottom bar that should stay put.
     */
    public static void padAllAndIme(View v) { apply(v, true, true, true, true, true); }

    private static void apply(final View v, final boolean top, final boolean left,
                              final boolean right, final boolean bottom, final boolean ime) {
        if (v == null) return;
        // Capture the view's own padding once so repeated inset passes (e.g. rotation) never accumulate.
        final int pl = v.getPaddingLeft();
        final int pt = v.getPaddingTop();
        final int pr = v.getPaddingRight();
        final int pb = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            int mask = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
            if (ime) mask |= WindowInsetsCompat.Type.ime();
            Insets bars = insets.getInsets(mask);
            int[] p = resolvePadding(pl, pt, pr, pb, bars.left, bars.top, bars.right, bars.bottom,
                    top, left, right, bottom);
            view.setPadding(p[0], p[1], p[2], p[3]);
            return insets;
        });
        // Insets may have already been delivered before the listener was attached; request a fresh pass.
        ViewCompat.requestApplyInsets(v);
    }

    /**
     * Pure rule: the new [left, top, right, bottom] padding = the original padding plus the inset on
     * each selected edge. Adds to the captured original (never to the already-padded value), so an
     * inset pass is idempotent. Package-private for unit testing.
     */
    static int[] resolvePadding(int pl, int pt, int pr, int pb,
                                int il, int it, int ir, int ib,
                                boolean top, boolean left, boolean right, boolean bottom) {
        return new int[]{
                left ? pl + il : pl,
                top ? pt + it : pt,
                right ? pr + ir : pr,
                bottom ? pb + ib : pb,
        };
    }
}
