/*
 * ============================================================================
 * Name        : DeepOpService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4957. Foreground service that OWNS a deep-environment operation (backup / restore)
 *               off the UI, so it survives the app being backgrounded or the op screen being recreated.
 *               Mirrors the resilience install already has (InstallService): hardware locks + a
 *               swipe-proof progress notification (re-asserted via startForeground) whose content
 *               intent brings the user back, and it brackets the EnvironmentLock (+ InstallGuard for a
 *               destructive restore). Progress is published to DeepOpProgressRepository, the app-scoped
 *               single source of truth the op screen observes. Clone adoption + the fragment migration
 *               (return-to-op routing) land next; this commit is the engine.
 *
 *               Job shapes:
 *                 BACKUP  (EXTRA_URI  = SAF dest)  : stop services -> stream tar|gzip to the file -> boot.
 *                 RESTORE (EXTRA_PATH = temp file) : stop services -> extract over the rootfs -> boot.
 *               For restore the caller (UI) has already copied + validated the archive and shown the
 *               destructive confirm; the service owns the kill-sensitive extract and sets InstallGuard.
 * ============================================================================
 */
package org.iiab.controller.deepop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.iiab.controller.InstallGuard;
import org.iiab.controller.PRootEngine;
import org.iiab.controller.R;
import org.iiab.controller.TarExtractor;
import org.iiab.controller.backup.domain.BackupEngine;
import org.iiab.controller.env.EnvironmentControl;
import org.iiab.controller.env.EnvironmentLock;
import org.iiab.controller.redesign.LibraryActivity;
import org.iiab.controller.util.AppExecutors;

import java.io.File;
import java.io.OutputStream;

public final class DeepOpService extends Service {

    private static final String TAG = "IIAB-DeepOpService";
    private static final String CHANNEL_ID = "deepop_channel";
    private static final int NOTIFICATION_ID = 7;

    public static final String ACTION_BACKUP = "org.iiab.controller.DEEPOP_BACKUP";
    public static final String ACTION_RESTORE = "org.iiab.controller.DEEPOP_RESTORE";
    public static final String ACTION_CANCEL = "org.iiab.controller.DEEPOP_CANCEL";
    public static final String EXTRA_URI = "uri";     // backup: SAF dest content:// uri
    public static final String EXTRA_PATH = "path";   // restore: already-validated temp file path

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean started = false;
    private volatile boolean finished = false;
    private EnvironmentLock.Owner owner;
    private String stepText = "";
    // Held so the booted environment's `tail -f` proot isn't GC'd after the service tears down; the
    // process itself is app-scoped (it lives on until the app process dies), the activity's
    // ServerController then reflects it on the next poll.
    @SuppressWarnings("unused")
    private static PRootEngine sBootEngine;

    /** Start a backup: stream a gzip'd tar of the rootfs to the SAF destination. */
    public static void startBackup(Context ctx, Uri dest) {
        Intent i = new Intent(ctx, DeepOpService.class).setAction(ACTION_BACKUP).putExtra(EXTRA_URI, dest.toString());
        startFg(ctx, i);
    }

    /** Start a restore: extract the already-validated temp archive over the rootfs (destructive). */
    public static void startRestore(Context ctx, String validatedTempPath) {
        Intent i = new Intent(ctx, DeepOpService.class).setAction(ACTION_RESTORE).putExtra(EXTRA_PATH, validatedTempPath);
        startFg(ctx, i);
    }

    private static void startFg(Context ctx, Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        final String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) { teardown(); return START_NOT_STICKY; }
        if (started) return START_NOT_STICKY;   // one op per service instance
        started = true;

        owner = ACTION_RESTORE.equals(action) ? EnvironmentLock.Owner.RESTORE : EnvironmentLock.Owner.BACKUP;
        stepText = getString(R.string.k2go_br_status_stopping);
        startForeground(NOTIFICATION_ID, buildNotification(stepText));
        acquireHardwareLocks();

        EnvironmentLock.acquire(this, owner);
        if (owner == EnvironmentLock.Owner.RESTORE) InstallGuard.begin(this);   // damage recovery for a killed extract
        post(stepText, -1);

        if (owner == EnvironmentLock.Owner.RESTORE) {
            final String path = intent.getStringExtra(EXTRA_PATH);
            EnvironmentControl.stop(this, this::log, () -> runRestore(path));
        } else {
            final String uriStr = intent.getStringExtra(EXTRA_URI);
            EnvironmentControl.stop(this, this::log, () -> runBackup(uriStr));
        }
        return START_NOT_STICKY;
    }

    // ---- BACKUP ----
    private void runBackup(final String uriStr) {
        setStep(getString(R.string.k2go_br_status_backing), -1);
        AppExecutors.get().io().execute(() -> {
            boolean ok;
            try (OutputStream os = getContentResolver().openOutputStream(Uri.parse(uriStr))) {
                ok = os != null && BackupEngine.streamBackup(this, os);
            } catch (Exception e) {
                Log.e(TAG, "backup failed", e);
                ok = false;
            }
            final boolean success = ok;
            main.post(() -> finishJob(success,
                    getString(R.string.k2go_br_backup_done), getString(R.string.k2go_br_backup_failed)));
        });
    }

    // ---- RESTORE ----
    private void runRestore(final String path) {
        setStep(getString(R.string.k2go_br_status_restoring), -1);
        final File destParent = new File(getFilesDir(), "rootfs");
        new TarExtractor().startExtraction(this, path, destParent.getAbsolutePath(), true,
                new TarExtractor.ExtractionListener() {
                    @Override public void onComplete(String destDir) { main.post(() -> endRestore(path, true)); }
                    @Override public void onError(String error) { main.post(() -> endRestore(path, false)); }
                    @Override public void onProgress(String line) { }
                });
    }

    private void endRestore(String tempPath, boolean ok) {
        File temp = new File(tempPath);
        if (temp.exists()) temp.delete();
        finishJob(ok, getString(R.string.k2go_br_restore_done), getString(R.string.k2go_br_restore_failed));
    }

    // ---- shared terminal ----
    private void finishJob(boolean ok, String okMsg, String failMsg) {
        // Boot the (possibly replaced) environment back; the returned engine is app-scoped.
        sBootEngine = EnvironmentControl.start(this, this::log);
        if (owner == EnvironmentLock.Owner.RESTORE) InstallGuard.end(this);
        EnvironmentLock.release(this);
        if (ok) DeepOpProgressRepository.get().postSuccess(owner, okMsg);
        else DeepOpProgressRepository.get().postFailed(owner, failMsg);
        teardown();
    }

    private void teardown() {
        finished = true;
        releaseHardwareLocks();
        stopForeground(true);
        stopSelf();
    }

    // ---- progress ----
    private void setStep(String step, int percent) {
        stepText = step;
        updateNotification(step);
        post(step, percent);
    }

    private void post(String step, int percent) {
        DeepOpProgressRepository.get().postRunning(owner, step, percent);
    }

    private void log(String line) { Log.d(TAG, line); }

    // ---- hardware locks (mirrors WatchdogService) ----
    private void acquireHardwareLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IIAB:DeepOpWakeLock");
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IIAB:DeepOpWifiLock");
            wifiLock.acquire();
        }
    }

    private void releaseHardwareLocks() {
        if (wakeLock != null && wakeLock.isHeld()) { wakeLock.release(); wakeLock = null; }
        if (wifiLock != null && wifiLock.isHeld()) { wifiLock.release(); wifiLock = null; }
    }

    // ---- notification (swipe-proof: re-assert startForeground, never notify(); mirrors InstallService) ----
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.deepop_channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.deepop_channel_desc));
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) m.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, LibraryActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, DeepOpService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.deepop_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.deepop_notif_cancel), cancelIntent)
                .build();
    }

    private void updateNotification(final String text) {
        if (finished) return;
        main.post(() -> { if (!finished) startForeground(NOTIFICATION_ID, buildNotification(text)); });
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
