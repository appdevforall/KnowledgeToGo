/*
 * ============================================================================
 * Name        : ZimDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. Foreground service that downloads the ZIM selection cart through the
 *               in-server REST job engine (RestContentClient / ADFA-4838/4840): one job per ZIM,
 *               SEQUENTIALLY, CONTINUING past a failed item. The heavy work runs on the live
 *               Debian/proot server (the job is durable there); the device only POSTs + polls, so
 *               this service is light — a notification + the sequential driver + a small shared
 *               state the Preparing screen observes (and re-attaches to via setListener). Being a
 *               service, "Run in background" keeps it going independent of the fragment lifecycle.
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

import org.iiab.controller.MainActivity;
import org.iiab.controller.R;
import org.iiab.controller.content.RestContentClient;

public final class ZimDownloadService extends Service {

    private static final String CHANNEL_ID = "zim_download_channel";
    private static final int NOTIFICATION_ID = 5;

    public static final String ACTION_START = "org.iiab.controller.ZIM_DOWNLOAD_START";
    public static final String ACTION_CANCEL = "org.iiab.controller.ZIM_DOWNLOAD_CANCEL";
    public static final String EXTRA_FILES = "files";
    public static final String EXTRA_LABELS = "labels";
    public static final String EXTRA_BYTES = "bytes";

    // Per-item status.
    public static final int PENDING = 0, ACTIVE = 1, INDEXING = 2, DONE = 3, FAILED = 4;

    public interface Listener { void onUpdate(); }

    // ---- shared state for the single active session (observed by the Preparing screen) ----
    private static volatile boolean sRunning = false;
    private static volatile boolean sAllDone = false;
    private static String[] sFiles = new String[0];
    private static String[] sLabels = new String[0];
    private static long[] sBytes = new long[0];
    private static int[] sStatus = new int[0];
    private static int sIndex = 0;
    private static int sPercent = 0;
    private static long sSpeed = 0;
    private static Listener sListener;

    public static boolean isRunning() { return sRunning; }
    public static boolean isAllDone() { return sAllDone; }
    public static String[] labels() { return sLabels; }
    public static long[] bytes() { return sBytes; }
    public static int[] status() { return sStatus; }
    public static int index() { return sIndex; }
    public static int percent() { return sPercent; }
    public static long speed() { return sSpeed; }
    public static void setListener(Listener l) { sListener = l; }

    public static void start(Context ctx, String[] files, String[] labels, long[] bytes) {
        Intent i = new Intent(ctx, ZimDownloadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FILES, files)
                .putExtra(EXTRA_LABELS, labels)
                .putExtra(EXTRA_BYTES, bytes);
        ContextCompat.startForegroundService(ctx, i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private RestContentClient client;

    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_CANCEL.equals(action)) {
            if (client != null) client.cancel();
            finishAll();
            return START_NOT_STICKY;
        }
        if (sRunning) return START_NOT_STICKY; // one session at a time; ignore re-starts

        sFiles = intent.getStringArrayExtra(EXTRA_FILES);
        sLabels = intent.getStringArrayExtra(EXTRA_LABELS);
        sBytes = intent.getLongArrayExtra(EXTRA_BYTES);
        if (sFiles == null || sFiles.length == 0) { stopSelf(); return START_NOT_STICKY; }
        if (sLabels == null) sLabels = sFiles;
        if (sBytes == null) sBytes = new long[sFiles.length];
        sStatus = new int[sFiles.length];
        sIndex = 0; sPercent = 0; sSpeed = 0; sAllDone = false; sRunning = true;

        startForeground(NOTIFICATION_ID, buildNotification(sLabels.length > 0 ? sLabels[0] : ""));
        process(0);
        return START_NOT_STICKY;
    }

    private void process(int i) {
        if (i >= sFiles.length) { finishAll(); return; }
        sIndex = i; sPercent = 0; sStatus[i] = ACTIVE;
        publish();
        updateNotification(sLabels[i]);
        client = new RestContentClient();
        client.addZim(sFiles[i], new RestContentClient.Listener() {
            @Override public void onProgress(int percent, String speed) {
                sPercent = percent;
                sSpeed = parseRate(speed);
                if (sStatus[i] != INDEXING) sStatus[i] = ACTIVE;
                publish();
                updateNotification(sLabels[i]);
            }
            @Override public void onIndexing() { sStatus[i] = INDEXING; publish(); }
            @Override public void onLog(String line) { /* logcat only */ }
            @Override public void onDone() { sStatus[i] = DONE; publish(); process(i + 1); }
            @Override public void onError(String message) { sStatus[i] = FAILED; publish(); process(i + 1); }
        });
    }

    private void finishAll() {
        sAllDone = true;
        sRunning = false;
        publish();
        main.post(() -> {
            stopForeground(true);
            stopSelf();
        });
    }

    private void publish() {
        main.post(() -> { if (sListener != null) sListener.onUpdate(); });
    }

    /** "3.4 MB" (as RestContentClient formats it) -> bytes/sec, best-effort, for the state. */
    private static long parseRate(String s) {
        if (s == null) return 0;
        try {
            String t = s.trim();
            int sp = t.indexOf(' ');
            if (sp < 0) return 0;
            double v = Double.parseDouble(t.substring(0, sp));
            String u = t.substring(sp + 1);
            double m = "GB".equals(u) ? 1024d * 1024 * 1024 : "MB".equals(u) ? 1024d * 1024
                    : "KB".equals(u) ? 1024d : 1d;
            return Math.round(v * m);
        } catch (Exception e) { return 0; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_zim_dl_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String currentLabel) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
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

    private void updateNotification(String currentLabel) {
        if (sAllDone) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(currentLabel));
    }
}
