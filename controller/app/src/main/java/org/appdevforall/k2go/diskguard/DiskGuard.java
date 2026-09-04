/*
 * ============================================================================
 * Name        : DiskGuard.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-386 (barrier 2). The Android-side free-space guard: one
 *               tick reads free space (StorageProbe), asks the pure rule
 *               (DiskGuardPolicy), and on CRITICAL tears the box down, reclaims
 *               the runaway log, and warns the user.
 *
 *               Why Android-side: the disk-fill happens when the box proot dies
 *               (a relaunch/restore orphans a service that busy-loops); the
 *               in-box healer dies with it. Device-proven (2026-09-04): only an
 *               outside-the-rootfs actor stops it — an in-box restart is
 *               unreachable, an in-proot kill does not reach the orphan, and a
 *               full teardown (force-stop) is what worked. So the action is a
 *               full box reap (EnvironmentProcess), NOT a targeted restart.
 * ============================================================================
 */
package org.appdevforall.k2go.diskguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.diskguard.domain.DiskGuardPolicy;
import org.appdevforall.k2go.env.EnvironmentProcess;
import org.appdevforall.k2go.storage.StorageProbe;

import java.io.File;
import java.io.FileOutputStream;

public final class DiskGuard {

    private static final String TAG = "K2Go-DiskGuard";

    /** Only truncate a log that is clearly a runaway, not a normal small log. */
    private static final long RUNAWAY_LOG_MIN_BYTES = 256L * 1024 * 1024;

    private static final String CHANNEL_ID = "disk_guard_channel";
    private static final int NOTIF_ID = 7386;

    private DiskGuard() {}

    /**
     * One guard tick with the default critical floor. Returns true when it acted. Safe to call
     * repeatedly from a poller; a null/UNKNOWN read is a no-op.
     */
    public static boolean check(Context ctx) {
        return checkWithFloor(ctx, DiskGuardPolicy.CRITICAL_FLOOR_BYTES);
    }

    /**
     * As {@link #check} but with an explicit floor — used by the debug device-verify hook to force the
     * action (a huge floor makes any real free space read CRITICAL) without filling the disk for real.
     */
    public static boolean checkWithFloor(Context ctx, long floorBytes) {
        if (ctx == null) return false;
        Long free = StorageProbe.freeBytes(ctx);
        if (DiskGuardPolicy.evaluate(free, floorBytes) != DiskGuardPolicy.Level.CRITICAL) return false;

        Log.w(TAG, "K2GO-386: free space CRITICAL (" + free + " B, floor " + floorBytes
                + ") — tearing the box down to protect the device");
        boolean reaped = EnvironmentProcess.reapBox(ctx);
        long reclaimed = reclaimRunawayLog(ctx);
        notifyUser(ctx);
        Log.w(TAG, "K2GO-386: box reaped=" + reaped + ", runaway log reclaimed=" + reclaimed + " B");
        return true;
    }

    /**
     * Truncate the biggest log file under the box's {@code /var/log} to reclaim the space the runaway
     * consumed (a real file that persists after its writer dies). Best-effort; bounded to a clearly-large
     * file so a normal log is never touched. Returns the bytes reclaimed, or 0.
     */
    private static long reclaimRunawayLog(Context ctx) {
        File varLog = new File(ctx.getFilesDir(), "rootfs/installed-rootfs/iiab/var/log");
        File[] files = varLog.listFiles();
        if (files == null) return 0L;
        File biggest = null;
        long max = 0L;
        for (File f : files) {
            if (f.isFile() && f.length() > max) {
                max = f.length();
                biggest = f;
            }
        }
        if (biggest == null || max < RUNAWAY_LOG_MIN_BYTES) return 0L;
        try (FileOutputStream truncate = new FileOutputStream(biggest)) {
            // opening for write with no append truncates to zero
            Log.w(TAG, "K2GO-386: truncated runaway log " + biggest.getName() + " (" + max + " B)");
            return max;
        } catch (Exception e) {
            Log.w(TAG, "K2GO-386: could not truncate " + biggest.getName(), e);
            return 0L;
        }
    }

    /** Warn the user that the box was stopped to protect the device. Best-effort — a no-op if the
     *  POST_NOTIFICATIONS permission is not granted (API 33+); the teardown still happened. */
    private static void notifyUser(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, ctx.getString(R.string.disk_guard_notif_title),
                        NotificationManager.IMPORTANCE_HIGH));
            }
            Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setContentTitle(ctx.getString(R.string.disk_guard_notif_title))
                    .setContentText(ctx.getString(R.string.disk_guard_notif_body))
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(ctx.getString(R.string.disk_guard_notif_body)))
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build();
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n);
        } catch (Exception e) {
            Log.w(TAG, "K2GO-386: could not post the disk-guard notification", e);
        }
    }
}
