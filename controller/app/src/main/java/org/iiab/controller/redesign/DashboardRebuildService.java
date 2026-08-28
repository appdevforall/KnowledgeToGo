/*
 * ============================================================================
 * Name        : DashboardRebuildService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5333. Runs the LIVE (REST) dash-node update in the background instead of behind a
 *               blocking modal. The dashboard is a REST API with a blue-green self-rebuild: the box does
 *               the work detached (POST /system/dashboard/rebuild returns 202, then stages/smoke-tests/
 *               atomically swaps and rolls back on failure). This foreground service owns only a
 *               notification and the status poll, so the user navigates freely while it runs.
 *
 *               Completion is the SERVER'S signal, not a clock: it polls the rebuild status until the box
 *               reports a terminal state (done | error) — there is no fixed time cap. A `running` state,
 *               or the brief unreachable window while dash-node restarts mid-swap, just means "keep
 *               waiting". On a terminal state it broadcasts {@link #ACTION_DONE} so a visible dashboard
 *               card can refresh its version/pill in place, and stops.
 *
 *               Scope: the live REST path only (dash-node >= 1.2.0, routed here by DashboardRebuild). The
 *               proot bridge path (< 1.2.0) genuinely stops the box and keeps its own guarded screen.
 * ============================================================================
 */
package org.iiab.controller.redesign;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;

public final class DashboardRebuildService extends Service {

    private static final String CHANNEL_ID = "dashboard_update_channel";
    private static final int NOTIFICATION_ID = 10;   // app-global; distinct from the other services

    public static final String ACTION_START = "org.iiab.controller.DASHBOARD_UPDATE_START";
    /** App-internal broadcast fired once when the live update reaches a terminal state. */
    public static final String ACTION_DONE = "org.iiab.controller.DASHBOARD_UPDATE_DONE";
    public static final String EXTRA_RESULT = "result";
    public static final String RESULT_DONE = "done";
    public static final String RESULT_ERROR = "error";

    private static final long POLL_MS = 2500L;

    private final Handler poller = new Handler(Looper.getMainLooper());
    private boolean started = false;   // one rebuild per service instance; ignore re-delivered starts

    /** Kick the background live update. Safe to call again while running — the box reports 409 and the
     *  service just keeps polling the in-flight rebuild. */
    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, DashboardRebuildService.class).setAction(ACTION_START));
    }

    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) return START_NOT_STICKY;
        started = true;
        startForeground(NOTIFICATION_ID, buildOngoing());
        DashboardClient.rebuildStart(new DashboardClient.RebuildStartCb() {
            @Override public void onStarted(boolean alreadyRunning) { pollStatus(); }
            @Override public void onErr(String message) { finish(RESULT_ERROR, R.string.k2go_dash_live_start_failed); }
        });
        return START_NOT_STICKY;
    }

    /** Poll the box until it reports a terminal state. No time cap: completion is the server's signal
     *  (done/error), not a clock. A `running` state or a transient unreachable window (dash-node
     *  restarting during the swap) just means "keep waiting". */
    private void pollStatus() {
        DashboardClient.rebuildStatus(new DashboardClient.RebuildStatusCb() {
            @Override public void onState(String state) {
                if (RESULT_DONE.equals(state)) { finish(RESULT_DONE, R.string.k2go_dash_live_done); return; }
                if (RESULT_ERROR.equals(state)) { finish(RESULT_ERROR, R.string.k2go_dash_live_error); return; }
                schedule();
            }
            @Override public void onErr(String message) { schedule(); }   // restarting mid-swap; keep waiting
        });
    }

    private void schedule() { poller.postDelayed(this::pollStatus, POLL_MS); }

    /** Terminal: replace the ongoing notification with a final dismissible one, broadcast the result so a
     *  visible card refreshes in place (reuses DashboardDetailFragment's live-update refresh), then stop. */
    private void finish(String result, @StringRes int msgRes) {
        poller.removeCallbacksAndMessages(null);
        // Keep the final notification on screen after we drop the foreground state.
        stopForeground(Service.STOP_FOREGROUND_DETACH);
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, buildFinal(msgRes));
        sendBroadcast(new Intent(ACTION_DONE).setPackage(getPackageName()).putExtra(EXTRA_RESULT, result));
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_dash_update_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }

    private PendingIntent openApp() {
        Intent openI = new Intent(this, SetupLibraryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, openI,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Ongoing "updating…" notification the user can ignore while the box rebuilds. */
    private Notification buildOngoing() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_dash_live_title))
                .setContentText(getString(R.string.k2go_dash_live_running))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .build();
    }

    /** Final result notification (done/error) — dismissible, auto-cancels on tap. */
    private Notification buildFinal(@StringRes int msgRes) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_dash_live_title))
                .setContentText(getString(msgRes))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
