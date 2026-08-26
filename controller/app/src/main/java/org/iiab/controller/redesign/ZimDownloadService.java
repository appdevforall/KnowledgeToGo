/*
 * ============================================================================
 * Name        : ZimDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849 / ADFA-4893. Foreground shell for the ZIM stream: it owns the notification
 *               and the ZIM-specific bits (file/label/byte arrays, the {ids:[..]} body, byte-weighted
 *               percent) and delegates the queue + client + progress/pause/reconnect state to the
 *               shared ContentDownloadSession. The static getters below keep the same API the UI has
 *               always observed. The heavy work runs on the server; the device only POSTs + polls.
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

public final class ZimDownloadService extends Service implements ContentDownloadSession.Host {

    private static final String CHANNEL_ID = "zim_download_channel";
    private static final int NOTIFICATION_ID = 5;

    public static final String ACTION_START = "org.iiab.controller.ZIM_DOWNLOAD_START";
    public static final String ACTION_RETRY = "org.iiab.controller.ZIM_DOWNLOAD_RETRY";
    public static final String ACTION_CANCEL = "org.iiab.controller.ZIM_DOWNLOAD_CANCEL";
    public static final String ACTION_PAUSE = "org.iiab.controller.ZIM_DOWNLOAD_PAUSE";
    public static final String ACTION_RESUME = "org.iiab.controller.ZIM_DOWNLOAD_RESUME";
    public static final String EXTRA_FILES = "files";
    public static final String EXTRA_LABELS = "labels";
    public static final String EXTRA_BYTES = "bytes";

    // Per-item status — same values the UI/checklist already use, sourced from the shared session.
    public static final int PENDING = ContentDownloadSession.PENDING;
    public static final int ACTIVE = ContentDownloadSession.ACTIVE;
    public static final int INDEXING = ContentDownloadSession.PROCESSING;
    public static final int DONE = ContentDownloadSession.DONE;
    public static final int FAILED = ContentDownloadSession.FAILED;

    /** Kept for the callers that pass {@code this::render}; adapts to the session listener. */
    public interface Listener { void onUpdate(); }

    private static final ContentDownloadSession SESSION = new ContentDownloadSession("kiwix");
    // ZIM-specific session data (labels/files/sizes); the queue/progress state lives in SESSION.
    private static String[] sFiles = new String[0];
    private static String[] sLabels = new String[0];
    private static long[] sBytes = new long[0];

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
    public static int percent() { return SESSION.percent(); }
    public static long speed() { return SESSION.speed(); }
    public static int overallPercent() { return SESSION.overallPercent(); }
    public static String[] labels() { return sLabels; }
    public static long[] bytes() { return sBytes; }
    public static void setListener(Listener l) { SESSION.setListener(l == null ? null : l::onUpdate); }

    public static void start(Context ctx, String[] files, String[] labels, long[] bytes) {
        Intent i = new Intent(ctx, ZimDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_FILES, files).putExtra(EXTRA_LABELS, labels).putExtra(EXTRA_BYTES, bytes);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void pause(Context ctx) {
        if (!SESSION.isRunning()) return;
        ContextCompat.startForegroundService(ctx, new Intent(ctx, ZimDownloadService.class).setAction(ACTION_PAUSE));
    }

    public static void resume(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, ZimDownloadService.class).setAction(ACTION_RESUME));
    }

    /** ADFA-4893: re-queue ALL failed items and resume — the status-screen Retry (Pause morphs into it). */
    public static void retryFailed(Context ctx) {
        if (SESSION.requeueFailed() && !SESSION.isRunning()) {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, ZimDownloadService.class).setAction(ACTION_RETRY));
        }
    }

    public static void finishSession() { SESSION.purge(); sFiles = new String[0]; sLabels = new String[0]; sBytes = new long[0]; }

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
            startForeground(NOTIFICATION_ID, buildNotification(label(SESSION.index())));
            SESSION.resumeQueue();
        } else { // ACTION_START: fresh session from the extras
            String[] f = intent.getStringArrayExtra(EXTRA_FILES);
            if (f == null || f.length == 0) { stopSelf(); return START_NOT_STICKY; }
            sFiles = f;
            sLabels = intent.getStringArrayExtra(EXTRA_LABELS);
            sBytes = intent.getLongArrayExtra(EXTRA_BYTES);
            if (sLabels == null) sLabels = sFiles;
            if (sBytes == null) sBytes = new long[sFiles.length];
            startForeground(NOTIFICATION_ID, buildNotification(sLabels.length > 0 ? sLabels[0] : ""));
            SESSION.begin(sFiles.length);
        }
        return START_NOT_STICKY;
    }

    // ---- ContentDownloadSession.Host (ZIM specifics) ------------------------------------------
    @Override public JSONObject buildBody(int i) {
        try { return new JSONObject().put("ids", new JSONArray().put(sFiles[i])); }
        catch (Exception e) { return new JSONObject(); }
    }

    @Override public String label(int i) { return i >= 0 && i < sLabels.length ? sLabels[i] : ""; }

    /** Byte-weighted overall percent: done/failed count in full, the active item adds its live percent. */
    @Override public int overallPercent() {
        int[] status = SESSION.status();
        int idx = SESSION.index();
        int p = SESSION.percent();
        long total = 0, done = 0;
        for (int i = 0; i < sBytes.length && i < status.length; i++) {
            total += sBytes[i];
            if (status[i] == DONE || status[i] == FAILED) done += sBytes[i];
            else if (i == idx && (status[i] == ACTIVE || status[i] == INDEXING) && p >= 0) done += sBytes[i] * p / 100;
        }
        return total > 0 ? (int) Math.min(100, done * 100 / total) : (SESSION.isComplete() ? 100 : 0);
    }

    @Override public void notify(String label) {
        if (!SESSION.isRunning()) return;
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, buildNotification(label));
    }

    @Override public void stop() { main.post(() -> { stopForeground(true); stopSelf(); }); }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_zim_dl_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String currentLabel) {
        Intent open = new Intent(this, SetupProgressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent cancel = new Intent(this, ZimDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_zim_dl_notif_title))
                .setContentText(currentLabel)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_zim_notif_cancel), cancelIntent)
                .build();
    }
}
