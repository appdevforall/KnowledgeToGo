/*
 * ============================================================================
 * Name        : NotificationIds.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-463. ONE source for every notification id in the app.
 *
 *               Each foreground service and each posted notification used to keep its own
 *               private id constant, so two of them could pick the same value with no check.
 *               That caused K2GO-458 (four services shared id 7, two shared id 8). The fix is
 *               structural, not another remap: all ids live here, and NotificationIdsTest fails
 *               the build if any two match. A new service takes its id from this file; it does
 *               not invent a literal at a startForeground call.
 *
 *               Dependency-free on purpose (no android.* import): the uniqueness test runs on a
 *               plain JVM. Request codes for PendingIntents are a different identity space and
 *               live in {@link NotificationRequestCodes}.
 * ============================================================================
 */
package org.appdevforall.k2go.notifications;

public final class NotificationIds {
    private NotificationIds() {}

    // Low range: the foreground services and the install notifications. These can run at the same
    // time, so every value must differ (that is what K2GO-458 established and this file now guards).
    public static final int WATCHDOG               = 2;   // WatchdogService
    public static final int INSTALL_ONGOING        = 3;   // InstallNotifications (the foreground install)
    public static final int TERMINAL_SESSION       = 4;   // TerminalSessionService
    public static final int ZIM_DOWNLOAD           = 5;   // ZimDownloadService
    public static final int BOOKS_DOWNLOAD         = 6;   // BooksDownloadService
    public static final int CODE_ASSETS_DOWNLOAD   = 7;   // CodeAssetsDownloadService
    public static final int CLONE_SHARE            = 8;   // CloneShareService
    public static final int KOLIBRI_SEED           = 9;   // KolibriSeedService
    public static final int DASHBOARD_REBUILD      = 10;  // DashboardRebuildService
    public static final int CODE_ADDONS_DOWNLOAD   = 11;  // AddonsDownloadService
    public static final int DEEPOP                 = 12;  // DeepOpService
    public static final int INSTALL_MODULE_FAILURE = 13;  // InstallNotifications (dismissible "install failed")
    public static final int FORGEJO_SEED           = 14;  // ForgejoSeedService

    // High range: single-purpose notifications that re-alert in place (they replace, never stack),
    // kept well away from the low range. Listed here too so the one source stays complete.
    public static final int DISK_GUARD_ESCALATION  = 7386;   // DiskGuard (stopped and staying down)
    public static final int DISK_GUARD_FIREHOSE    = 7387;   // DiskGuard (firehose contain)
    public static final int ADB_PAIRING            = 9401;   // AdbPairingReceiver (pairing in progress)
    public static final int ADB_PAIRING_RETURN     = 9402;   // AdbPairingReceiver (tap to return)
    public static final int NETWORK_COST           = 0x4E50; // MeteredNetworkObserver (metered warning)
}
