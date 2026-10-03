/*
 * ============================================================================
 * Name        : CodeAssetsDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-443. Foreground shell for the Code on the Go build-assets download on the durable
 *               job engine. A single-item session (POST /code-assets/download + poll), modeled on
 *               ZimDownloadService: it owns the notification and delegates the queue, the shared
 *               RestContentClient, and all progress/pause/reconnect state to ContentDownloadSession.
 *               The heavy work (aria2, resume, verify, swap) runs on the box; the device POSTs + polls.
 *               It survives the app going to the background so a ~0.8 GB download is not tied to a view.
 *
 *               Slice 2a (Option A): a thin per-type service clone; the service shells across ZIM /
 *               maps / code-assets / add-ons are unified by the content-module shared extraction (the
 *               next slice), not here.
 * ============================================================================
 */
package org.appdevforall.k2go.codeassets.presentation;

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
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.redesign.ContentDownloadSession;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CodeAssetsDownloadService extends Service implements ContentDownloadSession.Host {

    private static final String CHANNEL_ID = "code_assets_download_channel";
    private static final int NOTIFICATION_ID = 7;

    public static final String ACTION_START = "org.appdevforall.k2go.CODE_ASSETS_DOWNLOAD_START";
    public static final String ACTION_PAUSE = "org.appdevforall.k2go.CODE_ASSETS_DOWNLOAD_PAUSE";
    public static final String ACTION_RESUME = "org.appdevforall.k2go.CODE_ASSETS_DOWNLOAD_RESUME";
    public static final String ACTION_CANCEL = "org.appdevforall.k2go.CODE_ASSETS_DOWNLOAD_CANCEL";
    public static final String ACTION_RETRY = "org.appdevforall.k2go.CODE_ASSETS_DOWNLOAD_RETRY";

    // The server runner reads the manifest itself; it ignores the job items, so a single sentinel
    // satisfies POST /code-assets/download (which requires a non-empty items/ids).
    private static final String SENTINEL = "build-assets";

    private static final ContentDownloadSession SESSION = new ContentDownloadSession("code-assets");

    /** Adapts to the session listener; the UI passes {@code this::render}. */
    public interface Listener { void onUpdate(); }

    // ---- static API the UI observes (delegates to the shared session) --------------------------
    public static boolean isRunning() { return SESSION.isRunning(); }
    public static boolean isPaused() { return SESSION.isPaused(); }
    public static boolean hasSession() { return SESSION.hasSession(); }
    public static boolean isComplete() { return SESSION.isComplete(); }
    public static boolean hasFailed() { return SESSION.hasFailed(); }
    public static int percent() { return SESSION.percent(); }
    public static long speed() { return SESSION.speed(); }
    public static int reconnectAttempt() { return SESSION.reconnectAttempt(); }
    public static int reconnectTotal() { return SESSION.reconnectTotal(); }
    public static void setListener(Listener l) { SESSION.setListener(l == null ? null : l::onUpdate); }

    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, CodeAssetsDownloadService.class).setAction(ACTION_START));
    }

    public static void pause(Context ctx) {
        if (!SESSION.isRunning()) return;
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, CodeAssetsDownloadService.class).setAction(ACTION_PAUSE));
    }

    public static void resume(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, CodeAssetsDownloadService.class).setAction(ACTION_RESUME));
    }

    public static void cancel(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, CodeAssetsDownloadService.class).setAction(ACTION_CANCEL));
    }

    /** Re-queue the failed item and resume (the box resumes the partial via aria2 --continue). */
    public static void retry(Context ctx) {
        if (SESSION.requeueFailed() && !SESSION.isRunning()) {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, CodeAssetsDownloadService.class).setAction(ACTION_RETRY));
        }
    }

    public static void finishSession() { SESSION.purge(); }

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); SESSION.attach(this); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SESSION.attach(this);
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CANCEL.equals(action)) { SESSION.cancelAndPurge(); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(action)) { SESSION.pauseActive(); return START_NOT_STICKY; }
        if (ACTION_RESUME.equals(action)) { SESSION.resumeActive(); return START_NOT_STICKY; }

        if (SESSION.isRunning()) return START_NOT_STICKY;   // duplicate start/retry: ignore

        if (ACTION_RETRY.equals(action)) {
            if (!SESSION.hasSession()) { stopSelf(); return START_NOT_STICKY; }
            startForeground(NOTIFICATION_ID, buildNotification());
            SESSION.resumeQueue();
        } else { // ACTION_START: a fresh single-item session
            String[] keys = { "code-assets" };
            String[] labels = { getString(R.string.k2go_card_code_assets) };
            long[] sizes = { 0L };   // count-based: the UI shows the item percent directly
            JSONObject[] bodies = new JSONObject[1];
            try { bodies[0] = new JSONObject().put("ids", new JSONArray().put(SENTINEL)); }
            catch (Exception e) { bodies[0] = new JSONObject(); }
            startForeground(NOTIFICATION_ID, buildNotification());
            SESSION.begin(keys, labels, sizes, bodies);
        }
        return START_NOT_STICKY;
    }

    // ---- ContentDownloadSession.Host -----------------------------------------------------------
    @Override public void notify(String label) {
        if (!SESSION.isRunning()) return;
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override public void stop() { main.post(() -> { stopForeground(true); stopSelf(); }); }

    @Override public void onItemDone(String key) { /* build assets have no wishlist to prune */ }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_card_code_assets), NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent cancel = new Intent(this, CodeAssetsDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_code_assets_updating))
                .setContentText(getString(R.string.k2go_card_code_assets))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_dash_cancel), cancelIntent)
                .build();
    }
}
