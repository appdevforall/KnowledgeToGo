/*
 * ============================================================================
 * Name        : ContentExportService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Foreground service that drains the export queue: plan the selected
 *               items, then per item pull the streaming archive from cx-core and write it to the
 *               SAF destination, writing the collection manifest last over the items that landed.
 *               Reuses the download-service pattern (foreground specialUse + per-item queue +
 *               retry + notification), but the bytes flow phone <- box -> drive here, so it is a
 *               plain stream copy, not the server-owned POST/poll engine. The item is the unit of
 *               resume (per-item retry); pause/resume and within-item byte-resume are later work.
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
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.CxCoreDataSource;
import org.appdevforall.k2go.contentexchange.data.CxCoreExportSource;
import org.appdevforall.k2go.contentexchange.data.SafExportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ContentExportSource;
import org.appdevforall.k2go.contentexchange.domain.ExportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ExportFilenames;
import org.appdevforall.k2go.contentexchange.domain.ExportJob;
import org.appdevforall.k2go.contentexchange.domain.ExportPlan;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.appdevforall.k2go.notifications.NotificationIds;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ContentExportService extends Service {

    public static final String ACTION_START = "org.appdevforall.k2go.CONTENT_EXPORT_START";
    public static final String ACTION_RETRY = "org.appdevforall.k2go.CONTENT_EXPORT_RETRY";
    public static final String ACTION_CANCEL = "org.appdevforall.k2go.CONTENT_EXPORT_CANCEL";

    public static final String EXTRA_TREE_URI = "treeUri";
    public static final String EXTRA_APPS = "apps";
    public static final String EXTRA_LOCAL_REFS = "localRefs";

    private static final String CHANNEL_ID = "content_export_channel";
    private static final int NOTIFICATION_ID = NotificationIds.CONTENT_EXPORT;
    private static final int COPY_BUFFER = 64 * 1024;
    private static final long PUBLISH_INTERVAL_MS = 250L;
    private static final long NOTIFY_INTERVAL_MS = 1000L;

    /** The single run state the progress fragment observes. */
    private static final ExportSession SESSION = new ExportSession();

    static ExportSession session() {
        return SESSION;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // ---- static control surface (the fragment calls these) -------------------------------------

    public static void start(Context ctx, Uri treeUri, ItemRef[] refs) {
        String[] apps = new String[refs.length];
        String[] locals = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            apps[i] = refs[i].app();
            locals[i] = refs[i].localRef();
        }
        Intent i = new Intent(ctx, ContentExportService.class).setAction(ACTION_START)
                .putExtra(EXTRA_TREE_URI, treeUri.toString())
                .putExtra(EXTRA_APPS, apps)
                .putExtra(EXTRA_LOCAL_REFS, locals);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void retry(Context ctx) {
        if (SESSION.canRetry()) {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, ContentExportService.class).setAction(ACTION_RETRY));
        }
    }

    public static void cancel(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, ContentExportService.class).setAction(ACTION_CANCEL));
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
            SESSION.clear();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (SESSION.isRunning() && !ACTION_CANCEL.equals(action)) {
            return START_NOT_STICKY;   // a duplicate start while draining: ignore
        }

        if (ACTION_RETRY.equals(action)) {
            if (!SESSION.canRetry()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            SESSION.requeueFailed();   // flip failed items back to pending; leftover pending stay
            SESSION.setRunning(true);
            startForeground(NOTIFICATION_ID, buildNotification(null));
            worker.execute(() -> drain(/* fresh= */ false));
            return START_NOT_STICKY;
        }

        // START: a fresh run from the intent.
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String treeUri = intent.getStringExtra(EXTRA_TREE_URI);
        final ItemRef[] refs = readRefs(intent);
        if (refs.length == 0 || treeUri == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SESSION.beginPlanning(refs, initialLabels(refs), treeUri);
        startForeground(NOTIFICATION_ID, buildNotification(null));
        worker.execute(() -> drain(/* fresh= */ true));
        return START_NOT_STICKY;
    }

    // ---- the run -------------------------------------------------------------------------------

    /** Plan (fresh only), then drain the pending items and write the manifest over what landed. */
    private void drain(boolean fresh) {
        ExportCarrier carrier;
        ContentExportSource source = new CxCoreExportSource(new CxCoreDataSource());
        try {
            carrier = new SafExportCarrier(getApplicationContext(), Uri.parse(SESSION.treeUri()));
        } catch (Exception e) {
            finishWithError("Could not open the destination: " + e.getMessage());
            return;
        }
        SESSION.setDestination(carrier.destinationLabel());

        try {
            ExportPlan plan = source.plan(refsList());
            if (fresh) {
                applyPlan(plan);
            }
        } catch (Exception e) {
            // The box is unreachable: fail the run (keep any items a prior run finished).
            finishWithError("Could not reach the box: " + e.getMessage());
            return;
        }

        ExportJob job = SESSION.job();
        Set<String> used = seedUsedNames();
        long runStart = SystemClock.elapsedRealtime();
        long lastSampleAt = runStart;
        long lastSampleBytes = job.copiedTotal();

        for (int i = job.firstPending(); i >= 0; i = job.firstPending()) {
            if (SESSION.isCancelled()) {
                break;
            }
            ItemRef ref = SESSION.refAt(i);
            job.setActive(i);
            SESSION.publish();
            notifyNow();

            try (ContentExportSource.ItemPayload payload = source.open(ref)) {
                String name = ExportFilenames.resolve(payload.filename(), ref, used);
                ExportCarrier.PayloadSink sink = carrier.openPayload(name);
                SESSION.setPayloadName(i, sink.name());
                copy(payload.stream(), sink.stream(), i, job, runStart);
                job.setDone(i);
            } catch (IOException e) {
                job.setFailed(i);
                SESSION.publish();
                if (isNoSpace(e)) {
                    // The drive is full: stop and leave the rest pending for a Retry after space is freed.
                    finishWithError("The drive is out of space.");
                    return;
                }
                continue;   // a per-item failure: skip it, keep going
            } catch (Exception e) {
                job.setFailed(i);
                SESSION.publish();
                continue;
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastSampleAt >= 500L) {
                sampleRate(job, now - lastSampleAt, job.copiedTotal() - lastSampleBytes);
                lastSampleAt = now;
                lastSampleBytes = job.copiedTotal();
            }
            SESSION.publish();
        }

        if (SESSION.isCancelled()) {
            SESSION.clear();
            stopForeground(true);
            stopSelf();
            return;
        }

        writeManifest(source, carrier, job);
        SESSION.setRunning(false);
        SESSION.setRate(0L, -1L);
        SESSION.publish();
        stopForeground(true);
        stopSelf();
    }

    /**
     * Copy one stream to the destination, updating per-item progress and honoring cancellation. The
     * output is closed here inside try-with-resources so a close-time failure (ENOSPC on a drive
     * commonly surfaces at flush/close, not write) propagates and the item is marked failed, never
     * a silent partial written as done.
     */
    private void copy(InputStream in, OutputStream out, int i, ExportJob job, long runStart)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long total = 0L;
        long lastPublish = 0L;
        try (OutputStream sink = out) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (SESSION.isCancelled()) {
                    throw new IOException("cancelled");
                }
                sink.write(buffer, 0, read);
                total += read;
                job.setCopied(i, total);
                long now = SystemClock.elapsedRealtime();
                if (now - lastPublish >= PUBLISH_INTERVAL_MS) {
                    long elapsed = now - runStart;
                    if (elapsed > 0) {
                        long speed = job.copiedTotal() * 1000L / elapsed;
                        long remaining = job.plannedTotal() - job.copiedTotal();
                        long eta = speed > 0 ? Math.max(0L, remaining / speed) : -1L;
                        SESSION.setRate(speed, eta);
                    }
                    SESSION.publish();
                    maybeNotify(now);
                    lastPublish = now;
                }
            }
            sink.flush();
        }
    }

    private void writeManifest(ContentExportSource source, ExportCarrier carrier, ExportJob job) {
        List<ItemRef> order = new ArrayList<>();
        Map<ItemRef, String> names = new HashMap<>();
        for (int i = 0; i < job.size(); i++) {
            if (job.status(i) == ExportJob.DONE) {
                ItemRef ref = SESSION.refAt(i);
                order.add(ref);
                names.put(ref, SESSION.payloadName(i));
            }
        }
        if (order.isEmpty()) {
            return;   // nothing landed: no manifest to write
        }
        try (OutputStream out = carrier.openManifest()) {
            out.write(source.buildManifest(order, names));
            out.flush();
        } catch (Exception e) {
            // Rare (the manifest is tiny): the payloads are on the drive but the index is missing.
            SESSION.fail("Items were written but the manifest could not be saved. Re-export to rewrite it.");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void applyPlan(ExportPlan plan) {
        // Align the plan to the fixed request order; an item the core could not plan gets -1
        // (ExportJob marks it FAILED) so the queue never blocks waiting for it.
        ItemRef[] refs = SESSION.refs();
        Map<ItemRef, Long> sized = new HashMap<>();
        Map<ItemRef, String> titles = new HashMap<>();
        for (int k = 0; k < plan.items().size(); k++) {
            sized.put(plan.items().get(k).ref(), plan.items().get(k).bytes());
            titles.put(plan.items().get(k).ref(), plan.items().get(k).title());
        }
        long[] planned = new long[refs.length];
        String[] labels = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            Long b = sized.get(refs[i]);
            planned[i] = b != null ? b : -1L;
            String t = titles.get(refs[i]);
            labels[i] = t != null && !t.isEmpty() ? t : refs[i].localRef();
        }
        SESSION.applyPlan(labels, planned, plan.skipped());
    }

    private Set<String> seedUsedNames() {
        Set<String> used = new HashSet<>();
        for (int i = 0; i < SESSION.itemCount(); i++) {
            String name = SESSION.payloadName(i);
            if (name != null) {
                used.add(name);
            }
        }
        return used;
    }

    private void sampleRate(ExportJob job, long dtMs, long dBytes) {
        if (dtMs <= 0) {
            return;
        }
        long speed = Math.max(0L, dBytes * 1000L / dtMs);
        long remaining = job.plannedTotal() - job.copiedTotal();
        long eta = speed > 0 ? Math.max(0L, remaining / speed) : -1L;
        SESSION.setRate(speed, eta);
    }

    private void finishWithError(String message) {
        SESSION.fail(message);
        stopForeground(true);
        stopSelf();
    }

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

    private static String[] initialLabels(ItemRef[] refs) {
        String[] labels = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            labels[i] = refs[i].localRef();
        }
        return labels;
    }

    private List<ItemRef> refsList() {
        List<ItemRef> list = new ArrayList<>();
        ItemRef[] refs = SESSION.refs();
        for (ItemRef ref : refs) {
            list.add(ref);
        }
        return list;
    }

    private static boolean isNoSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.US);
                if (lower.contains("enospc") || lower.contains("no space")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- notification --------------------------------------------------------------------------

    private long lastNotifyAt = 0L;

    private void notifyNow() {
        lastNotifyAt = SystemClock.elapsedRealtime();
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) {
            m.notify(NOTIFICATION_ID, buildNotification(currentLabel()));
        }
    }

    private void maybeNotify(long now) {
        if (now - lastNotifyAt >= NOTIFY_INTERVAL_MS) {
            notifyNow();
        }
    }

    @Nullable
    private String currentLabel() {
        int i = SESSION.index();
        return i >= 0 ? SESSION.label(i) : null;
    }

    private Notification buildNotification(@Nullable String contentText) {
        Intent cancel = new Intent(this, ContentExportService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = contentText != null && !contentText.isEmpty()
                ? contentText : getString(R.string.k2go_cx_export_notif_text);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_cx_export_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_dash_cancel), cancelIntent)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_cx_export_channel_name),
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
