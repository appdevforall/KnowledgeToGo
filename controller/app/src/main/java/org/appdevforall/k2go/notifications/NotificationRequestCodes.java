/*
 * ============================================================================
 * Name        : NotificationRequestCodes.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-463. ONE source for the PendingIntent request codes used by notifications.
 *
 *               A PendingIntent identity is the request code plus the Intent component, action and
 *               data: extras are NOT part of it. So two request codes only collide when they target
 *               the SAME component. There are two families, and they need different rules:
 *
 *               - {@link Return}: the "tap to go back to the running op" intents. They all open the
 *                 same handful of Activities and differ only by extras, so they MUST be globally
 *                 unique among themselves (this is the K2GO-461 bug: all of them used code 0).
 *                 NotificationRequestCodesTest fails the build if any two match.
 *               - {@link ServiceAction}: a notification action that drives a service (cancel, pause,
 *                 resume). Each targets its OWN service component, so the same value on two different
 *                 services does not collide. They are NOT forced unique; the component disambiguates.
 *                 They are named here so a new service reuses a known code instead of a raw literal.
 *
 *               Dependency-free on purpose (no android.* import): the uniqueness test runs on a plain
 *               JVM. Notification ids are a different thing and live in {@link NotificationIds}.
 * ============================================================================
 */
package org.appdevforall.k2go.notifications;

public final class NotificationRequestCodes {
    private NotificationRequestCodes() {}

    /** Return-to-op codes. Same target Activity, extras-only difference, so these MUST all differ.
     *  Applied by OpReturnNavigator.notify() and by InstallNotifications.postModuleFailure(). */
    public static final class Return {
        private Return() {}
        public static final int CONTENT_DOWNLOAD = 1;  // SetupProgressActivity (kiwix / books / kolibri)
        public static final int BACKUP_RESTORE   = 2;  // SetupLibraryActivity (backup/restore job)
        public static final int DASHBOARD_REBUILD = 3; // SetupLibraryActivity (dashboard detail)
        public static final int INSTALL          = 4;  // LibraryActivity (rootfs / module install)
        public static final int CLONE_SHARE      = 5;  // LibraryActivity (clone tab)
        public static final int MODULE_FAILURE   = 6;  // SetupLibraryActivity (module management)
    }

    /** Service-action codes. Each targets its own Service, so an equal value across services does
     *  NOT collide (the component already separates them): these are deliberately not forced unique. */
    public static final class ServiceAction {
        private ServiceAction() {}
        public static final int CANCEL  = 1;  // each download / seed service: cancel its own work
        public static final int RESUME  = 2;  // InstallService: resume the rootfs download
        public static final int PAUSE   = 3;  // InstallService: pause the rootfs download
    }
}
