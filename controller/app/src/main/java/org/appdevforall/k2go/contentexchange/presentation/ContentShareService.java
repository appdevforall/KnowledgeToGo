/*
 * ============================================================================
 * Name        : ContentShareService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-376. Sender foreground service for granular wireless Share. Over the current
 *               content-manager selection it opens a scoped share session on the LOCAL cx-core
 *               (loopback), brings the hotspot up (only if it was off), holds both the hotspot and
 *               the gate open with CPU + Wi-Fi locks, and on teardown closes the gate (DELETE) and
 *               stops the hotspot only if it started it. It copies NO bytes: cx-core streams to the
 *               receiver that pulls over the hotspot. The invite/QR fragment observes SESSION.
 * ============================================================================
 */
package org.appdevforall.k2go.contentexchange.presentation;

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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.CxCoreDataSource;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.appdevforall.k2go.hotspot.HotspotPermissions;
import org.appdevforall.k2go.hotspot.LocalHotspotManager;
import org.appdevforall.k2go.notifications.NotificationIds;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ContentShareService extends Service {

    public static final String ACTION_START = "org.appdevforall.k2go.CONTENT_SHARE_START";
    public static final String ACTION_CANCEL = "org.appdevforall.k2go.CONTENT_SHARE_CANCEL";

    public static final String EXTRA_APPS = "apps";
    public static final String EXTRA_LOCAL_REFS = "localRefs";

    private static final String CHANNEL_ID = "content_share_channel";
    private static final int NOTIFICATION_ID = NotificationIds.CONTENT_SHARE;

    /** The single run state the invite/QR fragment observes. */
    private static final ShareSession SESSION = new ShareSession();

    static ShareSession session() {
        return SESSION;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // The open session on the local cx-core, held so onDestroy can close the gate. Set by the worker.
    private volatile String sessionId;
    private volatile String token;
    // True only when THIS service started the hotspot, so teardown never yanks a hotspot that Connect
    // or Clone brought up (LocalHotspotManager is a shared, process-bound singleton).
    private volatile boolean ownedHotspot;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // ---- static control surface (the fragment calls these) -------------------------------------

    public static void start(Context ctx, ItemRef[] refs) {
        String[] apps = new String[refs.length];
        String[] locals = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            apps[i] = refs[i].app();
            locals[i] = refs[i].localRef();
        }
        Intent i = new Intent(ctx, ContentShareService.class).setAction(ACTION_START)
                .putExtra(EXTRA_APPS, apps)
                .putExtra(EXTRA_LOCAL_REFS, locals);
        ContextCompat.startForegroundService(ctx, i);
    }

    /** Stop sharing (the user's "Done"/"Stop", and the notification action): closes the gate and the
     *  hotspot in onDestroy. */
    public static void cancel(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, ContentShareService.class).setAction(ACTION_CANCEL));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CANCEL.equals(action)) {
            // Satisfy the FGS start-foreground obligation (a fresh instance may handle this), then tear
            // down. onDestroy closes the gate and stops the hotspot.
            startForeground(NOTIFICATION_ID, buildNotification());
            SESSION.clear();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // A sticky restart after a process kill delivers a null intent. The hotspot is process-bound, so
        // it died with the process and there is nothing to resume; do not re-hold locks for a dead share.
        if (intent == null && !SESSION.hasSession()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (SESSION.hasSession()) {
            // A duplicate start while a share is live. This onStartCommand may come from
            // startForegroundService, so honor the FGS start obligation before ignoring it; the
            // fragment shows the running share.
            startForeground(NOTIFICATION_ID, buildNotification());
            return START_STICKY;
        }

        // START: a fresh share over the intent's selection.
        final ItemRef[] refs = readRefs(intent);
        if (refs.length == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SESSION.beginPreparing(refs.length);
        startForeground(NOTIFICATION_ID, buildNotification());
        acquireHardwareLocks();
        ensureHotspot();
        worker.execute(() -> openSession(refs));
        // START_STICKY (like CloneShareService): keep the process at foreground priority so the
        // process-bound hotspot and the live stream survive backgrounding and Doze.
        return START_STICKY;
    }

    // ---- the run -------------------------------------------------------------------------------

    /** Open the scoped share session on the local cx-core and publish the invite. Copies no bytes. */
    private void openSession(ItemRef[] refs) {
        List<ItemRef> items = new ArrayList<>();
        for (ItemRef ref : refs) {
            items.add(ref);
        }
        try {
            CxCoreDataSource.ShareCreated created = new CxCoreDataSource().postShareCreate(items);
            if (created.sessionId.isEmpty() || created.token.isEmpty() || created.manifestUrl.isEmpty()) {
                SESSION.fail(getString(R.string.k2go_cx_share_error));
                return;
            }
            // Store before publishing, so a teardown that races the publish still has the id to close.
            this.sessionId = created.sessionId;
            this.token = created.token;
            SESSION.setReady(created.sessionId, created.token, created.manifestUrl);
        } catch (Exception e) {
            SESSION.fail(getString(R.string.k2go_cx_share_error));
        }
    }

    /**
     * Bring the hotspot up only if it is currently off. If Connect or Clone already has it up (ON or
     * STARTING), this service does not own it and must not stop it on teardown. The runtime hotspot
     * permission is gated by the fragment before start; without it the hotspot stays off and the
     * fragment surfaces the failure from LocalHotspotManager's state.
     */
    private void ensureHotspot() {
        if (!LocalHotspotManager.isSupported()) {
            return;
        }
        LocalHotspotManager hs = LocalHotspotManager.get();
        LocalHotspotManager.State st = hs.state().getValue();
        LocalHotspotManager.Phase phase = st != null ? st.phase : LocalHotspotManager.Phase.OFF;
        boolean up = phase == LocalHotspotManager.Phase.ON || phase == LocalHotspotManager.Phase.STARTING;
        if (!up && HotspotPermissions.granted(this)) {
            ownedHotspot = true;
            hs.start(getApplicationContext());
        }
    }

    private void acquireHardwareLocks() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IIAB:ContentShareWakeLock");
            wakeLock.acquire();
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wifiLock == null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "IIAB:ContentShareWifiLock");
            wifiLock.acquire();
        }
    }

    private void releaseHardwareLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;
    }

    @Override
    public void onDestroy() {
        releaseHardwareLocks();
        // Stop the hotspot only if this service started it (shared singleton: never yank Connect's).
        if (ownedHotspot) {
            LocalHotspotManager.get().stop();
            ownedHotspot = false;
        }
        // Close the gate (the app-side closer, ADR "Pillar Share"). Off the main thread; the process
        // usually outlives this service (the app is still open). A rare race where the id is not stored
        // yet, or a process kill, is covered by cx-core's safety cap and the receiver's complete.
        final String id = sessionId;
        final String tok = token;
        if (id != null && tok != null) {
            new Thread(() -> {
                try {
                    new CxCoreDataSource().deleteShare(id, tok);
                } catch (Exception ignored) {
                    // best-effort close; cx-core's safety cap reclaims an abandoned session
                }
            }, "content-share-close").start();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static ItemRef[] readRefs(Intent intent) {
        if (intent == null) {
            return new ItemRef[0];
        }
        String[] apps = intent.getStringArrayExtra(EXTRA_APPS);
        String[] locals = intent.getStringArrayExtra(EXTRA_LOCAL_REFS);
        if (apps == null || locals == null || apps.length != locals.length) {
            return new ItemRef[0];
        }
        ItemRef[] refs = new ItemRef[apps.length];
        for (int i = 0; i < apps.length; i++) {
            refs[i] = new ItemRef(apps[i], locals[i]);
        }
        return refs;
    }

    // ---- notification --------------------------------------------------------------------------

    private Notification buildNotification() {
        Intent cancel = new Intent(this, ContentShareService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_cx_share_notif_title))
                .setContentText(getString(R.string.k2go_cx_share_notif_text))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_cx_share_stop), cancelIntent)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_cx_share_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
