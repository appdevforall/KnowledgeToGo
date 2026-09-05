/*
 * ============================================================================
 * Name        : DiskGuard.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-386 (Layer 3, app-side backstop). The outside-the-rootfs
 *               net for a disk-fill the in-box layers cannot stop.
 *
 *               One tick reads free space (StorageProbe) and asks the pure rule
 *               (DiskGuardPolicy). On CRITICAL it CONFIRMS with a fresh re-read
 *               (it never acts on one reading). It does NOT reap while a deep op
 *               (clone/backup/restore/install) holds the box, because a reap
 *               mid-operation would corrupt it. Otherwise it reaps the box and
 *               reclaims the runaway log.
 *
 *               The default action KEEPS THE SYSTEM ALIVE: it does not force the
 *               server down. A fresh service under a fresh proot does not
 *               busy-loop, so the ADFA-5343 reconciler relaunches a clean box.
 *               Only when the disk stays critical for several trips in a row
 *               (DiskGuardEscalation) does the guard stop and stay down as a last
 *               resort and tell the user. Trips are reported to developers.
 *
 *               Why Android-side: the fill happens when the box proot dies and a
 *               service is orphaned off proot. An in-box kill does not reach the
 *               orphan (device-proven 2026-09-04, HD1901); only an app-side reap
 *               works. See controller/docs/ADR-386.
 * ============================================================================
 */
package org.appdevforall.k2go.diskguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.delivery.DeliveryManager;
import org.appdevforall.k2go.diskguard.domain.DiskGuardEscalation;
import org.appdevforall.k2go.diskguard.domain.DiskGuardPolicy;
import org.appdevforall.k2go.env.EnvironmentLock;
import org.appdevforall.k2go.env.EnvironmentProcess;
import org.appdevforall.k2go.env.ServerLifecycleReconciler;
import org.appdevforall.k2go.storage.StorageProbe;
import org.appdevforall.k2go.system.domain.Operation;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

public final class DiskGuard {

    private static final String TAG = "K2Go-DiskGuard";

    // Only truncate a log that is clearly a runaway, not a normal log. A runaway at a critical-low-space
    // moment is many GB, so 1 GiB stays well clear of any legitimate log.
    private static final long RUNAWAY_LOG_MIN_BYTES = 1024L * 1024 * 1024;

    // Confirm-before-act: after a CRITICAL reading, wait this long and read again. A real fill persists;
    // a momentary spike does not (ADR-386, "confirm before acting").
    private static final long CONFIRM_DELAY_MS = 1000L;

    // Restart-to-keep-alive is the default. If the disk stays critical this many trips in a row, the
    // restart is not fixing it, so the guard escalates to stop-and-stay-down as a last resort.
    private static final int ESCALATE_AFTER_TRIPS = 3;
    private static final long TRIP_WINDOW_MS = 30L * 60L * 1000L;

    private static final String CHANNEL_ID = "disk_guard_channel";
    private static final int NOTIF_ID = 7386;

    // Recent-trip state, in memory on purpose: it resets when the app process restarts, so a stale count
    // never carries across a restart. Read and written only under advanceTripState (class monitor).
    private static long lastTripElapsedMs = -1L;
    private static int tripCount = 0;

    private DiskGuard() {}

    /**
     * One guard tick with the default critical floor. Returns true when it acted. Safe to call
     * repeatedly from a poller. A null or UNKNOWN read is a no-op.
     */
    public static boolean check(Context ctx) {
        return run(ctx, DiskGuardPolicy.CRITICAL_FLOOR_BYTES, false);
    }

    /**
     * The debug device-verify hook. It passes a huge floor so any real free-space read is CRITICAL, and
     * runs in FORCED mode: it exercises the reap/reclaim/restart path once but does NOT advance the real
     * escalation count, so triggering it repeatedly cannot stop the box.
     */
    public static boolean checkWithFloor(Context ctx, long floorBytes) {
        return run(ctx, floorBytes, true);
    }

    private static boolean run(Context ctx, long floorBytes, boolean forced) {
        if (ctx == null) return false;
        boolean critical = confirmCritical(ctx, floorBytes);

        DiskGuardEscalation.Verdict v;
        if (forced) {
            // Forced (debug): CONTAIN if critical, and never touch the shared trip state or escalate.
            v = new DiskGuardEscalation.Verdict(
                    critical ? DiskGuardEscalation.Action.CONTAIN : DiskGuardEscalation.Action.NONE,
                    0, 0L, true);
        } else {
            v = advanceTripState(critical);
        }
        if (v.action == DiskGuardEscalation.Action.NONE) return false;

        // Never reap while a deep op owns the box (clone/backup/restore/install). A reap mid-operation
        // would corrupt it. EnvironmentLock is the one owner of "is a stop-class op running".
        if (deepOpActive(ctx)) {
            Log.w(TAG, "K2GO-386: disk critical but a deep op holds the box; not reaping this tick");
            return false;
        }

        boolean reaped = EnvironmentProcess.reapBox(ctx);
        long reclaimed = reclaimRunawayLog(ctx);

        if (v.action == DiskGuardEscalation.Action.ESCALATE) {
            // Last resort: the fill keeps returning after restarts. Stop and stay down through the one
            // persisted lever, and tell the user. The user re-enables the server after freeing space.
            ServerLifecycleReconciler.get().setUserWantsOn(ctx, false);
            notifyUser(ctx);
            report(ctx, "escalated_stopped", floorBytes, reaped, reclaimed, v.tripCount);
            Log.w(TAG, "K2GO-386: recurring disk pressure (trip " + v.tripCount + "): stopped and staying down");
        } else {
            // Default: keep the system alive. Leave desired=UP and ask the reconciler to relaunch a fresh
            // box now. Report only the first trip of a spell so a thrash does not spam telemetry.
            ServerLifecycleReconciler.get().requestReconcileNow();
            if (v.firstOfSpell) report(ctx, "contained", floorBytes, reaped, reclaimed, v.tripCount);
            Log.w(TAG, "K2GO-386: contained disk pressure (trip " + v.tripCount + "): reaped=" + reaped
                    + ", reclaimed=" + reclaimed + " B, box restarting");
        }
        return true;
    }

    /**
     * True only if free space is CRITICAL on two reads separated by {@link #CONFIRM_DELAY_MS}. Both reads
     * are live (StatFs), so this debounces a momentary spike; it never acts on a single reading.
     */
    private static boolean confirmCritical(Context ctx, long floorBytes) {
        Long free = StorageProbe.freeBytes(ctx);
        if (DiskGuardPolicy.evaluate(free, floorBytes) != DiskGuardPolicy.Level.CRITICAL) return false;
        try {
            Thread.sleep(CONFIRM_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        Long free2 = StorageProbe.freeBytes(ctx);
        boolean stillCritical = DiskGuardPolicy.evaluate(free2, floorBytes) == DiskGuardPolicy.Level.CRITICAL;
        if (!stillCritical) {
            Log.i(TAG, "K2GO-386: free space recovered on re-read (" + free2 + " B); not acting");
        }
        return stillCritical;
    }

    /** Advance the shared trip state with the pure rule and return the verdict. */
    private static synchronized DiskGuardEscalation.Verdict advanceTripState(boolean critical) {
        DiskGuardEscalation.Verdict v = DiskGuardEscalation.next(
                critical, SystemClock.elapsedRealtime(), lastTripElapsedMs, tripCount,
                TRIP_WINDOW_MS, ESCALATE_AFTER_TRIPS);
        tripCount = v.tripCount;
        lastTripElapsedMs = v.lastElapsedMs;
        return v;
    }

    /** True when a stop-class operation (clone/backup/restore/install) currently holds the box. */
    private static boolean deepOpActive(Context ctx) {
        return EnvironmentLock.currentHolder(ctx).executionClass == Operation.ExecutionClass.STOPPED;
    }

    /** Report the event to developers through the delivery backbone (unattended; not user-facing). */
    private static void report(Context ctx, String action, long floorBytes, boolean reaped,
                              long reclaimed, int trip) {
        try {
            String json = new JSONObject()
                    .put("event", "disk_guard")
                    .put("action", action)
                    .put("floor_bytes", floorBytes)
                    .put("reaped", reaped)
                    .put("reclaimed_bytes", reclaimed)
                    .put("trip", trip)
                    .put("ts", System.currentTimeMillis())
                    .toString();
            DeliveryManager.with(ctx).enqueueAnalytics(json);
        } catch (Exception e) {
            Log.w(TAG, "K2GO-386: could not enqueue disk-guard report", e);
        }
    }

    /**
     * Truncate the biggest {@code *.log} file anywhere under the box's {@code /var/log} to reclaim the
     * space the runaway consumed (a real file that persists after its writer dies). Recurses
     * subdirectories (for example {@code /var/log/nginx/}) and only considers {@code .log} files over
     * {@link #RUNAWAY_LOG_MIN_BYTES}, so a normal or non-log file is never touched. Best-effort. Returns
     * the bytes reclaimed, or 0.
     */
    private static long reclaimRunawayLog(Context ctx) {
        File varLog = new File(ctx.getFilesDir(), "rootfs/installed-rootfs/iiab/var/log");
        File biggest = biggestLogUnder(varLog, null);
        if (biggest == null || biggest.length() < RUNAWAY_LOG_MIN_BYTES) return 0L;
        long size = biggest.length();
        try (FileOutputStream truncate = new FileOutputStream(biggest)) {
            // opening for write with no append truncates to zero
            Log.w(TAG, "K2GO-386: truncated runaway log " + biggest.getName() + " (" + size + " B)");
            return size;
        } catch (Exception e) {
            Log.w(TAG, "K2GO-386: could not truncate " + biggest.getName(), e);
            return 0L;
        }
    }

    /**
     * The biggest {@code *.log} regular file in the tree rooted at {@code dir}, or {@code best} if none is
     * bigger. Name-filtered to {@code .log} so a non-log large file is never a candidate. Bounded to the
     * small {@code /var/log} tree. Best-effort (unreadable dirs are skipped).
     */
    private static File biggestLogUnder(File dir, File best) {
        File[] entries = dir.listFiles();
        if (entries == null) return best;
        for (File f : entries) {
            if (f.isDirectory()) {
                best = biggestLogUnder(f, best);
            } else if (f.isFile() && f.getName().endsWith(".log")
                    && (best == null || f.length() > best.length())) {
                best = f;
            }
        }
        return best;
    }

    /**
     * Warn the user that the box was stopped to protect the device. Best-effort: a no-op if the
     * POST_NOTIFICATIONS permission is not granted (API 33+). The teardown still happened.
     */
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
