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
 *               flowing drive -> phone staging -> box. The item is the unit of resume (per-item retry).
 *               K2GO-577: a Share item the sender offers over rsync skips the staging copy: the box
 *               pulls it straight into place through a loopback relay, and a Retry resumes its bytes.
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
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.appdevforall.k2go.R;
import org.appdevforall.k2go.contentexchange.data.BoxImportStaging;
import org.appdevforall.k2go.contentexchange.data.ConexCoreDataSource;
import org.appdevforall.k2go.contentexchange.data.ConexCoreImportSource;
import org.appdevforall.k2go.contentexchange.data.NetworkImportCarrier;
import org.appdevforall.k2go.contentexchange.data.SafImportCarrier;
import org.appdevforall.k2go.contentexchange.data.ShareRelay;
import org.appdevforall.k2go.contentexchange.data.ShareRsyncTarget;
import org.appdevforall.k2go.contentexchange.domain.ExportJob;
import org.appdevforall.k2go.contentexchange.domain.ImportCarrier;
import org.appdevforall.k2go.contentexchange.domain.ImportNoSpaceException;
import org.appdevforall.k2go.contentexchange.domain.ImportSource;
import org.appdevforall.k2go.contentexchange.domain.ImportStatus;
import org.appdevforall.k2go.contentexchange.domain.ItemRef;
import org.appdevforall.k2go.contentexchange.domain.PullUnsupportedException;
import org.appdevforall.k2go.download.domain.DownloadEta;
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

    private static final String TAG = "ContentImportService";
    private static final String CHANNEL_ID = "content_import_channel";
    private static final int NOTIFICATION_ID = NotificationIds.CONTENT_IMPORT;
    private static final int COPY_BUFFER = 64 * 1024;
    private static final long PUBLISH_INTERVAL_MS = 250L;
    private static final long NOTIFY_INTERVAL_MS = 1000L;
    /** Minimum width of the trailing window used to sample the transfer rate (delta bytes / delta
     *  time), so the speed shown is the recent rate, not the cumulative average from the run start. */
    private static final long RATE_WINDOW_MS = 500L;
    /** K2GO-376: gap between import-ingest status polls (mirrors ContentExportService). */
    private static final long POLL_INTERVAL_MS = 1000L;
    /** K2GO-376: consecutive status-poll failures tolerated before an item fails. The ingest runs
     *  minutes, so one relay blip or a busy core must not discard it (mirrors ContentExportService). */
    private static final int MAX_POLL_ERRORS = 10;
    /** K2GO-577: gap between progress reports to the sender's gate during an rsync pull. */
    private static final long SENDER_REPORT_INTERVAL_MS = 5000L;
    /** The box reaches the relay on loopback (the box shares the phone's network namespace). */
    private static final String LOOPBACK = "127.0.0.1";

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
        giveUp(ctx);   // Finish after a failure gives up the items that did not arrive
        SESSION.clear();
    }

    /** The user ended the run: drop the kept pull bytes and tell the sender (K2GO-577). */
    private static void giveUp(Context ctx) {
        discardGivenUpPulls();
        releaseSenderOnGiveUp(ctx);
    }

    /**
     * K2GO-577: the user ended a network run before every item arrived (Cancel, or Finish after a
     * failure). Tell the sender, so its screen leaves the progress bar for "stopped". A full
     * success already sent complete, so it does not come here. Snapshot now (the session is
     * cleared next), then call off the main thread.
     */
    private static void releaseSenderOnGiveUp(Context ctx) {
        if (!SESSION.isNetwork() || SESSION.allDone()) {
            return;
        }
        final NetworkImportCarrier carrier = new NetworkImportCarrier(ctx.getApplicationContext(),
                SESSION.shareHost(), SESSION.sharePort(), SESSION.shareToken(),
                SESSION.shareManifestUrl(), () -> false);
        new Thread(carrier::onGivenUp, "content-import-giveup").start();
    }

    /**
     * K2GO-577 (decision a): the user ended the run (Cancel or Finish), so the items that did not
     * arrive will not be resumed. Ask the box to drop the bytes their rsync pulls kept, instead of
     * holding the disk until the 48 h sweep. A drop or a Retry never comes here, so those keep their
     * bytes. Snapshot now (the session is cleared next), then call off the main thread.
     */
    private static void discardGivenUpPulls() {
        if (!SESSION.isNetwork()) {
            return;
        }
        ExportJob job = SESSION.job();
        final java.util.List<String[]> pending = new java.util.ArrayList<>();
        for (int i = 0; i < job.size(); i++) {
            String entry = SESSION.entryJson(i);
            if (job.status(i) != ExportJob.DONE && ShareRsyncTarget.fromEntry(entry) != null) {
                pending.add(new String[]{SESSION.refAt(i).app(), entry});
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        new Thread(() -> {
            ImportSource source = new ConexCoreImportSource(new ConexCoreDataSource());
            for (String[] p : pending) {
                source.discardPull(p[0], p[1]);
            }
        }, "content-import-discard").start();
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
                giveUp(this);
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
        ImportSource source = new ConexCoreImportSource(new ConexCoreDataSource());
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
        NetworkImportCarrier net = carrier instanceof NetworkImportCarrier
                ? (NetworkImportCarrier) carrier : null;
        // The rsync relay opens with the first pulled item and closes with the run on every exit
        // path, which also ends any live pull connection.
        ShareRelay[] relay = {null};
        try {
            drainItems(source, staging, carrier, net, relay);
        } finally {
            if (relay[0] != null) {
                relay[0].close();
            }
        }
    }

    /** The per-item loop of {@link #drain()}. {@code relay} holds the rsync relay the caller closes. */
    private void drainItems(ImportSource source, BoxImportStaging staging, ImportCarrier carrier,
                            @Nullable NetworkImportCarrier net, ShareRelay[] relay) {
        ExportJob job = SESSION.job();
        long lastSampleAt = SystemClock.elapsedRealtime();
        long lastSampleBytes = job.copiedTotal();
        // False once this box refused a pull (an older core): the rest of the run uses the HTTP fetch.
        boolean pullSupported = true;

        for (int i = job.firstPending(); i >= 0; i = job.firstPending()) {
            if (SESSION.isCancelled()) {
                break;
            }
            ItemRef ref = SESSION.refAt(i);
            job.setActive(i);
            SESSION.publish();
            notifyNow();

            String stored = BoxImportStaging.safeName(SESSION.payloadName(i));
            ShareRsyncTarget target = net != null && pullSupported
                    ? ShareRsyncTarget.fromEntry(SESSION.entryJson(i)) : null;
            try {
                boolean pulled = false;
                if (target != null) {
                    // The sender offers rsync: the box pulls the item into place, no staging copy.
                    if (relay[0] == null) {
                        relay[0] = new ShareRelay(getApplicationContext(), SESSION.shareHost(), target.port());
                    }
                    try {
                        pull(source, ref, i, target, relay[0], net, job);
                        pulled = true;
                    } catch (PullUnsupportedException e) {
                        Log.i(TAG, "box cannot pull, using the HTTP fetch: " + e.getMessage());
                        pullSupported = false;
                    }
                }
                if (!pulled) {
                    copyToStaging(carrier, staging, SESSION.payloadName(i), stored, i, job);
                    ingest(source, ref, i, stored);
                }
                job.setDone(i);
            } catch (Exception e) {
                // The staging copy or the ingest failed: mark the item and keep its slot for a retry.
                // Log it: the box side (conex-core) does not surface per-item import errors to the app,
                // so without this the real cause of a failed import is invisible.
                Log.w(TAG, "Import failed for " + ref.app() + "/" + ref.localRef() + ": " + e.getMessage(), e);
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
            if (now - lastSampleAt >= RATE_WINDOW_MS) {
                sampleRate(job, now - lastSampleAt, job.copiedTotal() - lastSampleBytes);
                lastSampleAt = now;
                lastSampleBytes = job.copiedTotal();
            }
            SESSION.publish();
        }

        if (SESSION.isCancelled()) {
            // The running pull was aborted (runBoxOp), so the box no longer writes its partial.
            giveUp(this);
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
                               String storedName, int i, ExportJob job)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long total = 0L;
        long lastPublish = 0L;
        // Trailing-window rate: anchor at this item's copy start and re-anchor once the window is
        // wide enough. A cumulative average (all bytes / whole run elapsed) reads far too low on a
        // single large item that warms up slowly, so sample the recent throughput instead.
        long windowAt = SystemClock.elapsedRealtime();
        long windowBytes = job.copiedTotal();
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
                    long windowMs = now - windowAt;
                    if (windowMs >= RATE_WINDOW_MS) {
                        sampleRate(job, windowMs, job.copiedTotal() - windowBytes);
                        windowAt = now;
                        windowBytes = job.copiedTotal();
                    }
                    SESSION.publish();
                    maybeNotify(now);
                    lastPublish = now;
                }
            }
            out.flush();
        }
    }

    /**
     * Ingest one already-staged item on the box as a long operation (K2GO-376): start it, then poll
     * its status until it is done. Each poll is a SHORT request, so a slow Kolibri import (35-141 s)
     * never times out at the dash-node/nginx relay. The poll IS the liveness signal: there is no
     * wall-clock deadline here (the box's own watchdog ends a stalled ingest). A swept op (status
     * GONE) re-starts exactly once; a second loss fails the item. A box error throws (the
     * out-of-space case as {@link ImportNoSpaceException}, so the run stops with a space message, not
     * a per-item retry). The item stays active while this runs; its bar sits at the copied bytes
     * (the copy is done), as the old blocking ingest showed.
     */
    private void ingest(ImportSource source, ItemRef ref, int i, String storedName) throws Exception {
        String boxPath = BoxImportStaging.boxPath(storedName);
        runBoxOp(source, () -> source.startImport(ref.app(), SESSION.entryJson(i), boxPath), null);
    }

    /**
     * Pull one Share item with rsync (K2GO-577): the box pulls it from the sender's daemon through the
     * loopback relay, straight into place. The op's movedBytes are the bytes on disk, so the item bar
     * moves with the transfer and a Retry starts from the bytes the last attempt kept. The sender gets
     * a progress report every few seconds and a final done / error.
     */
    private void pull(ImportSource source, ItemRef ref, int i, ShareRsyncTarget target, ShareRelay relay,
                      NetworkImportCarrier net, ExportJob job) throws Exception {
        String itemRef = SESSION.payloadName(i);
        long[] last = {0L, 0L};                                   // moved, total
        long[] window = {-1L, 0L, 0L};                            // at, bytes, reportAt
        try {
            runBoxOp(source, () -> source.startPull(ref.app(), SESSION.entryJson(i), LOOPBACK,
                    relay.localPort(), target.module(), SESSION.shareToken()), (moved, total) -> {
                last[0] = moved;
                last[1] = total;
                job.setCopied(i, moved);
                long now = SystemClock.elapsedRealtime();
                if (window[0] < 0) {
                    // Anchor the rate on the first reading: a resumed pull starts at its kept bytes,
                    // which must not count as bytes moved in this window.
                    window[0] = now;
                    window[1] = job.copiedTotal();
                } else if (now - window[0] >= RATE_WINDOW_MS) {
                    sampleRate(job, now - window[0], job.copiedTotal() - window[1]);
                    window[0] = now;
                    window[1] = job.copiedTotal();
                }
                if (now - window[2] >= SENDER_REPORT_INTERVAL_MS) {
                    net.reportProgress(itemRef, moved, total, "running");
                    window[2] = now;
                }
            });
        } catch (PullUnsupportedException e) {
            throw e;   // nothing moved: the item goes over the HTTP fetch, so no error report
        } catch (Exception e) {
            net.reportProgress(itemRef, last[0], last[1], "error");
            throw e;
        }
        net.reportProgress(itemRef, last[1], last[1], "done");
    }

    /** Starts one box long operation; returns its opId. */
    private interface OpStarter {
        String start() throws Exception;
    }

    /** Receives a running op's moved / total bytes on each poll. */
    private interface MovedSink {
        void onMoved(long moved, long total);
    }

    /**
     * Drive one box long operation (import or pull) to its end: poll its status, re-start once if it
     * was swept, and throw on an error. {@code sink} is null for an import of an already-staged item.
     */
    private void runBoxOp(ImportSource source, OpStarter starter, @Nullable MovedSink sink)
            throws Exception {
        String opId = starter.start();
        boolean restarted = false;
        int pollErrors = 0;
        while (true) {
            if (SESSION.isCancelled()) {
                source.abortImport(opId);
                throw new IOException("cancelled");
            }
            sleepPoll();
            ImportStatus st;
            try {
                st = source.importStatus(opId);
            } catch (Exception e) {
                // A swept op returns GONE (handled below), not an exception; this is a transient poll
                // failure (relay blip, busy core). Keep the same op and retry, up to the blip budget,
                // so an ingest of minutes is not lost to one hiccup.
                if (++pollErrors > MAX_POLL_ERRORS) {
                    source.abortImport(opId);
                    throw e;
                }
                continue;
            }
            pollErrors = 0;
            switch (st.state()) {
                case DONE:
                    if (sink != null) {
                        sink.onMoved(st.movedBytes(), st.totalBytes());
                    }
                    // Release the consumed op now (an idempotent drop on the box), so a finished
                    // import does not linger until the TTL sweep. This is import's normal-completion
                    // cleanup, since it has no fetch stream whose close drops the op as export does.
                    source.abortImport(opId);
                    return;
                case ERROR:
                    if (st.noSpace()) {
                        throw new ImportNoSpaceException(st.error() != null && !st.error().isEmpty()
                                ? st.error() : "no space left on the device");
                    }
                    throw new Exception(st.error() != null && !st.error().isEmpty()
                            ? st.error() : "the box could not import this item");
                case GONE:
                    // The op was swept (e.g. a core restart). Re-start once; a second loss fails it.
                    if (restarted) {
                        throw new Exception("the import operation was lost");
                    }
                    restarted = true;
                    opId = starter.start();
                    break;
                case RUNNING:
                default:
                    if (sink != null) {
                        sink.onMoved(st.movedBytes(), st.totalBytes());
                    }
                    // Keep the session fresh so the UI shows the item is still being imported.
                    SESSION.publish();
                    maybeNotify(SystemClock.elapsedRealtime());
                    break;
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

    // ---- helpers -------------------------------------------------------------------------------

    private void sampleRate(ExportJob job, long dtMs, long dBytes) {
        if (dtMs <= 0) {
            return;
        }
        long speed = Math.max(0L, dBytes * 1000L / dtMs);
        long eta = DownloadEta.secondsRemaining(job.copiedTotal(), job.plannedTotal(), speed);
        SESSION.setRate(speed, eta);
    }

    private void finishWithError(String message) {
        SESSION.fail(message);
        stopForeground(true);
        stopSelf();
    }

    private static boolean isNoSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            // Structural signal: the core mapped an out-of-space ingest to 507, which the import
            // source rethrows as this typed exception. Preferred over the prose scan below, which
            // still catches a local staging-copy ENOSPC (a raw IOException, never through the core).
            if (t instanceof ImportNoSpaceException) {
                return true;
            }
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
