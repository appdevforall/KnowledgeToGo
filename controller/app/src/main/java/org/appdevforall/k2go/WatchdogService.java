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

package org.appdevforall.k2go;

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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WatchdogService extends Service {
    private static final String TAG = "IIAB-Watchdog";
    private static final String CHANNEL_ID = "watchdog_channel";
    private static final int NOTIFICATION_ID = 2;

    public static final String ACTION_START = "org.iiab.controller.WATCHDOG_START";
    public static final String ACTION_STOP = "org.iiab.controller.WATCHDOG_STOP";
    public static final String ACTION_STATE_STARTED = "org.iiab.controller.WATCHDOG_STARTED";
    public static final String ACTION_STATE_STOPPED = "org.iiab.controller.WATCHDOG_STOPPED";

    // ADFA-5343 (Phase 4b): process-scoped "is the watchdog protecting right now", so the reconciler —
    // the one promoter — can edge-detect (start only when not running, stop only when running) without a
    // reconciler-side flag. Resets to false if the process is recreated (START_STICKY re-delivers a start).
    private static volatile boolean RUNNING = false;
    public static boolean isRunning() { return RUNNING; }

    // Hardware Locks
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // K2GO-386 (Layer 3): a single background poller checks free space while the box is up. On a critical
    // reading DiskGuard reaps and reclaims, then by default keeps the system alive. Started once per
    // protected session, stopped on destroy.
    private ScheduledExecutorService diskGuardPoller;
    private static final long DISK_GUARD_INTERVAL_S = 25;
    // The low-disk check runs every tick (a local StatFs read). The firehose signal is an HTTP GET to
    // dash-node, and dash-node only advances it on its 10-min guard tick, so read it every Nth tick
    // (~150 s) instead of every 25 s. Touched only by the single poller thread.
    private static final int FIREHOSE_POLL_EVERY_N_TICKS = 6;
    private int diskGuardTick = 0;

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
        // 1. Start Foreground to prevent OOM (Out of Memory) kills. Called on EVERY start (idempotent) so a
        // re-delivered / repeat ACTION_START still satisfies the startForeground() contract.
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);

        // ADFA-5343 (Phase 4b): the reconciler may re-send ACTION_START; guard so a repeat does NOT
        // re-acquire the hardware locks (that would leak the held ones). Acquire once per running session.
        if (RUNNING) return;
        RUNNING = true;

        // 2. Acquire CPU WakeLock to prevent sleep during heavy operations (e.g., Tar extraction, Rsync)
        acquireHardwareLocks();

        // K2GO-386 (barrier 2): guard free space for the life of this protected session.
        startDiskGuard();

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

    // K2GO-386 (Layer 3): the free-space guard. One background poller ticks every DISK_GUARD_INTERVAL_S.
    // Two triggers. (1) check() EVERY tick (a local read): on a CRITICAL free-space reading, confirm,
    // reap, reclaim, and by default let the box restart. (2) checkFirehoseSignal() every Nth tick (an
    // HTTP read): on a fresh recurring firehose that is still growing, reap the off-proot orphan the box
    // cannot stop -- even before the disk goes low (ADR-386 §6). The in-box layers cannot stop an
    // off-proot orphan. Started once per session.
    private void startDiskGuard() {
        if (diskGuardPoller != null) return;
        diskGuardPoller = Executors.newSingleThreadScheduledExecutor();
        diskGuardPoller.scheduleWithFixedDelay(() -> {
            try {
                org.appdevforall.k2go.diskguard.DiskGuard.check(getApplicationContext());
                if (diskGuardTick++ % FIREHOSE_POLL_EVERY_N_TICKS == 0) {
                    org.appdevforall.k2go.diskguard.DiskGuard.checkFirehoseSignal(getApplicationContext());
                }
            } catch (Throwable t) {
                Log.w(TAG, "K2GO-386: disk-guard tick failed", t);
            }
        }, DISK_GUARD_INTERVAL_S, DISK_GUARD_INTERVAL_S, TimeUnit.SECONDS);
    }

    private void stopDiskGuard() {
        if (diskGuardPoller != null) {
            diskGuardPoller.shutdownNow();
            diskGuardPoller = null;
        }
    }

    @Override
    public void onDestroy() {
        RUNNING = false;   // ADFA-5343 (Phase 4b): protection is ending — clear the promoter's state signal
        // 1. Notify the UI that protection is gone
        Intent stopIntent = new Intent(ACTION_STATE_STOPPED);
        stopIntent.setPackage(getPackageName());
        sendBroadcast(stopIntent);

        // K2GO-386 (barrier 2): stop the free-space guard — this protected session (box up) is ending.
        stopDiskGuard();

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
        Intent notificationIntent = new Intent(this, org.appdevforall.k2go.redesign.LibraryActivity.class)   // ADFA-4987: redesign, not legacy UI
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
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