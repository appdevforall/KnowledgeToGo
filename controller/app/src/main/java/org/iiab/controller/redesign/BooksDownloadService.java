/*
 * ============================================================================
 * Name        : BooksDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4850. Foreground download manager for Books, following the ZIM pattern
 *               (CLAUDE.md): sequential, ONE AT A TIME (kind to Project Gutenberg), continuing
 *               past a failed item, per-item retry, "Finish" to clear the session. Each book is
 *               its own durable REST job: POST /api/books/download {items:[{id,title,url}]} then
 *               poll /api/books/jobs/:id (the server downloads the EPUB from Gutenberg and
 *               uploads it into Calibre-Web). The device only POSTs + polls.
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

public final class BooksDownloadService extends Service {

    private static final String CHANNEL_ID = "books_download_channel";
    private static final int NOTIFICATION_ID = 6;

    public static final String ACTION_START = "org.iiab.controller.BOOKS_DOWNLOAD_START";
    public static final String ACTION_RETRY = "org.iiab.controller.BOOKS_DOWNLOAD_RETRY";
    public static final String ACTION_CANCEL = "org.iiab.controller.BOOKS_DOWNLOAD_CANCEL";
    public static final String ACTION_PAUSE = "org.iiab.controller.BOOKS_DOWNLOAD_PAUSE";     // ADFA-4893
    public static final String ACTION_RESUME = "org.iiab.controller.BOOKS_DOWNLOAD_RESUME";   // ADFA-4893
    public static final String EXTRA_IDS = "ids";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_URLS = "urls";

    public static final int PENDING = 0, ACTIVE = 1, ADDING = 2, DONE = 3, FAILED = 4;

    public interface Listener { void onUpdate(); }

    // ---- shared session state (observed by the Downloads screen) ----
    private static volatile boolean sRunning = false;
    private static String[] sIds = new String[0];
    private static String[] sTitles = new String[0];
    private static String[] sUrls = new String[0];
    private static int[] sStatus = new int[0];
    private static int sIndex = 0;
    private static volatile long sLastProgressAt = 0L;   // ADFA-5146: last-progress heartbeat (elapsedRealtime)
    private static volatile boolean sPaused = false;     // ADFA-4893: active job paused on the server
    private static volatile int sReconnectAttempt = 0;   // ADFA-4893: >0 while the server reconnects (n of total)
    private static volatile int sReconnectTotal = 0;
    private static Listener sListener;

    public static boolean isRunning() { return sRunning; }
    public static boolean isPaused() { return sPaused; }
    public static int reconnectAttempt() { return sReconnectAttempt; }
    public static int reconnectTotal() { return sReconnectTotal; }
    public static boolean hasSession() { return sIds.length > 0; }
    public static boolean isComplete() {
        if (sIds.length == 0 || sRunning) return false;
        for (int st : sStatus) if (st == PENDING || st == ACTIVE || st == ADDING) return false;
        return true;
    }
    /** ADFA-5146: busy only while the poll heartbeat is fresh — a killed service lets it go cold. */
    public static boolean isActiveNow() {
        return hasSession() && !isComplete()
                && org.iiab.controller.env.Freshness.fresh(
                        sLastProgressAt, android.os.SystemClock.elapsedRealtime(),
                        org.iiab.controller.env.Freshness.STALE_MS);
    }
    public static String[] titles() { return sTitles; }
    public static int[] status() { return sStatus; }
    public static int index() { return sIndex; }
    public static void setListener(Listener l) { sListener = l; }

    /** ADFA-4893: true when any book is FAILED (so the status screen can offer a single Retry). */
    public static boolean hasFailed() {
        for (int st : sStatus) if (st == FAILED) return true;
        return false;
    }

    /** ADFA-4893: overall percent by item count (books have no per-item byte size) — one source for the
     *  status-screen bar and the index row bar, mirroring ZimDownloadService.overallPercent(). */
    public static int overallPercent() {
        int n = sStatus.length;
        if (n == 0) return isComplete() ? 100 : 0;
        int done = 0;
        for (int st : sStatus) if (st == DONE || st == FAILED) done++;
        return (int) Math.min(100, (long) done * 100 / n);
    }

    public static void start(Context ctx, String[] ids, String[] titles, String[] urls) {
        Intent i = new Intent(ctx, BooksDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_IDS, ids).putExtra(EXTRA_TITLES, titles).putExtra(EXTRA_URLS, urls);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** ADFA-4893: pause the active book job (server keeps state). No-op if nothing is running. */
    public static void pause(Context ctx) {
        if (!sRunning) return;
        ContextCompat.startForegroundService(ctx, new Intent(ctx, BooksDownloadService.class).setAction(ACTION_PAUSE));
    }

    /** ADFA-4893: resume a paused book job. */
    public static void resume(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, BooksDownloadService.class).setAction(ACTION_RESUME));
    }

    /** ADFA-4893: re-queue ALL failed books and resume — the status-screen Retry (Pause morphs into it). */
    public static void retryFailed(Context ctx) {
        boolean any = false;
        for (int i = 0; i < sStatus.length; i++) if (sStatus[i] == FAILED) { sStatus[i] = PENDING; any = true; }
        if (any && !sRunning) ContextCompat.startForegroundService(ctx,
                new Intent(ctx, BooksDownloadService.class).setAction(ACTION_RETRY));
    }

    public static void finishSession() {
        sIds = new String[0]; sTitles = new String[0]; sUrls = new String[0]; sStatus = new int[0];
        sIndex = 0; sRunning = false; sPaused = false;
        sReconnectAttempt = 0; sReconnectTotal = 0; sLastProgressAt = 0L;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private org.iiab.controller.content.RestContentClient client;   // ADFA-4893: shared REST client (books)

    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(action)) {
            if (client != null) client.cancel();
            sRunning = false; sPaused = false; sReconnectAttempt = 0; sReconnectTotal = 0;
            publish();
            main.post(() -> { stopForeground(true); stopSelf(); });
            return START_NOT_STICKY;
        }
        // ADFA-4893: pause/resume the active job, handled before the sRunning guard (paused == running).
        if (ACTION_PAUSE.equals(action)) {
            if (client != null) client.pause();
            sPaused = true; publish();
            return START_NOT_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            if (client != null) client.resume();
            sPaused = false; publish();
            return START_NOT_STICKY;
        }
        if (sRunning) return START_NOT_STICKY;

        if (ACTION_RETRY.equals(action)) {
            if (!hasSession()) { stopSelf(); return START_NOT_STICKY; }
        } else {
            String[] ids = intent.getStringArrayExtra(EXTRA_IDS);
            if (ids == null || ids.length == 0) { stopSelf(); return START_NOT_STICKY; }
            sIds = ids;
            sTitles = intent.getStringArrayExtra(EXTRA_TITLES);
            sUrls = intent.getStringArrayExtra(EXTRA_URLS);
            if (sTitles == null) sTitles = ids;
            if (sUrls == null) sUrls = new String[ids.length];
            sStatus = new int[ids.length];
            sIndex = 0; sPaused = false; sReconnectAttempt = 0; sReconnectTotal = 0;
        }
        sRunning = true;
        sLastProgressAt = android.os.SystemClock.elapsedRealtime();   // ADFA-5146: seed the heartbeat
        startForeground(NOTIFICATION_ID, buildNotification(currentTitle()));
        processNext();
        return START_NOT_STICKY;
    }

    private static int firstPending() {
        for (int i = 0; i < sStatus.length; i++) if (sStatus[i] == PENDING) return i;
        return -1;
    }

    private String currentTitle() { return sIndex >= 0 && sIndex < sTitles.length ? sTitles[sIndex] : ""; }

    private void processNext() {
        int i = firstPending();
        if (i < 0) { sessionComplete(); return; }
        sIndex = i; sStatus[i] = ACTIVE;
        publish();
        updateNotification(sTitles[i]);
        startItem(i);
    }

    private void startItem(final int i) {
        android.util.Log.i("K2Go-Provision", "books job start [" + i + "] id=" + sIds[i] + " title='" + sTitles[i] + "'");
        final JSONObject body;
        try {
            JSONObject item = new JSONObject().put("id", sIds[i]).put("title", sTitles[i]).put("url", sUrls[i]);
            body = new JSONObject().put("items", new JSONArray().put(item));
        } catch (Exception e) { sStatus[i] = FAILED; publish(); processNext(); return; }
        // ADFA-4893: books now share the ZIM REST client; the server owns reconnection (visible), and the
        // kickoff POST retry lives in the client. No app-side re-POST loop.
        client = new org.iiab.controller.content.RestContentClient("books");
        client.start(body, new org.iiab.controller.content.RestContentClient.Listener() {
            @Override public void onProgress(int percent, String speed) {
                if (sPaused) sPaused = false;
                sReconnectAttempt = 0;
                sLastProgressAt = android.os.SystemClock.elapsedRealtime();
                if (sStatus[i] != ADDING) sStatus[i] = ACTIVE;
                publish();
            }
            @Override public void onIndexing() { sStatus[i] = ADDING; sLastProgressAt = android.os.SystemClock.elapsedRealtime(); publish(); }
            @Override public void onPaused(int percent) {
                sPaused = true; sLastProgressAt = android.os.SystemClock.elapsedRealtime(); publish();
            }
            @Override public void onReconnecting(int attempt, int total) {
                sReconnectAttempt = attempt; sReconnectTotal = total;
                sLastProgressAt = android.os.SystemClock.elapsedRealtime(); publish();
            }
            @Override public void onLog(String line) { /* logcat only */ }
            @Override public void onDone() { android.util.Log.i("K2Go-Provision", "books job done [" + i + "]"); sStatus[i] = DONE; publish(); processNext(); }
            @Override public void onError(String message) {
                // ADFA-4893: server owns reconnection (visible); on give-up mark FAILED for a manual Retry.
                android.util.Log.w("K2Go-Provision", "books job [" + i + "] error: " + message);
                sStatus[i] = FAILED; sReconnectAttempt = 0; publish(); processNext();
            }
        });
    }

    private void sessionComplete() {
        sRunning = false; publish();
        main.post(() -> { stopForeground(true); stopSelf(); });
    }

    private void publish() { main.post(() -> { if (sListener != null) sListener.onUpdate(); }); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_books_dl_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String title) {
        // ADFA-4987: the redesign progress screen, not the legacy UI. ADFA-5074: the index, not
        // this stream's detail — it is the only surface that can end the run.
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

    private void updateNotification(String title) {
        if (!sRunning) return;
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, buildNotification(title));
    }

}
