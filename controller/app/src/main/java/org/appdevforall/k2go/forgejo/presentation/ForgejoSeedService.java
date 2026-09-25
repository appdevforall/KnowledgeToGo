/*
 * ============================================================================
 * Name        : ForgejoSeedService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-423. Foreground service that runs the Forgejo seed as a VISIBLE
 *               chained step after the role installs and the server is up: it drives
 *               the box seed (ForgejoSeedClient) and publishes state to
 *               ForgejoSeedRepository, which the install index and the detail card
 *               observe. It replaces the old silent background drain
 *               (ForgejoSeedProvisioner). No cancel: the seed either finishes or the
 *               user runs it in the background (the service keeps going).
 * ============================================================================
 */
package org.appdevforall.k2go.forgejo.presentation;

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
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.forgejo.data.ForgejoInstallPrefs;
import org.appdevforall.k2go.forgejo.data.ForgejoSeedClient;
import org.appdevforall.k2go.system.data.InstalledModulesReader;
import org.appdevforall.k2go.util.AppExecutors;

import java.util.Set;

/**
 * Drives one Forgejo seed to a terminal state on an IO thread ({@link ForgejoSeedClient#drive}
 * blocks), streaming its log tail into {@link ForgejoSeedRepository}. Started by the install index
 * once the module server is observed up, so a POST reaches a live dash-node (under proot); also
 * usable as a self-healing resume for a seed left banked by an interrupted background run.
 *
 * <p>The banked pref ({@link ForgejoInstallPrefs}) is the durable marker: cleared on a seeded box
 * or after the bounded give-up, so a completed or abandoned seed never re-runs.
 */
public final class ForgejoSeedService extends Service {

    private static final String TAG = "K2Go-Provision";
    private static final String CHANNEL_ID = "forgejo_seed_channel";
    private static final int NOTIFICATION_ID = 8;

    public static final String ACTION_START = "org.iiab.controller.FORGEJO_SEED_START";

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Start (or re-attach to) the seed. Idempotent: a running session is left alone. */
    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, ForgejoSeedService.class).setAction(ACTION_START));
    }

    /** Clear the finished session so a later install starts clean. */
    public static void finishSession() {
        ForgejoSeedRepository.get().clearSession();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Satisfy the startForegroundService contract (startForeground within ~5s) BEFORE any early
        // return: a fresh start that bailed out without it would crash (FGS did not start in time).
        startForeground(NOTIFICATION_ID, buildNotification());
        ForgejoSeedRepository repo = ForgejoSeedRepository.get();
        // A drive is already running: keep the foreground it owns, do not start a second POST/poll loop.
        if (repo.isRunning()) {
            return START_NOT_STICKY;
        }
        // Nothing banked: nothing to do (a stale start after the seed already cleared).
        if (!ForgejoInstallPrefs.isSeedPending(this)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean includeRepos = ForgejoInstallPrefs.includeRepos(this);
        repo.startSession(includeRepos);
        drive(includeRepos);
        return START_NOT_STICKY;
    }

    /** Total drive attempts before the seed is marked failed (best-effort give-up). */
    private static final int MAX_ATTEMPTS = ForgejoInstallPrefs.MAX_ATTEMPTS;
    private static final long RETRY_DELAY_MS = 4000L;

    private void drive(final boolean includeRepos) {
        final Context app = getApplicationContext();
        AppExecutors.get().io().execute(() -> {
            ForgejoSeedRepository repo = ForgejoSeedRepository.get();
            // Nothing to seed if the role did not land: a DEFINITE absence clears the banked marker
            // (a null read is "could not tell", so it is left for a later pass). Mirrors the check the
            // old provisioner did, so a stale/failed install does not drive a 5-minute seed timeout.
            Set<String> installed = InstalledModulesReader.installedKeys(app);
            if (installed != null && !installed.contains("forgejo")) {
                Log.i(TAG, "forgejo seed: Forgejo is not installed; clearing the banked seed");
                ForgejoInstallPrefs.clearSeed(app);
                repo.clearSession();
                main.post(() -> { stopForeground(true); stopSelf(); });
                return;
            }
            // The service owns the bounded retry so the session stays ACTIVE across attempts (a
            // FAILED-then-restart would fight the index's "already has a session" start guard). The
            // repository is set terminal (DONE/FAILED) only once here, at the end.
            ForgejoSeedClient.Result r = ForgejoSeedClient.Result.ERROR;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                Log.i(TAG, "forgejo seed: driving (includeRepos=" + includeRepos + ", attempt "
                        + attempt + "/" + MAX_ATTEMPTS + ")");
                r = new ForgejoSeedClient().drive(includeRepos, repo::appendLog);
                if (r == ForgejoSeedClient.Result.DONE) break;
                if (attempt < MAX_ATTEMPTS) {
                    repo.appendLog("seed attempt " + attempt + " failed; retrying");
                    try { Thread.sleep(RETRY_DELAY_MS); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
            // Clear the banked marker either way: done needs no re-run, and a give-up is best-effort
            // (the role installed; the seed is retried by a later install, not forever).
            ForgejoInstallPrefs.clearSeed(app);
            boolean ok = r == ForgejoSeedClient.Result.DONE;
            Log.i(TAG, "forgejo seed: " + (ok ? "done" : "gave up (best-effort)"));
            repo.finish(ok);
            main.post(() -> { stopForeground(true); stopSelf(); });
        });
    }

    // ---- notification ------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.k2go_forgejo_seed_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        // The index is the surface someone comes back to; route there (one owner: OpReturnNavigator).
        PendingIntent contentIntent = org.appdevforall.k2go.redesign.OpReturnNavigator.notify(this,
                org.appdevforall.k2go.redesign.OpReturnNavigator.contentDownload(this));
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_forgejo_seed_notif_title))
                .setContentText(getString(R.string.k2go_forgejo_seed_title))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .build();
    }
}
