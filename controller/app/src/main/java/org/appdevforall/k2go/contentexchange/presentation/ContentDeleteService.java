/*
 * ============================================================================
 * Name        : ContentDeleteService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Foreground service that drains the delete queue: per selected item, call
 *               the core DELETE /v1/content/{app}/{localRef} and accumulate the bytes it reclaimed.
 *               Reuses the download-service pattern (foreground specialUse + per-item queue + retry
 *               + notification). Simpler than export: the box owns the deletion, so there is no SAF
 *               destination, no staging, no byte copy: each item is one atomic call. The item is the
 *               unit of resume (per-item retry of a failed delete).
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
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.CxCoreDataSource;
import org.appdevforall.k2go.contentexchange.data.CxCoreDeleteSource;
import org.appdevforall.k2go.contentexchange.domain.ContentDeleteSource;
import org.appdevforall.k2go.contentexchange.domain.DeleteJob;
import org.appdevforall.k2go.contentexchange.domain.DeleteResult;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.appdevforall.k2go.notifications.NotificationIds;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ContentDeleteService extends Service {

    public static final String ACTION_START = "org.appdevforall.k2go.CONTENT_DELETE_START";
    public static final String ACTION_RETRY = "org.appdevforall.k2go.CONTENT_DELETE_RETRY";
    public static final String ACTION_CANCEL = "org.appdevforall.k2go.CONTENT_DELETE_CANCEL";

    public static final String EXTRA_APPS = "apps";
    public static final String EXTRA_LOCAL_REFS = "localRefs";
    public static final String EXTRA_LABELS = "labels";

    private static final String CHANNEL_ID = "content_delete_channel";
    private static final int NOTIFICATION_ID = NotificationIds.CONTENT_DELETE;

    /** The single run state the progress fragment observes. */
    private static final DeleteSession SESSION = new DeleteSession();

    static DeleteSession session() {
        return SESSION;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // ---- static control surface (the fragment calls these) -------------------------------------

    public static void start(Context ctx, ItemRef[] refs, String[] labels) {
        String[] apps = new String[refs.length];
        String[] locals = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            apps[i] = refs[i].app();
            locals[i] = refs[i].localRef();
        }
        Intent i = new Intent(ctx, ContentDeleteService.class).setAction(ACTION_START)
                .putExtra(EXTRA_APPS, apps)
                .putExtra(EXTRA_LOCAL_REFS, locals)
                .putExtra(EXTRA_LABELS, labels);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void retry(Context ctx) {
        if (SESSION.canRetry()) {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, ContentDeleteService.class).setAction(ACTION_RETRY));
        }
    }

    public static void cancel(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, ContentDeleteService.class).setAction(ACTION_CANCEL));
    }

    /** Clear a settled session (the "Finish" button). Safe to call when nothing is running. */
    public static void finishSession() {
        SESSION.clear();
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
            SESSION.markCancelled();
            if (SESSION.isRunning()) {
                // A run is draining: the worker sees the flag, finishes the delete in flight (a sent
                // DELETE cannot be aborted), exits the loop, and clears + stops itself, so the job is
                // only ever mutated from the worker thread. Re-assert foreground to satisfy this
                // delivery's start-foreground obligation while the worker winds down.
                startForeground(NOTIFICATION_ID, buildNotification(currentLabel()));
            } else {
                // Nothing is draining (the run already settled, or a fresh service instance handles the
                // cancel): satisfy the FGS start-foreground obligation before tearing down.
                startForeground(NOTIFICATION_ID, buildNotification(currentLabel()));
                SESSION.clear();
                stopForeground(true);
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (SESSION.isRunning()) {
            return START_NOT_STICKY;   // a duplicate start while draining: ignore
        }

        if (ACTION_RETRY.equals(action)) {
            if (!SESSION.canRetry()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            SESSION.requeueFailed();   // flip failed items back to pending
            SESSION.setRunning(true);
            startForeground(NOTIFICATION_ID, buildNotification(null));
            worker.execute(this::drain);
            return START_NOT_STICKY;
        }

        // START: a fresh run from the intent.
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final ItemRef[] refs = readRefs(intent);
        if (refs.length == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SESSION.begin(refs, intent.getStringArrayExtra(EXTRA_LABELS));
        startForeground(NOTIFICATION_ID, buildNotification(null));
        worker.execute(this::drain);
        return START_NOT_STICKY;
    }

    // ---- the run -------------------------------------------------------------------------------

    /**
     * Drain the pending items: delete each on the box and accumulate the reclaimed bytes.
     *
     * <p>v1 limitation (accepted): the run state is in-memory, so a process death mid-run loses it
     * (no cross-process-death resume). v1 does within-run per-item retry only. A delete call cannot
     * be interrupted mid-flight (the box runs the app's own delete), so cancel lands after the item
     * in flight finishes.
     */
    private void drain() {
        ContentDeleteSource source = new CxCoreDeleteSource(new CxCoreDataSource());
        DeleteJob job = SESSION.job();

        for (int i = job.firstPending(); i >= 0; i = job.firstPending()) {
            if (SESSION.isCancelled()) {
                break;
            }
            ItemRef ref = SESSION.refAt(i);
            job.setActive(i);
            SESSION.publish();
            notifyNow();

            if (ref == null || isBlank(ref.app()) || isBlank(ref.localRef())) {
                job.setFailed(i);   // defensive: an unaddressable item never reaches the box
                SESSION.publish();
                continue;
            }
            try {
                DeleteResult result = source.delete(ref);
                if (result.ok()) {
                    job.setDone(i, result.freedBytes());
                } else {
                    job.setFailed(i);
                }
            } catch (Exception e) {
                job.setFailed(i);   // box unreachable or a non-2xx: skip it, keep going
            }
            SESSION.publish();
        }

        if (SESSION.isCancelled()) {
            SESSION.clear();
            stopForeground(true);
            stopSelf();
            return;
        }

        SESSION.setRunning(false);
        SESSION.publish();
        stopForeground(true);
        stopSelf();
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static ItemRef[] readRefs(Intent intent) {
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

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    // ---- notification --------------------------------------------------------------------------

    private void notifyNow() {
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) {
            m.notify(NOTIFICATION_ID, buildNotification(currentLabel()));
        }
    }

    @Nullable
    private String currentLabel() {
        int i = SESSION.index();
        return i >= 0 ? SESSION.label(i) : null;
    }

    private Notification buildNotification(@Nullable String contentText) {
        Intent cancel = new Intent(this, ContentDeleteService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = contentText != null && !contentText.isEmpty()
                ? contentText : getString(R.string.k2go_cx_delete_notif_text);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_cx_delete_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_dash_cancel), cancelIntent)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_cx_delete_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
