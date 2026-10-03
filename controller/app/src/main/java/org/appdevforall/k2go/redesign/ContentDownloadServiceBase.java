/*
 * ============================================================================
 * Name        : ContentDownloadServiceBase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-449. Shared foreground-service shell for content downloads on the durable job
 *               engine. Holds the instance behavior every content service copied: the onStartCommand
 *               dispatch of pause/resume/cancel/retry, the notification + channel, and the
 *               ContentDownloadSession.Host plumbing. Per-module bits come from abstract hooks (the
 *               module's session, its action strings, the START item-building, and the notification
 *               text). The per-type ContentDownloadSession singleton stays owned by the subclass, so
 *               this adds no new state. CodeAssetsDownloadService adopts it first; ZimDownloadService /
 *               BooksDownloadService / maps migrate onto it in later, separately reviewed slices.
 * ============================================================================
 */
package org.appdevforall.k2go.redesign;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.appdevforall.k2go.R;

public abstract class ContentDownloadServiceBase extends Service implements ContentDownloadSession.Host {

    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- per-module hooks ----------------------------------------------------------------------
    /** The module's session singleton (the subclass owns the static instance). */
    protected abstract ContentDownloadSession session();
    protected abstract int notificationId();
    protected abstract String channelId();
    protected abstract String channelName();
    protected abstract String notifTitle();
    protected abstract String notifText();
    protected abstract String actionPause();
    protected abstract String actionResume();
    protected abstract String actionCancel();
    protected abstract String actionRetry();
    /** Build and begin a fresh session from the start intent (the subclass calls session().begin(...)). */
    protected abstract void beginFromIntent(Intent intent);

    @Override public void onCreate() { super.onCreate(); createChannel(); session().attach(this); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        session().attach(this);
        final String a = intent != null ? intent.getAction() : null;
        if (a != null) {
            if (a.equals(actionCancel())) { session().cancelAndPurge(); return START_NOT_STICKY; }
            if (a.equals(actionPause())) { session().pauseActive(); return START_NOT_STICKY; }
            if (a.equals(actionResume())) { session().resumeActive(); return START_NOT_STICKY; }
        }
        if (session().isRunning()) return START_NOT_STICKY;   // duplicate start/retry: ignore

        if (a != null && a.equals(actionRetry())) {
            if (!session().hasSession()) { stopSelf(); return START_NOT_STICKY; }
            startForeground(notificationId(), buildNotification());
            session().resumeQueue();
        } else { // START (any other action): a fresh session built by the subclass
            startForeground(notificationId(), buildNotification());
            beginFromIntent(intent);
        }
        return START_NOT_STICKY;
    }

    // ---- ContentDownloadSession.Host (generic) -------------------------------------------------
    @Override public void notify(String label) {
        if (!session().isRunning()) return;
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(notificationId(), buildNotification());
    }

    @Override public void stop() { main.post(() -> { stopForeground(true); stopSelf(); }); }

    @Override public void onItemDone(String key) { /* default: no wishlist; a subclass may override */ }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId(), channelName(), NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    protected Notification buildNotification() {
        Intent cancel = new Intent(this, getClass()).setAction(actionCancel());
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, channelId())
                .setContentTitle(notifTitle())
                .setContentText(notifText())
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_dash_cancel), cancelIntent)
                .build();
    }
}
