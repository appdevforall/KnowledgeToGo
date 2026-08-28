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
 *               waiting". State changes are broadcast ({@link #ACTION_STATE}: running | done | error) so a
 *               visible dashboard card shows an in-progress indicator and refreshes on completion; a
 *               static {@link #isRunning()} lets a card that opens mid-update pick the indicator up.
 *
 *               The ongoing notification is the way back to that indicator: it is not dismissible while
 *               the update runs, and tapping it deep-links to the dashboard card (Module management ->
 *               Dashboard) rather than cancelling. On a terminal state it becomes a dismissible result.
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
    /** App-internal broadcast on every state change so a visible card can reflect it live. */
    public static final String ACTION_STATE = "org.iiab.controller.DASHBOARD_UPDATE_STATE";
    public static final String EXTRA_STATE = "state";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_ERROR = "error";

    private static final long POLL_MS = 2500L;

    /** True while a background rebuild is in flight, so a card opening mid-update shows the indicator
     *  without waiting for a broadcast. Process-scoped; resets to false if the process is recreated. */
    private static volatile boolean RUNNING = false;
    public static boolean isRunning() { return RUNNING; }

    private final Handler poller = new Handler(Looper.getMainLooper());
    private boolean started = false;   // one rebuild per service instance; ignore re-delivered starts

    /** Kick the background live update. Safe to call again while running — the box reports 409 and the
     *  service just keeps polling the in-flight rebuild (so a card can re-own a lost session by calling
     *  this after it sees the box still rebuilding). */
    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, DashboardRebuildService.class).setAction(ACTION_START));
    }

    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() must be answered by startForeground(), including a redundant
        // start delivered while a rebuild is already in flight (user re-taps Rebuild) — otherwise the
        // system raises "did not call startForeground". It's idempotent, so satisfy the contract first.
        startForeground(NOTIFICATION_ID, buildOngoing());
        if (started) return START_NOT_STICKY;
        started = true;
        RUNNING = true;
        broadcastState(STATE_RUNNING);
        DashboardClient.rebuildStart(new DashboardClient.RebuildStartCb() {
            @Override public void onStarted(boolean alreadyRunning) { pollStatus(); }
            @Override public void onErr(String message) { finish(STATE_ERROR, R.string.k2go_dash_live_start_failed); }
        });
        return START_NOT_STICKY;
    }

    /** Poll the box until it reports a terminal state. No time cap: completion is the server's signal
     *  (done/error), not a clock. A `running` state or a transient unreachable window (dash-node
     *  restarting during the swap) just means "keep waiting". */
    private void pollStatus() {
        DashboardClient.rebuildStatus(new DashboardClient.RebuildStatusCb() {
            @Override public void onState(String state) {
                if (STATE_DONE.equals(state)) { finish(STATE_DONE, R.string.k2go_dash_live_done); return; }
                if (STATE_ERROR.equals(state)) { finish(STATE_ERROR, R.string.k2go_dash_live_error); return; }
                schedule();
            }
            @Override public void onErr(String message) { schedule(); }   // restarting mid-swap; keep waiting
        });
    }

    private void schedule() { poller.postDelayed(this::pollStatus, POLL_MS); }

    /** Terminal: replace the ongoing notification with a final dismissible one, broadcast the result so a
     *  visible card hides the indicator and refreshes in place, then stop. */
    private void finish(String state, @StringRes int msgRes) {
        poller.removeCallbacksAndMessages(null);
        RUNNING = false;
        // Keep the final notification on screen after we drop the foreground state.
        stopForeground(Service.STOP_FOREGROUND_DETACH);
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, buildFinal(msgRes));
        broadcastState(state);
        stopSelf();
    }

    @Override public void onDestroy() { RUNNING = false; super.onDestroy(); }   // safety if torn down early

    private void broadcastState(String state) {
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName()).putExtra(EXTRA_STATE, state));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_dash_update_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }

    /** Deep-link to Module management -> Dashboard (the card that shows the in-progress indicator), so the
     *  notification is a way back into the update rather than a dead end. */
    private PendingIntent openDashboardDetail() {
        Intent openI = new Intent(this, SetupLibraryActivity.class)
                .putExtra(SetupLibraryActivity.EXTRA_MODULE_MGMT, true)
                .putExtra(SetupLibraryActivity.EXTRA_DASHBOARD_DETAIL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, openI,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Ongoing "updating…" notification. Not dismissible and does NOT auto-cancel on tap — while the
     *  update runs it stays put as the way back to the in-app indicator. */
    private Notification buildOngoing() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_dash_live_title))
                .setContentText(getString(R.string.k2go_dash_live_running))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(openDashboardDetail())
                .setOngoing(true)
                .setAutoCancel(false)
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
                .setContentIntent(openDashboardDetail())
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
