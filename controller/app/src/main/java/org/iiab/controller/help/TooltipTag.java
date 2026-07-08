/*
 * ============================================================================
 * Name        : TooltipTag.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Stable tooltip tags (anchor-point keys) for the K2Go three-tier
 *               help system. One constant per native control; single registry.
 * ============================================================================
 */
package org.iiab.controller.help;

public final class TooltipTag {

    private TooltipTag() {}

    // crash
    public static final String CRASH_DISMISS = "crash.dismiss";
    public static final String CRASH_SEND = "crash.send";

    // portal
    public static final String PORTAL_HANDLE = "portal.handle";
    public static final String PORTAL_HIDE_NAV = "portal.hide_nav";
    public static final String PORTAL_BACK = "portal.back";
    public static final String PORTAL_HOME = "portal.home";
    public static final String PORTAL_RELOAD = "portal.reload";
    public static final String PORTAL_EXIT = "portal.exit";
    public static final String PORTAL_FORWARD = "portal.forward";

    // qr
    public static final String QR_FLIP = "qr.flip";
    public static final String QR_CLOSE = "qr.close";

    // setup
    public static final String SETUP_DONE = "setup.done";
    public static final String SETUP_NAV_SETUP = "setup.nav_setup";
    public static final String SETUP_NAV_FEEDBACK = "setup.nav_feedback";
    public static final String SETUP_NAV_ABOUT = "setup.nav_about";

    // about
    public static final String ABOUT_ANALYTICS_CONSENT = "about.analytics_consent";

    // dashboard
    public static final String DASHBOARD_FLIP_GAUGES = "dashboard.flip_gauges";
    public static final String DASHBOARD_MODULES_TITLE = "dashboard.modules_title";

    // deploy
    public static final String DEPLOY_LED_DCPR = "deploy.led_dcpr";
    public static final String DEPLOY_LED_PPK = "deploy.led_ppk";
    public static final String DEPLOY_ADV_MONITORING = "deploy.adv_monitoring";
    public static final String DEPLOY_ADB_ACTION = "deploy.adb_action";
    public static final String DEPLOY_TIER_BASIC = "deploy.tier_basic";
    public static final String DEPLOY_TIER_STANDARD = "deploy.tier_standard";
    public static final String DEPLOY_TIER_FULL = "deploy.tier_full";
    public static final String DEPLOY_COMPANION_DATA = "deploy.companion_data";
    public static final String DEPLOY_KIWIX_SETTINGS = "deploy.kiwix_settings";
    public static final String DEPLOY_FAST_INSTALL = "deploy.fast_install";
    public static final String DEPLOY_FAST_DELETE = "deploy.fast_delete";
    public static final String DEPLOY_MODULE_MGMT = "deploy.module_mgmt";
    public static final String DEPLOY_REFRESH_MODULES = "deploy.refresh_modules";
    public static final String DEPLOY_LAUNCH_INSTALL = "deploy.launch_install";
    public static final String DEPLOY_MAINTENANCE = "deploy.maintenance";
    public static final String DEPLOY_BACKUP = "deploy.backup";
    public static final String DEPLOY_RESET = "deploy.reset";
    public static final String DEPLOY_RESTORE = "deploy.restore";
    public static final String DEPLOY_FORCE_STOP = "deploy.force_stop";
    public static final String DEPLOY_IMPORT_BACKUP = "deploy.import_backup";
    public static final String DEPLOY_SELECT_BACKUP = "deploy.select_backup";
    public static final String DEPLOY_RESTORE_LOG_CLOSE = "deploy.restore_log_close";

    // feedback
    public static final String FEEDBACK_CATEGORY = "feedback.category";
    public static final String FEEDBACK_SEND = "feedback.send";
    public static final String FEEDBACK_FORCE_CRASH = "feedback.force_crash";

    // setup_section
    public static final String SETUP_SECTION_CONTINUE = "setup_section.continue";
    public static final String SETUP_SECTION_PERM_NOTIFICATIONS = "setup_section.perm_notifications";
    public static final String SETUP_SECTION_PERM_STORAGE = "setup_section.perm_storage";
    public static final String SETUP_SECTION_PERM_OVERLAY = "setup_section.perm_overlay";
    public static final String SETUP_SECTION_PERM_BATTERY = "setup_section.perm_battery";
    public static final String SETUP_SECTION_MANAGE_ALL = "setup_section.manage_all";
    public static final String SETUP_SECTION_LANGUAGE_HEADER = "setup_section.language_header";
    public static final String SETUP_SECTION_APP_LANGUAGE = "setup_section.app_language";
    public static final String SETUP_SECTION_CONTENT_LANGUAGE = "setup_section.content_language";

    // sync
    public static final String SYNC_MODE_SHARE = "sync.mode_share";
    public static final String SYNC_MODE_RECEIVE = "sync.mode_receive";
    public static final String SYNC_NET_WIFI = "sync.net_wifi";
    public static final String SYNC_NET_HOTSPOT = "sync.net_hotspot";
    public static final String SYNC_START_SERVER = "sync.start_server";
    public static final String SYNC_SHARE_APP = "sync.share_app";
    public static final String SYNC_SCAN_QR = "sync.scan_qr";
    public static final String SYNC_CANCEL_TRANSFER = "sync.cancel_transfer";

    // usage
    public static final String USAGE_WIFI = "usage.wifi";
    public static final String USAGE_HOTSPOT = "usage.hotspot";
    public static final String USAGE_BROWSE_CONTENT = "usage.browse_content";
    public static final String USAGE_DNS_CHECK = "usage.dns_check";
    public static final String USAGE_DNS_ACCEPT = "usage.dns_accept";
    public static final String USAGE_DNS_SETTINGS = "usage.dns_settings";
    public static final String USAGE_LOG = "usage.log";
    public static final String USAGE_CLEAR_LOG = "usage.clear_log";
    public static final String USAGE_COPY_LOG = "usage.copy_log";
    public static final String USAGE_SERVER_CONTROL = "usage.server_control";
    public static final String USAGE_LOHS_TOGGLE = "usage.lohs_toggle";
    public static final String USAGE_LOHS_SHOW_QR = "usage.lohs_show_qr";

    // main
    public static final String MAIN_SHARE_QR = "main.share_qr";
    public static final String MAIN_SETTINGS = "main.settings";
    public static final String MAIN_THEME_TOGGLE = "main.theme_toggle";
    public static final String MAIN_VERSION = "main.version";

}
