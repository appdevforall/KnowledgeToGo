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
package org.iiab.controller.help;

import android.view.View;

import org.iiab.controller.R;

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
        // dashboard
        MAP.put(R.id.btn_flip_gauges, TooltipTag.DASHBOARD_FLIP_GAUGES);
        MAP.put(R.id.dash_modules_title, TooltipTag.DASHBOARD_MODULES_TITLE);
        // deploy
        MAP.put(R.id.container_led_dcpr, TooltipTag.DEPLOY_LED_DCPR);
        MAP.put(R.id.container_led_ppk, TooltipTag.DEPLOY_LED_PPK);
        MAP.put(R.id.txt_adv_monitoring_title, TooltipTag.DEPLOY_ADV_MONITORING);
        MAP.put(R.id.btn_adb_action, TooltipTag.DEPLOY_ADB_ACTION);
        MAP.put(R.id.btn_tier_basic, TooltipTag.DEPLOY_TIER_BASIC);
        MAP.put(R.id.btn_tier_standard, TooltipTag.DEPLOY_TIER_STANDARD);
        MAP.put(R.id.btn_tier_full, TooltipTag.DEPLOY_TIER_FULL);
        MAP.put(R.id.chk_companion_data, TooltipTag.DEPLOY_COMPANION_DATA);
        MAP.put(R.id.btn_kiwix_settings, TooltipTag.DEPLOY_KIWIX_SETTINGS);
        MAP.put(R.id.btn_fast_install, TooltipTag.DEPLOY_FAST_INSTALL);
        MAP.put(R.id.btn_fast_delete, TooltipTag.DEPLOY_FAST_DELETE);
        MAP.put(R.id.txt_module_mgmt_title, TooltipTag.DEPLOY_MODULE_MGMT);
        MAP.put(R.id.btn_refresh_modules, TooltipTag.DEPLOY_REFRESH_MODULES);
        MAP.put(R.id.btn_launch_install, TooltipTag.DEPLOY_LAUNCH_INSTALL);
        MAP.put(R.id.txt_maintenance_title, TooltipTag.DEPLOY_MAINTENANCE);
        MAP.put(R.id.btn_advanced_backup, TooltipTag.DEPLOY_BACKUP);
        MAP.put(R.id.btn_advanced_reset, TooltipTag.DEPLOY_RESET);
        MAP.put(R.id.btn_advanced_restore, TooltipTag.DEPLOY_RESTORE);
        MAP.put(R.id.btn_advanced_force_stop, TooltipTag.DEPLOY_FORCE_STOP);
        MAP.put(R.id.btn_import_backup, TooltipTag.DEPLOY_IMPORT_BACKUP);
        MAP.put(R.id.txt_select_backup_title, TooltipTag.DEPLOY_SELECT_BACKUP);
        MAP.put(R.id.restore_log_close, TooltipTag.DEPLOY_RESTORE_LOG_CLOSE);
        MAP.put(R.id.col_internet, TooltipTag.DEPLOY_INTERNET);
        MAP.put(R.id.col_dev_mode, TooltipTag.DEPLOY_DEV_MODE);
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
        // sync
        MAP.put(R.id.rb_mode_share, TooltipTag.SYNC_MODE_SHARE);
        MAP.put(R.id.rb_mode_receive, TooltipTag.SYNC_MODE_RECEIVE);
        MAP.put(R.id.rb_net_wifi, TooltipTag.SYNC_NET_WIFI);
        MAP.put(R.id.rb_net_hotspot, TooltipTag.SYNC_NET_HOTSPOT);
        MAP.put(R.id.btn_start_server, TooltipTag.SYNC_START_SERVER);
        MAP.put(R.id.btn_share_app, TooltipTag.SYNC_SHARE_APP);
        MAP.put(R.id.btn_scan_qr, TooltipTag.SYNC_SCAN_QR);
        MAP.put(R.id.btn_cancel_transfer, TooltipTag.SYNC_CANCEL_TRANSFER);
        // usage
        MAP.put(R.id.dash_wifi, TooltipTag.USAGE_WIFI);
        MAP.put(R.id.dash_hotspot, TooltipTag.USAGE_HOTSPOT);
        MAP.put(R.id.btnBrowseContent, TooltipTag.USAGE_BROWSE_CONTENT);
        MAP.put(R.id.setup_dns_check, TooltipTag.USAGE_DNS_CHECK);
        MAP.put(R.id.dns_accept, TooltipTag.USAGE_DNS_ACCEPT);
        MAP.put(R.id.dns_settings_label, TooltipTag.USAGE_DNS_SETTINGS);
        MAP.put(R.id.log_label, TooltipTag.USAGE_LOG);
        MAP.put(R.id.btn_clear_log, TooltipTag.USAGE_CLEAR_LOG);
        MAP.put(R.id.btn_copy_log, TooltipTag.USAGE_COPY_LOG);
        MAP.put(R.id.btn_server_control, TooltipTag.USAGE_SERVER_CONTROL);
        MAP.put(R.id.lohs_toggle, TooltipTag.USAGE_LOHS_TOGGLE);
        MAP.put(R.id.lohs_show_qr, TooltipTag.USAGE_LOHS_SHOW_QR);
        // main
        MAP.put(R.id.btn_share_qr, TooltipTag.MAIN_SHARE_QR);
        MAP.put(R.id.btn_settings, TooltipTag.MAIN_SETTINGS);
        MAP.put(R.id.theme_toggle, TooltipTag.MAIN_THEME_TOGGLE);
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
