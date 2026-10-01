/*
 * ============================================================================
 * Name        : InstallNotifications.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-436 (slice 3). The install notifications in one place: the channel, the ongoing
 *               foreground notification (with its per-pipeline actions) and the dismissible module
 *               -failure notification, plus their identity (channel id + notification ids). InstallService
 *               keeps startForeground/updateNotification (a Service can only post its own foreground
 *               notification); this builds the Notification it posts. Presentation layer. Slice 3 of
 *               controller/docs/ADR-installservice-decomposition.md.
 * ============================================================================
 */
package org.appdevforall.k2go.install.presentation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import org.appdevforall.k2go.R;

import java.util.List;

public final class InstallNotifications {

    private static final String CHANNEL_ID = "install_channel";

    /** The ongoing foreground notification id (InstallService posts it via startForeground). */
    public static final int ONGOING_ID = 3;

    /** The dismissible module-failure notification id, distinct from the foreground one. */
    private static final int MODULE_FAIL_ID = ONGOING_ID + 4;

    private InstallNotifications() {
    }

    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.install_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(ctx.getString(R.string.install_channel_desc));
            NotificationManager manager = ctx.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    /**
     * The ongoing foreground notification. {@code installPipeline} is the Service mode fact this cannot
     * know (true only for the rootfs install, not a module queue / reset / dashboard rebuild); {@code st}
     * is the current install state that decides which action to offer.
     */
    public static Notification buildOngoing(Context ctx, String text, boolean installPipeline, InstallState st) {
        // ADFA-4919 / K2GO-382: return to the modern progress surface : LibraryActivity shows rootfs
        // progress (boot gate) and routes to the proot install index when a module is running : unlike
        // legacy MainActivity, which shows neither. The route now has one owner (OpReturnNavigator);
        // EXTRA_INSTALLING makes a fresh/refreshed LibraryActivity land on install progress instead of
        // the last tab.
        PendingIntent contentIntent = org.appdevforall.k2go.redesign.OpReturnNavigator.notify(ctx,
                org.appdevforall.k2go.redesign.OpReturnNavigator.install(ctx));

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(ctx.getString(R.string.install_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true);

        // ADFA-5119: the rootfs download gets Pause/Resume here and NO Cancel.
        //
        // Cancel was the only action this notification ever had, and on this path it was the wrong
        // one twice over: it is destructive : it discards the transfer, the tier and the wishlists :
        // and it fired straight from the shade with no confirmation, while the same decision on
        // screen asks first. A control that expensive should not be one stray tap in a crowded
        // shade, so it stays where it can be confirmed. The notification keeps the cheap, reversible
        // half; the notification body already carries the percentage and the rate, and loses the
        // rate on a pause because there is no longer one to report.
        //
        // Nothing is offered during verify, extract or provisioning: a pause there would leave a
        // half-written rootfs, and Cancel is exactly what we just took away. The notification's job
        // in those phases is to report, and its tap still opens the screen where the choice lives.
        //
        // Every other user of this notification : the module queue, the scratch reset, the dashboard
        // rebuild : keeps Cancel unchanged. They have no pause to offer and Cancel is their only
        // control.
        // Keyed on the pipeline, not on `work`: `work` only narrows to ROOTFS_BUILD once the pipeline
        // has decided, and this notification is posted before that : so keying on it would show
        // Cancel for the first second of every install, which is one stray tap in the shade doing
        // the exact destructive thing this change removes.
        if (installPipeline && st.isHeld()) {
            b.addAction(0, ctx.getString(st.isSoftFailed() ? R.string.k2go_dl_retry
                                                           : R.string.k2go_dl_resume),
                    serviceAction(ctx, InstallService.ACTION_RESUME, 2));
        } else if (installPipeline && st.phase == InstallState.Phase.DOWNLOADING) {
            b.addAction(0, ctx.getString(R.string.k2go_dl_pause), serviceAction(ctx, InstallService.ACTION_PAUSE, 3));
        } else if (!installPipeline) {
            // ADFA-5119: a module queue, a scratch reset and a dashboard rebuild get a way to LOOK,
            // never a way to stop. Cancel was the only action they had, and on these three it is a
            // button that breaks the thing it is attached to: doCancel clears the queue and tears the
            // service down, but the runrole already inside proot keeps writing : and teardown clears
            // the install marker, so the app stops standing back while Ansible is still configuring a
            // module. The result is a half-configured module and a server the app now feels free to
            // start over it. There is no partial file to discard and nothing to resume from; unlike a
            // download, this cannot be undone by deleting a file.
            //
            // The notification's job here is to report and to offer a way back to the screen. The
            // body tap already does that; the action makes it visible instead of leaving a
            // notification that looks inert.
            b.addAction(0, ctx.getString(R.string.k2go_notif_view), contentIntent);
        }
        return b.build();
    }

    /**
     * ADFA-4898: dismissible "install failed" notification for a module batch that ended with failures.
     * Distinct id from the foreground one (removed by teardown's stopForeground); tapping opens Module
     * management, where the failed module shows a Retry.
     */
    public static void postModuleFailure(Context ctx, List<String> failed) {
        NotificationManager m = ctx.getSystemService(NotificationManager.class);
        if (m == null) return;
        Intent open = new Intent(ctx, org.appdevforall.k2go.redesign.SetupLibraryActivity.class)
                .putExtra(org.appdevforall.k2go.redesign.SetupLibraryActivity.EXTRA_MODULE_MGMT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle(ctx.getString(R.string.k2go_mod_phase_failed))
                .setContentText(TextUtils.join(", ", failed))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
        m.notify(MODULE_FAIL_ID, n);
    }

    /** Clear a previously posted module-failure notification (a new batch supersedes the old result). */
    public static void cancelModuleFailure(Context ctx) {
        NotificationManager m = ctx.getSystemService(NotificationManager.class);
        if (m != null) m.cancel(MODULE_FAIL_ID);
    }

    /**
     * A notification action that delivers one of InstallService's own intents. Distinct request codes
     * per action, deliberately: PendingIntent identity ignores the action string, so reusing one code
     * would let FLAG_UPDATE_CURRENT hand the same pending intent a different action : a Pause that cancels.
     */
    private static PendingIntent serviceAction(Context ctx, String action, int requestCode) {
        Intent i = new Intent(ctx, InstallService.class).setAction(action);
        return PendingIntent.getService(ctx, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
