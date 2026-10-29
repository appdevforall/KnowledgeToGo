/*
 * ============================================================================
 * Name        : ContentExportService.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-427. Foreground service that drains the export queue: plan the selected
 *               items, then per item pull the streaming archive from conex-core and write it to the
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
import org.appdevforall.k2go.contentexchange.data.ConexCoreDataSource;
import org.appdevforall.k2go.contentexchange.data.ConexCoreExportSource;
import org.appdevforall.k2go.contentexchange.data.SafExportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ContentExportSource;
import org.appdevforall.k2go.contentexchange.domain.ExportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ExportFilenames;
import org.appdevforall.k2go.contentexchange.domain.ExportJob;
import org.appdevforall.k2go.contentexchange.domain.ExportPlan;
import org.appdevforall.k2go.contentexchange.domain.ExportPrep;
import org.appdevforall.k2go.contentexchange.domain.ExportStatus;
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
    /** K2GO-507: gap between export-staging status polls (mirrors content/RestContentClient). */
    private static final long POLL_INTERVAL_MS = 1000L;
    /** K2GO-507: consecutive status-poll failures tolerated before an item fails. A staging run lasts
     *  minutes, so one relay blip or a busy core must not discard it (mirrors RestContentClient). */
    private static final int MAX_POLL_ERRORS = 10;

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

    /** Clear a settled session (the "Finish" button) and release its one-shot SAF grant. Safe to
     *  call when nothing is running. */
    public static void finishSession(Context ctx) {
        releaseGrant(ctx, SESSION.treeUri());
        SESSION.clear();
    }

    /**
     * Release the persisted SAF grant for a finished export. Export is one-shot (pick a drive, write,
     * done), so the grant is not needed afterwards; releasing it keeps the app's persisted-permission
     * set from growing toward the system cap. Idempotent: releasing a grant not held is a no-op.
     */
    static void releaseGrant(Context ctx, String treeUri) {
        if (treeUri == null) {
            return;
        }
        try {
            ctx.getContentResolver().releasePersistableUriPermission(Uri.parse(treeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // already released, or never persisted (transient grant): nothing to free
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
            // A fresh service instance may handle this cancel (no run ever started in it), so satisfy the
            // FGS start-foreground obligation before tearing down. Idempotent if already foreground.
            startForeground(NOTIFICATION_ID, buildNotification(null));
            releaseGrant(getApplicationContext(), SESSION.treeUri());
            boolean wasRunning = SESSION.isRunning();
            SESSION.markCancelled();
            if (!wasRunning) {
                // No worker draining: tear down here. When a run IS draining, its cancelled branch owns
                // teardown (clear + stop), so clearing here would race the worker's reads of refs/job.
                SESSION.clear();
                stopForeground(true);
                stopSelf();
            }
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

    /**
     * Plan (fresh only), then drain the pending items and write the manifest over what landed.
     *
     * <p>v1 limitation (accepted): the run state is in-memory, so a process death mid-export loses it
     * (no cross-process-death resume), and because the manifest is written last, an interrupted run
     * leaves payloads on the drive with no manifest. v1 does within-run per-item retry only; durable
     * op-state and the manifest-interrupt hardening are v1.x (the import side also validates and skips).
     */
    private void drain(boolean fresh) {
        ExportCarrier carrier;
        ContentExportSource source = new ConexCoreExportSource(new ConexCoreDataSource());
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

            String opId = null;
            try {
                // Prepare phase (K2GO-507): ask the box to stage the item and poll until it is ready.
                // The poll feeds the prepare-half bar and IS the liveness signal, so there is no
                // wall-clock timeout; only the later fetch has a per-read stall timeout.
                opId = stage(source, ref, i, job);
                // Write phase: fetch the staged stream and copy it to the drive.
                job.setPhase(i, ExportJob.PHASE_WRITE);
                SESSION.publish();
                try (ContentExportSource.ItemPayload payload = source.fetch(opId)) {
                    String name = ExportFilenames.forItem(SESSION.label(i), payload.filename(), ref, used);
                    // Write into the item's per-app subfolder; the sink reports the stored path
                    // ("app/name"), which becomes the manifest payload.
                    ExportCarrier.PayloadSink sink = carrier.openPayload(ref.app(), name);
                    SESSION.setPayloadName(i, sink.name());
                    copy(payload.stream(), sink.stream(), i, job, runStart);
                }
                // The core clears the op when the fetch stream closes, so the happy path does not abort.
                job.setDone(i);
            } catch (IOException e) {
                source.abort(opId);   // no-op when opId is null or the op is already dropped
                job.setFailed(i);
                SESSION.publish();
                if (isNoSpace(e)) {
                    // The drive is full: stop and leave the rest pending for a Retry after space is freed.
                    finishWithError("The drive is out of space.");
                    return;
                }
                continue;   // a per-item failure: skip it, keep going
            } catch (Exception e) {
                source.abort(opId);
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
        if (SESSION.allDone()) {
            // Fully exported: one-shot done, so free the grant now (a partial run keeps it for Retry).
            releaseGrant(getApplicationContext(), SESSION.treeUri());
        }
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

    /**
     * Prepare one item and poll the box until it is staged, feeding the prepare-half bar from the
     * staged byte count. Returns the opId to fetch. The poll is the liveness signal (K2GO-507): a slow
     * multi-GB stage keeps the operation alive as long as its byte count grows, so there is no
     * wall-clock timeout here. A swept operation (status 404 -> {@code GONE}) triggers exactly one
     * re-prepare; a second sweep fails the item. Cancellation and a box staging error abort the
     * operation here before throwing, so the caller does not double-abort (its opId is still null).
     */
    private String stage(ContentExportSource source, ItemRef ref, int i, ExportJob job) throws Exception {
        ExportPrep prep = source.prepare(ref);
        String opId = prep.opId();
        if (prep.ready()) {
            return opId;   // nothing to stage: Kiwix/Calibre stream directly
        }
        boolean reprepared = false;
        boolean handedOff = false;   // true once a READY op is returned: the fetch owns it from then on
        int pollErrors = 0;
        try {
            while (true) {
                if (SESSION.isCancelled()) {
                    throw new IOException("cancelled");
                }
                sleepPoll();
                if (SESSION.isCancelled()) {
                    throw new IOException("cancelled");
                }
                ExportStatus st;
                try {
                    st = source.status(opId);
                } catch (Exception e) {
                    // A swept op returns GONE (handled below), not an exception; this is a transient
                    // poll failure (relay blip, busy core). Keep the same op and retry, up to the blip
                    // budget, so a staging run of several minutes is not lost to one hiccup.
                    if (++pollErrors > MAX_POLL_ERRORS) {
                        throw e;
                    }
                    continue;
                }
                pollErrors = 0;
                switch (st.state()) {
                    case READY:
                        handedOff = true;
                        return opId;
                    case ERROR:
                        throw new Exception(st.error() != null && !st.error().isEmpty()
                                ? st.error() : "the box could not stage this item");
                    case GONE:
                        // The op was swept (e.g. a core restart). Re-prepare once; a second loss fails it.
                        if (reprepared) {
                            throw new Exception("the export operation was lost");
                        }
                        reprepared = true;
                        prep = source.prepare(ref);
                        opId = prep.opId();
                        if (prep.ready()) {
                            handedOff = true;
                            return opId;
                        }
                        break;
                    case STAGING:
                    default:
                        job.setStaged(i, st.stagedBytes());
                        SESSION.publish();
                        maybeNotify(SystemClock.elapsedRealtime());
                        break;
                }
            }
        } finally {
            // stage() owns the op until it hands a READY one to the fetch. On any abnormal exit (cancel,
            // box staging error, poll-budget exhaustion, worker interrupt) abort the current op so a live
            // multi-GB stage is not left running on the box until its TTL sweep. A GONE op is already
            // swept, so the abort is a harmless no-op.
            if (!handedOff) {
                source.abort(opId);
            }
        }
    }

    private static void sleepPoll() throws IOException {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");   // worker shutdown: end the item
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
                // payloadName is the bundle-relative path ("app/file"); ExportFilenames dedups on the
                // bare file name, so seed the bare name.
                int slash = name.lastIndexOf('/');
                used.add(slash >= 0 ? name.substring(slash + 1) : name);
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
