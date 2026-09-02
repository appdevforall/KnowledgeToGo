/*
 * ============================================================================
 * Name        : LogScroll.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-374. The pure decision behind LiveLogPanel's "stick to the bottom only if the user
 *               is already at the bottom" auto-scroll. Extracted from the View so the rule — the fix for
 *               the old panels that yanked the scrollbar to the bottom on every update, fighting a user
 *               reading higher up — is unit-tested on a plain JVM (no android.*).
 * ============================================================================
 */
package org.iiab.controller.widget;

/** Pure helper for a vertical scroll view's "am I at the bottom?" question. */
public final class LogScroll {

    private LogScroll() {}

    /**
     * True when the scroll view is at (or within {@code thresholdPx} of) the bottom of its content, so
     * new content should keep it pinned there. When the content fits the viewport there is nothing to
     * scroll, so it counts as at-bottom; a not-yet-laid-out viewport (height 0) also pins.
     *
     * @param scrollY         current vertical scroll offset, px
     * @param viewportHeight  the scroll view's visible height, px
     * @param contentHeight   the scrolled child's full height, px
     * @param thresholdPx     how close to the bottom still counts as "at bottom", px (negative → 0)
     */
    public static boolean isAtBottom(int scrollY, int viewportHeight, int contentHeight, int thresholdPx) {
        if (viewportHeight <= 0) {
            return true;   // not laid out yet — pin to bottom
        }
        int maxScroll = contentHeight - viewportHeight;
        if (maxScroll <= 0) {
            return true;   // content fits — always "at bottom"
        }
        int threshold = Math.max(0, thresholdPx);
        return scrollY >= maxScroll - threshold;
    }
}
