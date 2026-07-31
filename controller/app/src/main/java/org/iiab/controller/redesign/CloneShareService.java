/*
 * ============================================================================
 * Name        : CloneShareService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4960. Clone-specific foreground keep-alive. Same role as WatchdogService (holds
 *               the CPU + Wi-Fi locks and a foreground notification so the app process — and therefore
 *               the native rsync daemon/pull child — survives backgrounding and swipe-away), but with a
 *               CLONE notification whose content intent returns to the Clone tab (LibraryActivity +
 *               EXTRA_TAB nav_clone), unlike WatchdogService's generic one that points at MainActivity.
 *               Distinct channel + notification id so it never collides with WatchdogService. Clone uses
 *               this instead of WatchdogService, so it owns its own locks (no double-acquire).
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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.iiab.controller.R;

public final class CloneShareService extends Service {

    private static final String TAG = "IIAB-CloneShare";
    private static final String CHANNEL_ID = "clone_channel";
    private static final int NOTIFICATION_ID = 8;

    public static final String ACTION_START = "org.iiab.controller.CLONE_SHARE_START";
    public static final String ACTION_STOP = "org.iiab.controller.CLONE_SHARE_STOP";

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // ADFA-4960: a START_STICKY restart delivers a null intent. After a true process kill the clone
        // is gone (the session + native daemon died with the process), so don't resurrect a stale
        // "transferring" notification / re-hold locks for a transfer that no longer exists.
        if (intent == null
                && !CloneSendSession.isActive()
                && !org.iiab.controller.sync.presentation.SyncProgressRepository.get().isActive()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        acquireHardwareLocks();
        Log.i(TAG, "clone share protection ON");
        // START_STICKY (like WatchdogService, no stopWithTask/onTaskRemoved): keep the process at
        // foreground priority so the native rsync child survives backgrounding and swipe-away.
        return START_STICKY;
    }

    private void acquireHardwareLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IIAB:CloneWakeLock");
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wifiLock == null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IIAB:CloneWifiLock");
            wifiLock.acquire();
        }
    }

    private void releaseHardwareLocks() {
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); wakeLock = null; }
        if (wifiLock != null && wifiLock.isHeld()) { wifiLock.release(); wifiLock = null; }
    }

    @Override
    public void onDestroy() {
        releaseHardwareLocks();
        stopForeground(true);
        Log.i(TAG, "clone share protection OFF");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_clone_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.k2go_clone_channel_desc));
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, LibraryActivity.class)
                .putExtra(LibraryActivity.EXTRA_TAB, R.id.nav_clone)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_clone_notif_title))
                .setContentText(getString(R.string.k2go_clone_notif_text))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
