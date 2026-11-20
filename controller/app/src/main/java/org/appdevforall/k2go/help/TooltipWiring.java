/*
 * ============================================================================
 * Name        : TooltipWiring.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Central R.id -> tag registry. Call wireAll(root) once per screen
 *               (Activity/Fragment); it attaches a long-press three-tier tooltip
 *               to every listed control present in that view tree (null-safe: a
 *               control absent from the current screen is simply skipped).
 * ============================================================================
 */
package org.appdevforall.k2go.help;

import android.view.View;

import org.appdevforall.k2go.R;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TooltipWiring {

    private TooltipWiring() {}

    private static final Map<Integer, String> MAP = new LinkedHashMap<>();
    static {
        // crash
        MAP.put(R.id.crash_dismiss, TooltipTag.CRASH_DISMISS);
        MAP.put(R.id.crash_send, TooltipTag.CRASH_SEND);
        // portal
        MAP.put(R.id.btnHandle, TooltipTag.PORTAL_HANDLE);
        MAP.put(R.id.btnHideNav, TooltipTag.PORTAL_HIDE_NAV);
        MAP.put(R.id.btnBack, TooltipTag.PORTAL_BACK);
        MAP.put(R.id.btnHome, TooltipTag.PORTAL_HOME);
        MAP.put(R.id.btnReload, TooltipTag.PORTAL_RELOAD);
        MAP.put(R.id.btnExit, TooltipTag.PORTAL_EXIT);
        MAP.put(R.id.btnForward, TooltipTag.PORTAL_FORWARD);
        // qr
        MAP.put(R.id.btn_flip_qr, TooltipTag.QR_FLIP);
        MAP.put(R.id.btn_close_qr, TooltipTag.QR_CLOSE);
        // ADFA-5192: the dashboard / deploy / sync / usage / main tooltip entries were removed with
        // the legacy tabbed UI (their controls and layouts no longer exist). The redesign wires its
        // own tooltips; this registry now covers only the surviving native surfaces below.
        // feedback
        MAP.put(R.id.feedback_category, TooltipTag.FEEDBACK_CATEGORY);
        MAP.put(R.id.feedback_send, TooltipTag.FEEDBACK_SEND);
        MAP.put(R.id.feedback_force_crash, TooltipTag.FEEDBACK_FORCE_CRASH);
    }

    /** Attach tier-1/2 tooltips (long-press) to every mapped control found under {@code root}. */
    public static void wireAll(View root) {
        if (root == null) return;
        for (Map.Entry<Integer, String> e : MAP.entrySet()) {
            View v = root.findViewById(e.getKey());
            if (v != null) {
                ViewTooltips.attachLongPress(v, TooltipCategory.K2GO, e.getValue());
            }
        }
    }
}
