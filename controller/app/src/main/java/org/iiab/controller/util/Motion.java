/*
 * ============================================================================
 * Name        : Motion.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : One reading of the system "reduce motion" setting.
 * ============================================================================
 */
package org.iiab.controller.util;

import android.content.Context;
import android.provider.Settings;

/**
 * Whether the user asked the system to stop animating.
 *
 * <p>Every screen that plays a Lottie has to answer this, and the answer must be the same everywhere:
 * a phone that reports no animation cannot have one screen honouring it and the next ignoring it. It
 * lives here so there is one reading of one setting rather than a private copy per screen.
 *
 * <p>Callers decide what to do with a {@code true} — a static frame, a hidden view, no animation at
 * all — but a screen must never depend on motion to be understood.
 */
public final class Motion {

    private Motion() {
    }

    /** True when the system animator duration scale is 0 ("remove animations" / reduce motion). */
    public static boolean reduced(Context ctx) {
        if (ctx == null) return false;
        try {
            return Settings.Global.getFloat(ctx.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception e) {
            return false;
        }
    }
}
