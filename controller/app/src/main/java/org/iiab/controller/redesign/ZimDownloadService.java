/*
 * ============================================================================
 * Name        : ZimDownloadService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4849. Foreground service that downloads the ZIM selection cart through the
 *               in-server REST job engine (RestContentClient / ADFA-4838/4840): one job per ZIM,
 *               SEQUENTIALLY, CONTINUING past a failed item. The heavy work runs on the live
 *               Debian/proot server (durable there); the device only POSTs + polls, so this
 *               service is light. It holds the session state; the Preparing screen observes it,
 *               re-attaches, RETRIES failed items (re-queued), and FINISHES (clears the session).
 *               Resume/checksum of a partial download is the SERVER's job; a retry just re-POSTs.
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
    public static final String ACTION_RETRY = "org.iiab.controller.ZIM_DOWNLOAD_RETRY";
    public static final String ACTION_CANCEL = "org.iiab.controller.ZIM_DOWNLOAD_CANCEL";
    public static final String EXTRA_FILES = "files";
    public static final String EXTRA_LABELS = "labels";
    public static final String EXTRA_BYTES = "bytes";

    public static final int PENDING = 0, ACTIVE = 1, INDEXING = 2, DONE = 3, FAILED = 4;

    public interface Listener { void onUpdate(); }

    // ---- shared state for the single active session (observed by the Preparing screen) ----
    private static volatile boolean sRunning = false;   // a job is actively being processed
    private static String[] sFiles = new String[0];
    private static String[] sLabels = new String[0];
    private static long[] sBytes = new long[0];
    private static int[] sStatus = new int[0];
    private static int sIndex = 0;
    private static int sPercent = 0;
    private static long sSpeed = 0;
    private static Listener sListener;

    public static boolean isRunning() { return sRunning; }
    public static boolean hasSession() { return sFiles.length > 0; }
    /** All items are terminal (done/failed) and nothing is in flight. */
    public static boolean isComplete() {
        if (sFiles.length == 0 || sRunning) return false;
        for (int st : sStatus) if (st == PENDING || st == ACTIVE || st == INDEXING) return false;
        return true;
    }
    public static String[] labels() { return sLabels; }
    public static long[] bytes() { return sBytes; }
    public static int[] status() { return sStatus; }
    public static int index() { return sIndex; }
    public static int percent() { return sPercent; }
    public static long speed() { return sSpeed; }
    public static void setListener(Listener l) { sListener = l; }

    public static void start(Context ctx, String[] files, String[] labels, long[] bytes) {
        Intent i = new Intent(ctx, ZimDownloadService.class).setAction(ACTION_START)
                .putExtra(EXTRA_FILES, files).putExtra(EXTRA_LABELS, labels).putExtra(EXTRA_BYTES, bytes);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** Re-queue a failed item; resume processing if the session had already stopped. */
    public static void retry(Context ctx, int i) {
        if (i < 0 || i >= sStatus.length) return;
        sStatus[i] = PENDING;
        if (!sRunning) {
            ContextCompat.startForegroundService(ctx, new Intent(ctx, ZimDownloadService.class).setAction(ACTION_RETRY));
        }
    }

    /** Clear the session so a new selection can start fresh. */
    public static void finishSession() {
        sFiles = new String[0]; sLabels = new String[0]; sBytes = new long[0]; sStatus = new int[0];
        sIndex = 0; sPercent = 0; sSpeed = 0; sRunning = false;
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
            sRunning = false;
            publish();
            main.post(() -> { stopForeground(true); stopSelf(); });
            return START_NOT_STICKY;
        }

        if (sRunning) return START_NOT_STICKY;

        if (ACTION_RETRY.equals(action)) {
            if (!hasSession()) { stopSelf(); return START_NOT_STICKY; }
        } else { // ACTION_START: fresh session from the extras
            String[] f = intent.getStringArrayExtra(EXTRA_FILES);
            if (f == null || f.length == 0) { stopSelf(); return START_NOT_STICKY; }
            sFiles = f;
            sLabels = intent.getStringArrayExtra(EXTRA_LABELS);
            sBytes = intent.getLongArrayExtra(EXTRA_BYTES);
            if (sLabels == null) sLabels = sFiles;
            if (sBytes == null) sBytes = new long[sFiles.length];
            sStatus = new int[sFiles.length];
            sIndex = 0; sPercent = 0; sSpeed = 0;
        }

        sRunning = true;
        startForeground(NOTIFICATION_ID, buildNotification(currentLabel()));
        processNext();
        return START_NOT_STICKY;
    }

    private static int firstPending() {
        for (int i = 0; i < sStatus.length; i++) if (sStatus[i] == PENDING) return i;
        return -1;
    }

    private String currentLabel() {
        return sIndex >= 0 && sIndex < sLabels.length ? sLabels[sIndex] : "";
    }

    private void processNext() {
        int i = firstPending();
        if (i < 0) { sessionComplete(); return; }
        sIndex = i; sPercent = 0; sStatus[i] = ACTIVE;
        publish();
        updateNotification(sLabels[i]);
        client = new RestContentClient();
        client.addZim(sFiles[i], new RestContentClient.Listener() {
            @Override public void onProgress(int percent, String speed) {
                sPercent = percent; sSpeed = parseRate(speed);
                if (sStatus[i] != INDEXING) sStatus[i] = ACTIVE;
                publish(); updateNotification(sLabels[i]);
            }
            @Override public void onIndexing() { sStatus[i] = INDEXING; publish(); }
            @Override public void onLog(String line) { /* logcat only */ }
            @Override public void onDone() { sStatus[i] = DONE; publish(); processNext(); }
            @Override public void onError(String message) { sStatus[i] = FAILED; publish(); processNext(); }
        });
    }

    private void sessionComplete() {
        sRunning = false;
        publish();
        main.post(() -> { stopForeground(true); stopSelf(); });
    }

    private void publish() { main.post(() -> { if (sListener != null) sListener.onUpdate(); }); }

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
        if (!sRunning) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(currentLabel));
    }
}
