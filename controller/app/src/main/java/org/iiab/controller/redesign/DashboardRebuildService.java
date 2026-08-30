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
    /** ADFA-5333: re-own an already-running rebuild (poll only, never POST a new one). */
    public static final String ACTION_ATTACH = "org.iiab.controller.DASHBOARD_UPDATE_ATTACH";
    /** ADFA-5333: request a clean cancel (from the notification action or the card button). */
    public static final String ACTION_CANCEL = "org.iiab.controller.DASHBOARD_UPDATE_CANCEL";
    /** App-internal broadcast on every state change so a visible card can reflect it live. */
    public static final String ACTION_STATE = "org.iiab.controller.DASHBOARD_UPDATE_STATE";
    public static final String EXTRA_STATE = "state";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_ERROR = "error";
    public static final String STATE_CANCELLED = "cancelled";

    private static final long POLL_MS = 2500L;
    /**
     * ADFA-5343 (Phase 3B): a wall-clock backstop so a WEDGED rebuild cannot defer the reconciler forever
     * (it defers while this holds the lock — DASHBOARD self-restarts the server, ADR-5343a). Completion is
     * normally the server's own signal with no time cap; this only catches a rebuild that never reaches a
     * terminal state.
     *
     * ADR-5343a §12: device measurement (recompile 1.2.7→1.2.9 in-proot) showed real builds up to ~12 min
     * on the slowest device (HMD TA-1039), so the original 5 min sat BELOW real Oppo/HMD builds and
     * false-fired mid-build. INTERIM: a conservative cap set safely above the worst real build (30 min) —
     * it never trips a slow-but-live rebuild (which would release the lock and let the reconciler interfere
     * mid-swap), only a truly-hung one. TARGET (dashboard/rebuild follow-up, §12): replace with a
     * no-CPU-for-N movement backstop, the P4 module-stall-detector shape, device-independent.
     */
    private static final long REBUILD_STALL_MS = 30 * 60_000L;

    /** True while a background rebuild is in flight, so a card opening mid-update shows the indicator
     *  without waiting for a broadcast. Process-scoped; resets to false if the process is recreated. */
    private static volatile boolean RUNNING = false;
    public static boolean isRunning() { return RUNNING; }

    private final Handler poller = new Handler(Looper.getMainLooper());
    private boolean started = false;    // one rebuild per service instance; ignore re-delivered starts
    private boolean cancelling = false; // a cancel request is in flight; ignore repeats
    private long startedAtMs = 0L;      // ADFA-5343 (Phase 3B): monotonic start, for the stall backstop

    /** Kick a NEW background live update (POST + poll). */
    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, DashboardRebuildService.class).setAction(ACTION_START));
    }

    /** Re-own a rebuild that is ALREADY running on the box (e.g. our process was killed mid-update): poll
     *  it to completion and restore the notification, but never POST a new rebuild — so a card that sees
     *  the box still building can attach without risking a second run if the original just finished. */
    public static void attach(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, DashboardRebuildService.class).setAction(ACTION_ATTACH));
    }

    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() must be answered by startForeground(), including a redundant
        // start delivered while a rebuild is already in flight (user re-taps Rebuild) or a cancel request
        // — otherwise the system raises "did not call startForeground". It's idempotent, so satisfy the
        // contract first.
        startForeground(NOTIFICATION_ID, buildOngoing());
        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(action)) { requestCancel(); return START_NOT_STICKY; }
        if (started) return START_NOT_STICKY;
        started = true;
        RUNNING = true;
        startedAtMs = android.os.SystemClock.elapsedRealtime();   // ADFA-5343 (Phase 3B): arm the stall backstop
        broadcastState(STATE_RUNNING);
        if (ACTION_ATTACH.equals(action)) {
            // Re-own: the box is already building, so just poll to completion (a first poll that finds
            // done/error finishes at once — no new rebuild is ever launched).
            pollStatus();
        } else {
            DashboardClient.rebuildStart(new DashboardClient.RebuildStartCb() {
                @Override public void onStarted(boolean alreadyRunning) { pollStatus(); }
                @Override public void onErr(String message) { finish(STATE_ERROR, R.string.k2go_dash_live_start_failed); }
            });
        }
        return START_NOT_STICKY;
    }

    /** ADFA-5333: ask the box to cancel. On success stop cleanly (the box left the live dashboard as it
     *  was); if it's too late (promoting) or unreachable, keep running and let the update finish — the
     *  swap is seconds away, so a half-cancel is worse than letting it complete. */
    private void requestCancel() {
        if (cancelling) return;
        cancelling = true;
        DashboardClient.rebuildCancel(new DashboardClient.RebuildCancelCb() {
            @Override public void onResult(boolean cancelled, boolean promoting) {
                if (cancelled) { finishCancelled(); return; }
                cancelling = false;
                // Not cancelled. If this instance isn't actually running a rebuild (a stale cancel-only
                // start on an old notification), clear the device side so no orphan notification lingers.
                if (!started) { finishCancelled(); return; }
                // Still updating: re-assert RUNNING so a visible card re-enables its Cancel, then say why.
                broadcastState(STATE_RUNNING);
                toast(promoting ? R.string.k2go_dash_cancel_too_late : R.string.k2go_dash_cancel_failed);
            }
            @Override public void onErr(String message) {
                cancelling = false;
                if (!started) { finishCancelled(); return; }   // couldn't reach the box, nothing to keep
                broadcastState(STATE_RUNNING);                  // update keeps going; let the card retry
                toast(R.string.k2go_dash_cancel_failed);
            }
        });
    }

    /** Cancelled cleanly: drop the poll and the notification entirely (leave nothing behind) and tell a
     *  visible card to re-enable Rebuild. */
    private void finishCancelled() {
        poller.removeCallbacksAndMessages(null);
        RUNNING = false;
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        broadcastState(STATE_CANCELLED);
        stopSelf();
    }

    private void toast(@StringRes int msgRes) {
        android.widget.Toast.makeText(getApplicationContext(), msgRes, android.widget.Toast.LENGTH_LONG).show();
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

    private void schedule() {
        // ADFA-5343 (Phase 3B): the stall backstop. A rebuild that never reaches terminal within
        // REBUILD_STALL_MS is failed here so RUNNING clears, the lock drops, and the reconciler resumes
        // (blue-green rolls back on failure, so failing a wedged rebuild is safe). A live rebuild reaches
        // done/error long before this; only a genuinely stuck one hits it.
        if (startedAtMs > 0L
                && android.os.SystemClock.elapsedRealtime() - startedAtMs > REBUILD_STALL_MS) {
            finish(STATE_ERROR, R.string.k2go_dash_live_error);
            return;
        }
        poller.postDelayed(this::pollStatus, POLL_MS);
    }

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
     *  update runs it stays put as the way back to the in-app indicator. Carries a Cancel action. */
    private Notification buildOngoing() {
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, DashboardRebuildService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_dash_live_title))
                .setContentText(getString(R.string.k2go_dash_live_running))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(openDashboardDetail())
                .setOngoing(true)
                .setAutoCancel(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_dash_cancel), cancel)
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
