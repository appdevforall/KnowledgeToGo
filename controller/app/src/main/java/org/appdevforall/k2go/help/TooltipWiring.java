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
        // setup
        MAP.put(R.id.btn_settings_done, TooltipTag.SETUP_DONE);
        MAP.put(R.id.nav_setup, TooltipTag.SETUP_NAV_SETUP);
        MAP.put(R.id.nav_feedback, TooltipTag.SETUP_NAV_FEEDBACK);
        MAP.put(R.id.nav_about, TooltipTag.SETUP_NAV_ABOUT);
        // about
        MAP.put(R.id.switch_analytics_consent, TooltipTag.ABOUT_ANALYTICS_CONSENT);
        // ADFA-5192: the dashboard / deploy / sync / usage / main tooltip entries were removed with
        // the legacy tabbed UI (their controls and layouts no longer exist). The redesign wires its
        // own tooltips; this registry now covers only the surviving native surfaces below.
        // feedback
        MAP.put(R.id.feedback_category, TooltipTag.FEEDBACK_CATEGORY);
        MAP.put(R.id.feedback_send, TooltipTag.FEEDBACK_SEND);
        MAP.put(R.id.feedback_force_crash, TooltipTag.FEEDBACK_FORCE_CRASH);
        // setup_section
        MAP.put(R.id.btn_setup_continue, TooltipTag.SETUP_SECTION_CONTINUE);
        MAP.put(R.id.switch_perm_notifications, TooltipTag.SETUP_SECTION_PERM_NOTIFICATIONS);
        MAP.put(R.id.switch_perm_storage, TooltipTag.SETUP_SECTION_PERM_STORAGE);
        MAP.put(R.id.switch_perm_overlay, TooltipTag.SETUP_SECTION_PERM_OVERLAY);
        MAP.put(R.id.switch_perm_battery, TooltipTag.SETUP_SECTION_PERM_BATTERY);
        MAP.put(R.id.btn_manage_all, TooltipTag.SETUP_SECTION_MANAGE_ALL);
        MAP.put(R.id.language_header, TooltipTag.SETUP_SECTION_LANGUAGE_HEADER);
        MAP.put(R.id.spinner_app_language, TooltipTag.SETUP_SECTION_APP_LANGUAGE);
        MAP.put(R.id.spinner_language, TooltipTag.SETUP_SECTION_CONTENT_LANGUAGE);
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
