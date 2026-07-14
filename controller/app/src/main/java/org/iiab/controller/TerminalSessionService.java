/*
 * ============================================================================
 * Name        : TerminalSessionService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Foreground keep-alive service for the ninja terminal
 *               (ADFA-4696, phase 2). While at least one terminal session is
 *               running it holds the process at foreground priority so a long
 *               command started in the terminal (e.g. an `iiab` install, a log
 *               tail, or a build inside PRoot) is not killed when the app is
 *               backgrounded, and is still there when the user returns.
 *
 *               Started by TerminalController when a session is created and
 *               stopped when the last session ends. START_NOT_STICKY: if the
 *               process is killed under memory pressure we do not resurrect an
 *               empty terminal. Mirrors the WatchdogService/InstallService
 *               foreground pattern (specialUse). The session objects themselves
 *               are moved out of the Activity in a follow-up PR under this
 *               ticket; this service only keeps the process alive.
 * ============================================================================
 */
package org.iiab.controller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class TerminalSessionService extends Service {

    private static final String CHANNEL_ID = "terminal_channel";
    private static final int NOTIFICATION_ID = 4;

    public static final String ACTION_START = "org.iiab.controller.TERMINAL_START";
    public static final String ACTION_STOP = "org.iiab.controller.TERMINAL_STOP";

    /** Start the keep-alive service. Call while the app is in the foreground. */
    public static void start(Context ctx) {
        Intent i = new Intent(ctx, TerminalSessionService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** Stop the keep-alive service. Call when the last terminal session has ended. */
    public static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, TerminalSessionService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // keep-alive only; not a bound service
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "K2Go Terminal",
                    NotificationManager.IMPORTANCE_LOW); // LOW: no sound/vibration
            channel.setDescription("Keeps terminal sessions running in the background.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("K2Go terminal active")
                .setContentText("A terminal session is running in the background.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // cannot be swiped away while a session is alive
                .build();
    }
}
