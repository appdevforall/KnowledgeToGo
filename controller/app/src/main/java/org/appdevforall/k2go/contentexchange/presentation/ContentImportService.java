/*
 * ============================================================================
 * Name        : ContentImportService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Foreground service that drains the import queue: per selected item, SAF-read
 *               the archive from the user-picked drive, write it to the box-visible import-staging path
 *               the core reads, POST /v1/import/item so the core ingests it (delegating to the app's own
 *               import), then remove the staging copy. The mirror of ContentExportService (foreground
 *               specialUse + per-item queue + retry + notification + grant release), with the bytes
 *               flowing drive -> phone staging -> box. The item is the unit of resume (per-item retry);
 *               pause/resume and within-item byte-resume are later work.
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
import org.appdevforall.k2go.contentexchange.data.BoxImportStaging;
import org.appdevforall.k2go.contentexchange.data.CxCoreDataSource;
import org.appdevforall.k2go.contentexchange.data.CxCoreImportSource;
import org.appdevforall.k2go.contentexchange.data.NetworkImportCarrier;
import org.appdevforall.k2go.contentexchange.data.SafImportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ExportJob;
import org.appdevforall.k2go.contentexchange.domain.ImportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ImportSource;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.appdevforall.k2go.notifications.NotificationIds;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ContentImportService extends Service {

    public static final String ACTION_START = "org.appdevforall.k2go.CONTENT_IMPORT_START";
    public static final String ACTION_RETRY = "org.appdevforall.k2go.CONTENT_IMPORT_RETRY";
    public static final String ACTION_CANCEL = "org.appdevforall.k2go.CONTENT_IMPORT_CANCEL";

    public static final String EXTRA_TREE_URI = "treeUri";
    /** Network (Share) source: the sender's hotspot address, token and manifest path (K2GO-376). A
     *  START intent carries either {@link #EXTRA_TREE_URI} (drive) or these (network), never both. */
    public static final String EXTRA_NET_HOST = "netHost";
    public static final String EXTRA_NET_PORT = "netPort";
    public static final String EXTRA_SHARE_TOKEN = "shareToken";
    public static final String EXTRA_MANIFEST_URL = "manifestUrl";
    public static final String EXTRA_APPS = "apps";
    public static final String EXTRA_LOCAL_REFS = "localRefs";
    public static final String EXTRA_TITLES = "titles";
    public static final String EXTRA_ENTRIES = "entries";
    public static final String EXTRA_PAYLOADS = "payloads";
    public static final String EXTRA_BYTES = "bytes";

    private static final String CHANNEL_ID = "content_import_channel";
    private static final int NOTIFICATION_ID = NotificationIds.CONTENT_IMPORT;
    private static final int COPY_BUFFER = 64 * 1024;
    private static final long PUBLISH_INTERVAL_MS = 250L;
    private static final long NOTIFY_INTERVAL_MS = 1000L;

    /** The single run state the progress fragment observes. */
    private static final ImportSession SESSION = new ImportSession();

    static ImportSession session() {
        return SESSION;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    // ---- static control surface (the fragment calls these) -------------------------------------

    /** Start an import of the selected items from the drive {@code treeUri}. */
    public static void start(Context ctx, Uri treeUri, ItemRef[] refs, String[] titles,
                             String[] entries, String[] payloads, long[] bytes) {
        String[] apps = new String[refs.length];
        String[] locals = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            apps[i] = refs[i].app();
            locals[i] = refs[i].localRef();
        }
        Intent i = new Intent(ctx, ContentImportService.class).setAction(ACTION_START)
                .putExtra(EXTRA_TREE_URI, treeUri.toString())
                .putExtra(EXTRA_APPS, apps)
                .putExtra(EXTRA_LOCAL_REFS, locals)
                .putExtra(EXTRA_TITLES, titles)
                .putExtra(EXTRA_ENTRIES, entries)
                .putExtra(EXTRA_PAYLOADS, payloads)
                .putExtra(EXTRA_BYTES, bytes);
        ContextCompat.startForegroundService(ctx, i);
    }

    /**
     * Start a network import: pull the sender-scoped items from its gate over the hotspot (K2GO-376).
     * Same per-item queue / staging / ingest as the drive path; only the carrier (the source) differs.
     */
    public static void startNetwork(Context ctx, String host, int port, String token, String manifestUrl,
                                    ItemRef[] refs, String[] titles, String[] entries,
                                    String[] payloads, long[] bytes) {
        String[] apps = new String[refs.length];
        String[] locals = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            apps[i] = refs[i].app();
            locals[i] = refs[i].localRef();
        }
        Intent i = new Intent(ctx, ContentImportService.class).setAction(ACTION_START)
                .putExtra(EXTRA_NET_HOST, host)
                .putExtra(EXTRA_NET_PORT, port)
                .putExtra(EXTRA_SHARE_TOKEN, token)
                .putExtra(EXTRA_MANIFEST_URL, manifestUrl)
                .putExtra(EXTRA_APPS, apps)
                .putExtra(EXTRA_LOCAL_REFS, locals)
                .putExtra(EXTRA_TITLES, titles)
                .putExtra(EXTRA_ENTRIES, entries)
                .putExtra(EXTRA_PAYLOADS, payloads)
                .putExtra(EXTRA_BYTES, bytes);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void retry(Context ctx) {
        if (SESSION.canRetry()) {
            ContextCompat.startForegroundService(ctx,
                    new Intent(ctx, ContentImportService.class).setAction(ACTION_RETRY));
        }
    }

    public static void cancel(Context ctx) {
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, ContentImportService.class).setAction(ACTION_CANCEL));
    }

    /** Clear a settled session (the "Finish" button) and release its SAF grant. Safe when idle. */
    public static void finishSession(Context ctx) {
        releaseGrant(ctx, SESSION.treeUri());
        SESSION.clear();
    }

    /**
     * Release the persisted SAF grant for a finished import. Import is one-shot (pick a drive, read,
     * done), so the grant is not needed afterwards; releasing it keeps the app's persisted-permission
     * set from growing toward the system cap. Idempotent.
     */
    static void releaseGrant(Context ctx, String treeUri) {
        if (treeUri == null) {
            return;
        }
        try {
            ctx.getContentResolver().releasePersistableUriPermission(Uri.parse(treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // already released, or never persisted: nothing to free
        }
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
            // Release the grant and flag cancel. If a worker is draining it owns teardown (its cancelled
            // branch clears the session and stops the service), so clearing or stopping here would race
            // its reads of refs/job. Clear and stop on this thread only when no run is active.
            releaseGrant(getApplicationContext(), SESSION.treeUri());
            boolean wasRunning = SESSION.isRunning();
            SESSION.markCancelled();
            if (!wasRunning) {
                // No worker owns teardown: a fresh service instance may be handling this cancel, so
                // satisfy the FGS start-foreground obligation before stopping.
                startForeground(NOTIFICATION_ID, buildNotification(null));
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
            SESSION.requeueFailed();
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
        String treeUri = intent.getStringExtra(EXTRA_TREE_URI);
        String netHost = intent.getStringExtra(EXTRA_NET_HOST);
        String[] apps = intent.getStringArrayExtra(EXTRA_APPS);
        String[] locals = intent.getStringArrayExtra(EXTRA_LOCAL_REFS);
        String[] titles = intent.getStringArrayExtra(EXTRA_TITLES);
        String[] entries = intent.getStringArrayExtra(EXTRA_ENTRIES);
        String[] payloads = intent.getStringArrayExtra(EXTRA_PAYLOADS);
        long[] bytes = intent.getLongArrayExtra(EXTRA_BYTES);
        boolean network = netHost != null;
        if ((treeUri == null && !network) || apps == null || locals == null || apps.length == 0
                || apps.length != locals.length) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ItemRef[] refs = new ItemRef[apps.length];
        for (int i = 0; i < apps.length; i++) {
            refs[i] = new ItemRef(apps[i], locals[i]);
        }
        if (network) {
            SESSION.beginNetwork(refs, titles, entries, payloads, bytes, netHost,
                    intent.getIntExtra(EXTRA_NET_PORT, 0),
                    intent.getStringExtra(EXTRA_SHARE_TOKEN), intent.getStringExtra(EXTRA_MANIFEST_URL));
        } else {
            SESSION.begin(refs, titles, entries, payloads, bytes, treeUri);
        }
        startForeground(NOTIFICATION_ID, buildNotification(null));
        worker.execute(this::drain);
        return START_NOT_STICKY;
    }

    // ---- the run -------------------------------------------------------------------------------

    /**
     * Drain the pending items: read each archive from the drive, stage it where the box can read it,
     * ingest it, then clean up the staged copy.
     *
     * <p>v1 limitation (accepted): the run state is in-memory, so a process death mid-import loses it;
     * v1 does within-run per-item retry only. Durable op-state and byte-resume are v1.x.
     */
    private void drain() {
        ImportSource source = new CxCoreImportSource(new CxCoreDataSource());
        BoxImportStaging staging = new BoxImportStaging(getApplicationContext());
        ImportCarrier carrier;
        try {
            // The carrier is the only sender-specific dependency: a drive (SAF) source or a paired
            // sender over the hotspot (network). Everything after it (stage + ingest) is identical.
            if (SESSION.isNetwork()) {
                carrier = new NetworkImportCarrier(getApplicationContext(), SESSION.shareHost(),
                        SESSION.sharePort(), SESSION.shareToken(), SESSION.shareManifestUrl(),
                        SESSION::isCancelled);
            } else {
                carrier = new SafImportCarrier(getApplicationContext(), Uri.parse(SESSION.treeUri()));
            }
        } catch (Exception e) {
            finishWithError("Could not open the source: " + e.getMessage());
            return;
        }
        SESSION.setSource(carrier.sourceLabel());

        ExportJob job = SESSION.job();
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

            String stored = BoxImportStaging.safeName(SESSION.payloadName(i));
            try {
                copyToStaging(carrier, staging, SESSION.payloadName(i), stored, i, job, runStart);
                source.importItem(ref.app(), SESSION.entryJson(i), BoxImportStaging.boxPath(stored));
                job.setDone(i);
            } catch (Exception e) {
                // The staging copy or the ingest failed: mark the item and keep its slot for a retry.
                job.setFailed(i);
                SESSION.publish();
                staging.remove(stored);
                if (isNoSpace(e)) {
                    // No room on the shared phone/box disk: stop, leave the rest pending for a Retry.
                    finishWithError("Not enough space on the device.");
                    return;
                }
                continue;
            }
            staging.remove(stored);

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

        if (SESSION.allDone()) {
            // Fully imported: one-shot done, so free the grant now (a partial run keeps it for Retry).
            releaseGrant(getApplicationContext(), SESSION.treeUri());   // null treeUri (network) is a no-op
            try {
                // Clean success: tell a network sender the receiver has everything, so its gate
                // releases the session (best effort; the sender also self-bounds).
                carrier.onAllDelivered();
            } catch (Exception ignored) {
                // the sender's safety cap backstops a failed signal
            }
        }
        SESSION.setRunning(false);
        SESSION.setRate(0L, -1L);
        SESSION.publish();
        stopForeground(true);
        stopSelf();
    }

    /**
     * Copy one archive from the drive to the box-visible staging path, updating per-item progress and
     * honoring cancellation. Both streams close inside try-with-resources so a close-time failure
     * (ENOSPC on the staging disk commonly surfaces at flush/close) propagates and the item is marked
     * failed, never a silent partial written as done.
     */
    private void copyToStaging(ImportCarrier carrier, BoxImportStaging staging, String payloadName,
                               String storedName, int i, ExportJob job, long runStart)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long total = 0L;
        long lastPublish = 0L;
        try (InputStream in = carrier.openPayload(payloadName);
             OutputStream out = staging.open(storedName)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (SESSION.isCancelled()) {
                    throw new IOException("cancelled");
                }
                out.write(buffer, 0, read);
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
            out.flush();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

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

    private static boolean isNoSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(java.util.Locale.US);
                if (lower.contains("enospc") || lower.contains("no space")
                        || lower.contains("insufficient space")) {
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
        Intent cancel = new Intent(this, ContentImportService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = contentText != null && !contentText.isEmpty()
                ? contentText : getString(R.string.k2go_cx_import_notif_text);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.k2go_cx_import_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.k2go_dash_cancel), cancelIntent)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.k2go_cx_import_channel_name),
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
