/*
 * ============================================================================
 * Name        : ContentDownloadServiceBase.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-449. Shared foreground-service shell for content downloads on the durable job
 *               engine. Holds the instance behavior every content service copied: the onStartCommand
 *               dispatch of pause/resume/cancel/retry, the notification + channel, and the
 *               ContentDownloadSession.Host plumbing. Per-module bits come from abstract hooks (the
 *               module's session, its action strings, the notification title/fallback text, the START
 *               item-building, and optional cancel cleanup). The notification shows the current item
 *               label and, when the module provides a returnIntent(), routes tap-to-return through
 *               OpReturnNavigator, per the long-running operation contract. The per-type
 *               ContentDownloadSession singleton stays owned by the subclass, so this adds no new state.
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
    /** Fallback notification text, shown before the first item label arrives. */
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
            if (a.equals(actionCancel())) {
                // Run the module's cancel cleanup, but never let it block the purge: cancel must always
                // tear down the session and the foreground service, even if a hook throws.
                try { onCancel(); } catch (RuntimeException e) { /* best-effort */ }
                session().cancelAndPurge();
                return START_NOT_STICKY;
            }
            if (a.equals(actionPause())) { session().pauseActive(); return START_NOT_STICKY; }
            if (a.equals(actionResume())) { session().resumeActive(); return START_NOT_STICKY; }
        }
        if (session().isRunning()) return START_NOT_STICKY;   // duplicate start/retry: ignore

        if (a != null && a.equals(actionRetry())) {
            if (!session().hasSession()) { stopSelf(); return START_NOT_STICKY; }
            startForeground(notificationId(), buildNotification(currentLabel()));
            session().resumeQueue();
        } else { // START (any other action): a fresh session built by the subclass
            startForeground(notificationId(), buildNotification(null));
            beginFromIntent(intent);
        }
        return START_NOT_STICKY;
    }

    // ---- ContentDownloadSession.Host (generic) -------------------------------------------------
    @Override public void notify(String label) {
        if (!session().isRunning()) return;
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(notificationId(), buildNotification(label));
    }

    @Override public void stop() { main.post(() -> { stopForeground(true); stopSelf(); }); }

    @Override public void onItemDone(String key) { /* default: no wishlist; a subclass may override */ }

    /** Extra work when the user cancels, before the session is purged. Default: nothing. */
    protected void onCancel() { }

    /** The screen a notification tap returns to, or null for no tap target. Default: no tap target. */
    @Nullable protected Intent returnIntent() { return null; }

    /** The current item label, or null when no session is active (the notification content text). */
    @Nullable private String currentLabel() {
        ContentDownloadSession s = session();
        return s.hasSession() ? s.label(s.index()) : null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId(), channelName(), NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    protected Notification buildNotification(@Nullable String contentText) {
        Intent cancel = new Intent(this, getClass()).setAction(actionCancel());
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = (contentText != null && !contentText.isEmpty()) ? contentText : notifText();
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channelId())
                .setContentTitle(notifTitle())
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_dash_cancel), cancelIntent);
        // Operation contract: tap-to-return routes through the single owner (OpReturnNavigator). The
        // destination is per module (null = no tap target); the onboarding streams override returnIntent().
        Intent ret = returnIntent();
        if (ret != null) b.setContentIntent(OpReturnNavigator.notify(this, ret));
        return b.build();
    }
}
