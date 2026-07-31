/*
 * ============================================================================
 * Name        : EllipsisAnimator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4842. Reusable animated "…" on a TextView's status line, so a long wait (server
 *               starting, indexing, booting) never looks frozen. Cycles 0..3 trailing dots on a fixed
 *               base label at a steady cadence. Main-thread only; one animator per TextView. Extracted
 *               so callers stop re-declaring the same Handler/Runnable/frames dance in every screen
 *               (LibraryActivity, SetupProgressActivity, …).
 * ============================================================================
 */
package org.iiab.controller.util;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

public final class EllipsisAnimator {

    private static final long FRAME_MS = 450L;
    private static final String[] FRAMES = {"", ".", "..", "..."};
    // ADFA-4947: fixed-width variant — 3-slot suffix padded with spaces and rendered monospace, so a
    // centered status line does not shift left/right as the dots grow.
    private static final String[] FRAMES_FIXED = {"   ", ".  ", ".. ", "..."};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView target;
    private final boolean fixedWidth;
    private String base;
    private int frame;
    private Runnable ticker;

    /** Variable-width, plain-text dots — the common case. */
    public EllipsisAnimator(TextView target) {
        this(target, false);
    }

    /** @param fixedWidth true = constant-width, monospaced suffix so a centered line does not jiggle. */
    public EllipsisAnimator(TextView target, boolean fixedWidth) {
        this.target = target;
        this.fixedWidth = fixedWidth;
    }

    /** Start (or retarget) the animation with a base label. Trailing dots/ellipsis/space in {@code label}
     *  are stripped so we never double them. No-op if already animating the same base. Call on the main
     *  thread. */
    public void start(String label) {
        String b = (label == null) ? "" : label.replaceAll("[\\s.…]+$", "");
        if (ticker != null && b.equals(base)) return;   // already animating this text
        stop();
        base = b;
        frame = 0;
        ticker = new Runnable() {
            @Override
            public void run() {
                if (target != null) {
                    if (fixedWidth) {
                        String suffix = FRAMES_FIXED[frame % FRAMES_FIXED.length];
                        android.text.SpannableString sp = new android.text.SpannableString(base + suffix);
                        sp.setSpan(new android.text.style.TypefaceSpan("monospace"),
                                base.length(), base.length() + suffix.length(),
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        target.setText(sp);
                    } else {
                        target.setText(base + FRAMES[frame % FRAMES.length]);
                    }
                }
                frame++;
                handler.postDelayed(this, FRAME_MS);
            }
        };
        handler.post(ticker);
    }

    /** Stop animating. Leaves the current text in place; safe to call repeatedly. */
    public void stop() {
        if (ticker != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
            base = null;
        }
    }

    public boolean isRunning() {
        return ticker != null;
    }
}
