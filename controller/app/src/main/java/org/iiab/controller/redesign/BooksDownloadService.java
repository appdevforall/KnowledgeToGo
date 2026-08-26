/*
 * ============================================================================
 * Name        : BooksDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4850 / ADFA-4893. Foreground shell for the Books stream: owns the notification and
 *               the Books-specific bits (id/title/url arrays, the {items:[{id,title,url}]} body,
 *               item-count percent) and delegates the queue + client + progress/pause/reconnect state
 *               to the shared ContentDownloadSession — the same engine ZIM uses, so both behave
 *               identically. The server downloads each EPUB from Gutenberg and uploads it into
 *               Calibre-Web; the device only POSTs + polls.
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
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.iiab.controller.R;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BooksDownloadService extends Service implements ContentDownloadSession.Host {

    private static final String CHANNEL_ID = "books_download_channel";
    private static final int NOTIFICATION_ID = 6;

    public static final String ACTION_START = "org.iiab.controller.BOOKS_DOWNLOAD_START";
    public static final String ACTION_RETRY = "org.iiab.controller.BOOKS_DOWNLOAD_RETRY";
    public static final String ACTION_CANCEL = "org.iiab.controller.BOOKS_DOWNLOAD_CANCEL";
    public static final String ACTION_PAUSE = "org.iiab.controller.BOOKS_DOWNLOAD_PAUSE";
    public static final String ACTION_RESUME = "org.iiab.controller.BOOKS_DOWNLOAD_RESUME";
    public static final String EXTRA_IDS = "ids";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_URLS = "urls";

    // Per-item status — same values the UI/checklist already use, sourced from the shared session.
    public static final int PENDING = ContentDownloadSession.PENDING;
    public static final int ACTIVE = ContentDownloadSession.ACTIVE;
    public static final int ADDING = ContentDownloadSession.PROCESSING;
    public static final int DONE = ContentDownloadSession.DONE;
    public static final int FAILED = ContentDownloadSession.FAILED;

    /** Kept for the callers that pass {@code this::render}; adapts to the session listener. */
    public interface Listener { void onUpdate(); }

    private static final ContentDownloadSession SESSION = new ContentDownloadSession("books");
    // The whole per-item snapshot (id/title/url-as-bodies, titles-as-labels) now lives in SESSION, so
    // status and the metadata the UI indexes off it stay length-consistent by construction (ADFA-4893).

    // ---- static API the UI observes (delegates to the shared session) -------------------------
    public static boolean isRunning() { return SESSION.isRunning(); }
    public static boolean isPaused() { return SESSION.isPaused(); }
    public static int reconnectAttempt() { return SESSION.reconnectAttempt(); }
    public static int reconnectTotal() { return SESSION.reconnectTotal(); }
    public static boolean hasSession() { return SESSION.hasSession(); }
    public static boolean isComplete() { return SESSION.isComplete(); }
    public static boolean isActiveNow() { return SESSION.isActiveNow(); }
    public static boolean hasFailed() { return SESSION.hasFailed(); }
    public static int[] status() { return SESSION.status(); }
    public static int index() { return SESSION.index(); }
    public static int overallPercent() { return SESSION.overallPercent(); }
    public static String[] titles() { return SESSION.labels(); }
    public static void setListener(Listener l) { SESSION.setListener(l == null ? null : l::onUpdate); }

    public static void start(Context ctx, String[] ids, String[] titles, String[] urls) {
        Intent i = new Intent(ctx, BooksDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_IDS, ids).putExtra(EXTRA_TITLES, titles).putExtra(EXTRA_URLS, urls);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void pause(Context ctx) {
        if (!SESSION.isRunning()) return;
        ContextCompat.startForegroundService(ctx, new Intent(ctx, BooksDownloadService.class).setAction(ACTION_PAUSE));
    }

    public static void resume(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, BooksDownloadService.class).setAction(ACTION_RESUME));
    }

    /** ADFA-4893: re-queue ALL failed books and resume — the status-screen Retry (Pause morphs into it). */
    public static void retryFailed(Context ctx) {
        if (SESSION.requeueFailed() && !SESSION.isRunning()) {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, BooksDownloadService.class).setAction(ACTION_RETRY));
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

        if (SESSION.isRunning()) return START_NOT_STICKY;

        if (ACTION_RETRY.equals(action)) {
            if (!SESSION.hasSession()) { stopSelf(); return START_NOT_STICKY; }
            startForeground(NOTIFICATION_ID, buildNotification(SESSION.label(SESSION.index())));
            SESSION.resumeQueue();
        } else { // ACTION_START: fresh session from the extras
            String[] ids = intent.getStringArrayExtra(EXTRA_IDS);
            if (ids == null || ids.length == 0) { stopSelf(); return START_NOT_STICKY; }
            String[] titles = intent.getStringArrayExtra(EXTRA_TITLES);
            String[] urls = intent.getStringArrayExtra(EXTRA_URLS);
            if (titles == null) titles = ids;
            if (urls == null) urls = new String[ids.length];
            JSONObject[] bodies = new JSONObject[ids.length];
            for (int i = 0; i < ids.length; i++) {
                try {
                    JSONObject item = new JSONObject().put("id", ids[i]).put("title", titles[i]).put("url", urls[i]);
                    bodies[i] = new JSONObject().put("items", new JSONArray().put(item));
                } catch (Exception e) { bodies[i] = new JSONObject(); }
            }
            startForeground(NOTIFICATION_ID, buildNotification(titles.length > 0 ? titles[0] : ""));
            SESSION.begin(titles, null, bodies);   // books: no per-item byte size -> count-based percent
        }
        return START_NOT_STICKY;
    }

    // ---- ContentDownloadSession.Host (Books specifics) ----------------------------------------
    @Override public void notify(String label) {
        if (!SESSION.isRunning()) return;
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, buildNotification(label));
    }

    @Override public void stop() { main.post(() -> { stopForeground(true); stopSelf(); }); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_books_dl_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title) {
        Intent openI = new Intent(this, SetupProgressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, openI,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, BooksDownloadService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_books_dl_notif_title))
                .setContentText(title)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(open)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_zim_notif_cancel), cancel)
                .build();
    }
}
