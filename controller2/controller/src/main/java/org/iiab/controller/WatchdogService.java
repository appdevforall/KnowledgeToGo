/*
 * ============================================================================
 * Name        : WatchdogService.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : State-of-the-art Foreground Service to protect heavy I/O
 * and Network operations (Server, Sync, Tar extraction) from
 * being killed by Android's battery optimizer or Doze mode.
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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class WatchdogService extends Service {
    private static final String TAG = "IIAB-Watchdog";
    private static final String CHANNEL_ID = "watchdog_channel";
    private static final int NOTIFICATION_ID = 2;

    public static final String ACTION_START = "org.iiab.controller.WATCHDOG_START";
    public static final String ACTION_STOP = "org.iiab.controller.WATCHDOG_STOP";
    public static final String ACTION_STATE_STARTED = "org.iiab.controller.WATCHDOG_STARTED";
    public static final String ACTION_STATE_STOPPED = "org.iiab.controller.WATCHDOG_STOPPED";

    // Hardware Locks
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START.equals(action)) {
                startWatchdog();
            } else if (ACTION_STOP.equals(action)) {
                stopSelf(); // Triggers onDestroy() cleanly
            }
        }
        // START_STICKY tells Android to restart this service if it ever gets killed under extreme memory pressure
        return START_STICKY;
    }

    private void startWatchdog() {
        // 1. Start Foreground to prevent OOM (Out of Memory) kills
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);

        // 2. Acquire CPU WakeLock to prevent sleep during heavy operations (e.g., Tar extraction, Rsync)
        acquireHardwareLocks();

        // 3. Notify the UI (MainActivity) that the engine is protected and running
        IIABWatchdog.logSessionStart(this);
        Intent startIntent = new Intent(ACTION_STATE_STARTED);
        startIntent.setPackage(getPackageName());
        sendBroadcast(startIntent);

        Log.i(TAG, "Watchdog Service Started: CPU and Wi-Fi are now protected.");
    }

    private void acquireHardwareLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IIAB:TransferWakeLock");
            // temporal: No timeout used because a 90GB transfer might take hours. Must be released manually.
            wakeLock.acquire();
        }

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            // temporal: WIFI_MODE_FULL_HIGH_PERF prevents Android from throttling Wi-Fi speed to save battery
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IIAB:TransferWifiLock");
            wifiLock.acquire();
        }
    }

    private void releaseHardwareLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    @Override
    public void onDestroy() {
        // 1. Notify the UI that protection is gone
        Intent stopIntent = new Intent(ACTION_STATE_STOPPED);
        stopIntent.setPackage(getPackageName());
        sendBroadcast(stopIntent);

        // 2. Release Hardware Locks so the phone can sleep again
        releaseHardwareLocks();

        // 3. Cleanup
        IIABWatchdog.logSessionStop(this);
        stopForeground(true);

        Log.i(TAG, "Watchdog Service Stopped: Hardware locks released.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not using bound service paradigm
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.watchdog_channel_name),
                    NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound/vibration every time it starts
            );
            channel.setDescription(getString(R.string.watchdog_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.watchdog_notif_title))
                .setContentText("Protecting critical background operations...")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true) // Cannot be swiped away by the user
                .build();
    }
}