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
 *               BOOT HANDOFF (ADFA-4957 review #2): the service does NOT boot the environment. Like
 *               InstallService (whose server restart is owned by the install index, not the service),
 *               DeepOpService leaves the environment stopped and the hosting Activity boots it via
 *               serverController.startEnvironment() on return / next launch — so the service and
 *               ServerController never both own the proot container.
 *
 *               Job shapes:
 *                 BACKUP  (EXTRA_URI = SAF dest)   : stop services -> stream tar|gzip -> post terminal.
 *                 RESTORE (EXTRA_URI = SAF source) : stage the file -> stop services -> extract -> terminal.
 *               K2GO-372: the caller now only shows the destructive confirm (which needs the file's name,
 *               not its bytes); every read of the archive belongs to the service, so it is measured, it
 *               survives backgrounding, and the user can walk away as soon as they have answered.
 *               Backup is cancellable from the notification (read-only); restore is not (hard gate).
 * ============================================================================
 */
package org.appdevforall.k2go.deepop;

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

import org.appdevforall.k2go.InstallGuard;
import org.appdevforall.k2go.R;
import org.appdevforall.k2go.TarExtractor;
import org.appdevforall.k2go.backup.domain.BackupEngine;
import org.appdevforall.k2go.deploy.data.RootfsArchiveValidator;
import org.appdevforall.k2go.deploy.data.RootfsManifest;
import org.appdevforall.k2go.env.EnvironmentControl;
import org.appdevforall.k2go.env.EnvironmentLock;
import org.appdevforall.k2go.redesign.LibraryActivity;
import org.appdevforall.k2go.util.AppExecutors;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class DeepOpService extends Service {

    private static final String TAG = "IIAB-DeepOpService";
    private static final String CHANNEL_ID = "deepop_channel";
    private static final int NOTIFICATION_ID = 7;

    public static final String ACTION_BACKUP = "org.iiab.controller.DEEPOP_BACKUP";
    public static final String ACTION_RESTORE = "org.iiab.controller.DEEPOP_RESTORE";
    public static final String ACTION_CANCEL = "org.iiab.controller.DEEPOP_CANCEL";
    // K2GO-384: the pausable-copy handshake -- Cancel pauses the copy, then the confirm dialog resolves it.
    public static final String ACTION_RESUME = "org.iiab.controller.DEEPOP_RESUME";
    public static final String ACTION_CANCEL_CONFIRM = "org.iiab.controller.DEEPOP_CANCEL_CONFIRM";
    public static final String ACTION_FORCE_CANCEL = "org.iiab.controller.DEEPOP_FORCE_CANCEL";   // K2GO-384: acknowledged cancel DURING extract
    public static final String EXTRA_URI = "uri";     // backup: SAF dest; restore: SAF source

    /** K2GO-372: a restore is one run of three passes — stage the file, verify it, extract it — so they
     *  share one bar instead of each filling it and sending it back to zero. */
    private static final int RESTORE_PASSES = 3;
    private static final int COPY_PASS = 0;
    private static final int VERIFY_PASS = 1;
    private static final int EXTRACT_PASS = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private volatile boolean started = false;
    private volatile boolean finished = false;   // notification teardown reached
    private volatile boolean done = false;       // terminal reached (cancel OR natural) — clean up once
    private EnvironmentLock.Owner owner;
    private String stepText = "";
    /** K2GO-384 (ADR-5343c): a CONFIRMED abort in the SAFE zone (copy / verify / the verify->extract
     *  boundary). The copy loop and TarExtractor's verify + boundary read it and abort with the rootfs
     *  untouched. It is NEVER read by the extract feeder -- a safe-zone abort must not be able to tear the
     *  rootfs (that is {@link #forceExtractCancel}'s job). Set only while currentCancelKind == CANCELLABLE. */
    private final java.util.concurrent.atomic.AtomicBoolean cancelBeforeExtract = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** K2GO-384 (ADR-5343c): an ACKNOWLEDGED destructive kill, read ONLY by the extract feeder (past the
     *  point of no return). Set only while currentCancelKind == DESTRUCTIVE. Kept separate from
     *  cancelBeforeExtract so the two cancel intents can never alias across the verify->extract boundary. */
    private final java.util.concurrent.atomic.AtomicBoolean forceExtractCancel = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** K2GO-384: the owner of "one verify+extract pass at a time". Set when a pass starts, cleared at every
     *  pass outcome (complete / error / cancelled / held). Guards the re-callable attemptVerifyAndExtract so a
     *  "Keep restoring" cannot start a second pass over the same temp while one is running. */
    private volatile boolean passRunning = false;
    /** K2GO-384: cancel for the (read-only, single-pass) BACKUP. streamBackup's read loop reads it and kills
     *  tar. Always safe (nothing on the device is touched); the terminal removes the incomplete SAF file. */
    private final java.util.concurrent.atomic.AtomicBoolean backupCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** K2GO-384: true while the user is deciding on a paused COPY (Cancel pressed). The copy loop blocks on
     *  it; ACTION_RESUME clears it (continue), ACTION_CANCEL_CONFIRM sets cancelBeforeExtract and clears it
     *  (abort). Only the copy is pausable -- verify/extract are external tar processes. */
    private final java.util.concurrent.atomic.AtomicBoolean pauseRequested = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** K2GO-384: what cancelling the current restore pass means -- set from the real phase, published in
     *  DeepOpState so the UI shows the right dialog without guessing from the step text. */
    private volatile DeepOpState.CancelKind currentCancelKind = DeepOpState.CancelKind.NONE;
    /** K2GO-384: non-null while a verify pass is HELD for an undecided Cancel -- carries the copied temp so
     *  ACTION_RESUME can re-run verify+extract on it (no re-copy) and ACTION_CANCEL_CONFIRM can delete it. */
    private volatile String heldTempPath = null;

    /** Start a backup: stream a gzip'd tar of the rootfs to the SAF destination. */
    public static void startBackup(Context ctx, Uri dest) {
        Intent i = new Intent(ctx, DeepOpService.class).setAction(ACTION_BACKUP).putExtra(EXTRA_URI, dest.toString());
        startFg(ctx, i);
    }

    /** Start a restore: stage the picked archive, then extract it over the rootfs (destructive). */
    public static void startRestore(Context ctx, Uri source) {
        Intent i = new Intent(ctx, DeepOpService.class).setAction(ACTION_RESTORE).putExtra(EXTRA_URI, source.toString());
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
        if (ACTION_CANCEL.equals(action)) { if (started) cancel(); else stopSelf(); return START_NOT_STICKY; }
        // K2GO-384: resolve a paused COPY -- resume (keep restoring) or confirm the cancel (abort).
        if (ACTION_RESUME.equals(action)) {
            // K2GO-384: keep restoring. A paused COPY just resumes; a HELD verify re-runs on the same temp
            // (verify + extract, no re-copy).
            pauseRequested.set(false);
            final String held = heldTempPath;
            if (held != null) { heldTempPath = null; attemptVerifyAndExtract(held); }
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL_CONFIRM.equals(action)) {
            // K2GO-384 (ADR-5343c): the reversible abort applies ONLY in the safe zone. Guarding on the
            // service's own currentCancelKind (not the UI's lagging copy) means a confirm that races past the
            // verify->extract boundary is ignored here -- the extract simply finishes -- rather than reaching
            // the feeder. The system is never torn by a "system unchanged" confirm.
            if (started && currentCancelKind == DeepOpState.CancelKind.CANCELLABLE) {
                cancelBeforeExtract.set(true);   // a paused COPY bails on this; stageThenRestore then cleans up
                pauseRequested.set(false);
                final String held = heldTempPath;
                if (held != null) { heldTempPath = null; main.post(() -> endRestore(held, false, "")); }
            }
            return START_NOT_STICKY;
        }
        // K2GO-384 (ADR-5343c): acknowledged cancel DURING extract -- kill tar mid-write via its OWN token.
        // Guarded on DESTRUCTIVE so it acts only past the point of no return; InstallGuard stays planted so
        // recovery reinstalls.
        if (ACTION_FORCE_CANCEL.equals(action)) {
            if (started && currentCancelKind == DeepOpState.CancelKind.DESTRUCTIVE) forceExtractCancel.set(true);
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;   // one op per service instance
        started = true;

        owner = ACTION_RESTORE.equals(action) ? EnvironmentLock.Owner.RESTORE : EnvironmentLock.Owner.BACKUP;
        final boolean restoring = owner == EnvironmentLock.Owner.RESTORE;
        // K2GO-372: a restore now begins by staging the picked file, so it opens on a different step.
        // Taking the lock here also sends the box down (RESTORE is a STOPPED-class holder, so desired
        // goes DOWN on the next reconciler tick) — which is what the confirm the user just answered
        // promised, and it means the explicit stop below has little left to wait for.
        stepText = getString(restoring ? R.string.k2go_br_status_copying : R.string.k2go_br_status_stopping);
        startForeground(NOTIFICATION_ID, buildNotification(stepText));
        acquireHardwareLocks();

        EnvironmentLock.acquire(this, owner);
        // Indeterminate until a pass reports a percent it measured: a source whose size the provider
        // will not tell us has no honest bar, and one pinned at 0 reads as a stall.
        post(stepText, -1);

        final String uriStr = intent.getStringExtra(EXTRA_URI);
        if (restoring) stageThenRestore(uriStr);
        else EnvironmentControl.stop(this, this::log, () -> runBackup(uriStr));
        return START_NOT_STICKY;
    }

    // ---- BACKUP (read-only) ----
    private void runBackup(final String uriStr) {
        if (done) return;
        currentCancelKind = DeepOpState.CancelKind.CANCELLABLE;   // K2GO-384: backup is cancellable throughout (read-only)
        setStep(getString(R.string.k2go_br_status_backing), -1);
        AppExecutors.get().io().execute(() -> {
            boolean ok;
            try (OutputStream os = getContentResolver().openOutputStream(Uri.parse(uriStr))) {
                // K2GO-384: byte-accurate progress + ETA; streamBackup reports from its tar-stdout read loop.
                // post() is thread-safe. backupCancelled lets the on-screen (or notification) Cancel stop tar.
                ok = os != null && BackupEngine.streamBackup(this, os,
                        (percent, etaSeconds) -> post(getString(R.string.k2go_br_status_backing), percent, etaSeconds),
                        backupCancelled);
            } catch (Exception e) {
                Log.e(TAG, "backup failed", e);
                ok = false;
            }
            final boolean success = ok;
            main.post(() -> {
                if (done) return;
                if (!success) {
                    // K2GO-384: an unfinished backup left an incomplete/damaged .tar.gz at the SAF destination.
                    // Removing it is the default (no prompt) -- a partial gzip'd tar is useless and unresumable.
                    deleteBackupDoc(uriStr);
                    if (backupCancelled.get()) { finishCancelled(); return; }   // user cancel -> bifurcation
                }
                finishJob(success, getString(R.string.k2go_br_backup_done), getString(R.string.k2go_br_backup_failed));
            });
        });
    }

    /** K2GO-384: remove the incomplete/damaged backup file a cancelled or failed run left at the SAF
     *  destination. Best-effort -- a document picked via CREATE_DOCUMENT supports delete; if not, leave it. */
    private void deleteBackupDoc(String uriStr) {
        try {
            android.provider.DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(uriStr));
        } catch (Exception e) {
            Log.w(TAG, "could not delete incomplete backup doc: " + e.getMessage());
        }
    }

    // ---- RESTORE (destructive) ----

    /**
     * K2GO-372: copy the picked archive out of the SAF provider, then run the restore.
     *
     * <p>The copy used to run in the fragment, before the confirm, with no progress and no foreground
     * service behind it. It is the same copy; it just belongs here, where it is measured and where
     * leaving the screen cannot strand it. It runs before the damage marker is planted, because nothing
     * is written yet — see the marker comment in {@link #runRestore}.
     */
    private void stageThenRestore(final String uriStr) {
        final File temp = new File(getCacheDir(), "restore.tar.gz");
        final Uri src = Uri.parse(uriStr);
        AppExecutors.get().io().execute(() -> {
            final long compressed = sourceSize(src);
            String failure = rejectBeforeCopy(src);
            if (failure == null) {
                failure = rejectForSpace(compressed);
            }
            if (failure == null) {
                failure = stageArchive(src, temp, compressed);
            }
            final String outcome = failure;
            main.post(() -> {
                if (done) {
                    // Unreachable while a restore is uncancellable, but a staged archive is gigabytes:
                    // whoever reaches a terminal first must not leave it in the cache.
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                    return;
                }
                if (outcome != null) {
                    endRestore(temp.getAbsolutePath(), false, outcome);
                    return;
                }
                currentCancelKind = DeepOpState.CancelKind.CANCELLABLE;   // K2GO-384: stopping -- cancellable
                setStep(getString(R.string.k2go_br_status_stopping), -1);
                EnvironmentControl.stop(this, this::log, () -> runRestore(temp.getAbsolutePath()));
            });
        });
    }

    /**
     * K2GO-372: judge the picked file before paying to copy it.
     *
     * <p>The identity manifest is the archive's first member, so this reads a few KB of the SAF stream
     * and refuses a wrong-ABI or non-rootfs file in about a second, instead of after a multi-gigabyte
     * copy the user then watches be thrown away. An archive with no manifest is not judged here — the
     * extractor's structural check still has the last word, on the copy.
     *
     * @return the reason to show, or {@code null} when nothing here rejects it.
     */
    private String rejectBeforeCopy(Uri src) {
        RootfsManifest.Identity id;
        try (InputStream raw = getContentResolver().openInputStream(src)) {
            if (raw == null) return getString(R.string.k2go_br_restore_unreadable);
            // A SAF uri carries no filename to read an extension from, so ask the bytes: gzip's two
            // magic bytes. Honest for any source, and it drops the naming assumption the old
            // path-based caller could afford.
            java.io.BufferedInputStream buf = new java.io.BufferedInputStream(raw);
            buf.mark(2);
            final boolean isGzip = buf.read() == 0x1f && buf.read() == 0x8b;
            buf.reset();
            id = RootfsManifest.read(buf, isGzip);
        } catch (Exception e) {
            Log.w(TAG, "Could not read the picked file's manifest", e);
            return null;   // unreadable here is not a verdict; the copy below will say so properly
        }
        return RootfsArchiveValidator.rejectionMessage(this, RootfsArchiveValidator.identityRejection(id));
    }

    /** The picked file's length, or {@code -1} when the provider will not report one. */
    private long sourceSize(Uri src) {
        try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(src, "r")) {
            return pfd == null ? -1L : pfd.getStatSize();
        } catch (Exception ignored) {
            return -1L;   // the copy below is what has to work; the size only sharpens it
        }
    }

    /**
     * K2GO-372: refuse before copying when the copy and the tree it expands into plainly cannot both
     * fit. The guard was only ever asked about the expansion, and only once the copy was already on
     * disk, so a device with room for neither filled up mid-copy and reported it as an unreadable file.
     *
     * <p>{@link #runRestore} keeps the authoritative check on the real staged file — this one cannot run
     * when the provider will not report a length, and free space can change in between.
     *
     * @return the reason to show, or {@code null} when there is room (or nothing to judge with).
     */
    private String rejectForSpace(long compressedBytes) {
        if (compressedBytes <= 0L) {
            return null;
        }
        org.appdevforall.k2go.storage.FreeSpacePreflight.Result pf =
                org.appdevforall.k2go.storage.FreeSpacePreflight.check(this,
                        org.appdevforall.k2go.storage.SpaceEstimate.peakForRestore(compressedBytes));
        return pf.ok ? null : noStorageMessage(pf);
    }

    /** One shape for "it does not fit", used before the copy and again before the extraction. */
    private String noStorageMessage(org.appdevforall.k2go.storage.FreeSpacePreflight.Result pf) {
        return getString(R.string.install_error_no_storage) + " ("
                + org.appdevforall.k2go.util.ByteFormatter.toHuman(pf.amountToReport()) + ")";
    }

    /**
     * Copy {@code src} to {@code temp}, reporting percent as it goes.
     *
     * @param size the source's length, or {@code -1}: an unknown length is copied with an indeterminate
     *             percent rather than a made-up one.
     * @return {@code null} on success, otherwise the message to show the user.
     */
    private String stageArchive(Uri src, File temp, long size) {
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream out = new java.io.FileOutputStream(temp)) {
            if (in == null) throw new IOException("The picked file could not be opened");
            byte[] buf = new byte[1 << 16];
            long copied = 0L, lastEmit = 0L;
            final long startMs = android.os.SystemClock.elapsedRealtime();
            currentCancelKind = DeepOpState.CancelKind.CANCELLABLE;   // K2GO-384: copy -- cancellable (pauses here)
            int n;
            while ((n = in.read(buf)) != -1) {   // a 0-length read is not end of stream
                // K2GO-384: Cancel during the copy PAUSES here (our native loop). Block while paused; a
                // confirmed cancel sets cancelBeforeExtract and we bail (endRestore deletes the temp, the
                // rootfs untouched); resume just continues. The empty return is never shown -- a cancel
                // returns the screen to the bifurcation.
                if (cancelBeforeExtract.get()) return "";
                while (pauseRequested.get() && !cancelBeforeExtract.get()) {
                    try { Thread.sleep(120L); } catch (InterruptedException e) { return ""; }
                }
                if (cancelBeforeExtract.get()) return "";
                out.write(buf, 0, n);
                copied += n;
                long now = android.os.SystemClock.elapsedRealtime();
                if (size > 0L && now - lastEmit >= 200L) {
                    lastEmit = now;
                    final int pct = org.appdevforall.k2go.deploy.domain.ExtractProgress
                            .unifiedPercent(org.appdevforall.k2go.deploy.domain.ExtractProgress
                                    .percent(copied, size), COPY_PASS, RESTORE_PASSES);
                    // K2GO-384: the copy is the restore's first pass; give it the same live per-pass ETA
                    // the extract/verify passes already report (TarExtractor computes theirs the same way).
                    final long rate = org.appdevforall.k2go.system.domain.TransferRate
                            .perSecond(copied, now - startMs);
                    final long eta = org.appdevforall.k2go.deploy.domain.ExtractProgress
                            .etaSeconds(copied, size, rate);
                    main.post(() -> setStep(getString(R.string.k2go_br_status_copying), pct, eta));
                }
            }
            out.flush();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Staging the picked archive failed", e);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return getString(R.string.k2go_br_restore_unreadable);
        }
    }

    private void runRestore(final String path) {
        if (done) return;
        // ADFA-5105: the restore overwrites the rootfs with the backup's contents. Refuse before
        // anything is touched when the uncompressed backup plainly won't fit (fail-safe on UNKNOWN
        // free space too). The "needed" is estimated from the backup archive already on disk.
        // K2GO-372: the second of two gates. rejectForSpace() asked the same question before the copy,
        // where the copy still counted toward what has to fit; here the copy is already spent, so the
        // free space it reads is that much lower and the expansion alone is what is left to cover —
        // the same inequality, asked again in case the length was unknown or the disk moved under us.
        long needed = org.appdevforall.k2go.storage.SpaceEstimate.fromCompressed(new File(path).length());
        org.appdevforall.k2go.storage.FreeSpacePreflight.Result pf =
                org.appdevforall.k2go.storage.FreeSpacePreflight.check(this, needed);
        if (!pf.ok) {
            endRestore(path, false, noStorageMessage(pf));
            return;
        }
        // ADFA-5070: stop the downloads before the tar starts overwriting the rootfs
        // they were writing into. The pending orders are not touched yet — a tar that
        // will not open leaves the system intact, and discarding them here would have
        // thrown away what the user asked for over an operation that never happened.
        org.appdevforall.k2go.system.data.ContentStateInvalidator.replacementStarting(this,
                org.appdevforall.k2go.system.domain.SystemReplacement.Cause.RESTORE);
        attemptVerifyAndExtract(path);
    }

    /**
     * K2GO-384: run (or RE-run) the verify + extract on the already-staged {@code path}. Re-callable so a
     * "Keep restoring" after a Cancel raised during verify re-runs just this pass -- verify (`tar -t`) then
     * extract -- on the copied temp, WITHOUT re-copying. The copy above is never repeated.
     */
    private void attemptVerifyAndExtract(final String path) {
        if (done || passRunning) return;   // K2GO-384: one verify+extract pass at a time (service is the owner)
        passRunning = true;
        currentCancelKind = DeepOpState.CancelKind.CANCELLABLE;   // K2GO-384: verify -- cancellable (kill + hold)
        setStep(getString(R.string.k2go_br_status_checking), 0);
        final File destParent = new File(getFilesDir(), "rootfs");
        new TarExtractor().startExtraction(this, path, destParent.getAbsolutePath(), true,
                cancelBeforeExtract, pauseRequested, forceExtractCancel,
                new TarExtractor.ExtractionListener() {
                    @Override public void onComplete(String destDir) { passRunning = false; main.post(() -> endRestore(path, true, null)); }
                    @Override public void onError(String error) {
                        passRunning = false;
                        // K2GO-384 (ADR-5343c): "known damage" is owned by "the extract began and did not
                        // complete", not by the cancel button. isLive == true means onExtractStarting planted
                        // the marker, i.e. the rootfs was being written and is now torn (a force-cancel OR a
                        // real mid-write failure). Mark it DAMAGED so isLive drops (the k2go_busy_install gate
                        // lifts, unblocking a fresh restore) and desired stays DOWN (isSystemInstalled=false, no
                        // flap on the torn base); isInterrupted stays true so recovery owns it next launch/return.
                        final boolean torn = InstallGuard.isLive(DeepOpService.this);
                        if (torn) InstallGuard.markDamaged(DeepOpService.this);
                        // forced only picks the MESSAGE: our own acknowledged kill -> the damaged line; a real
                        // failure keeps its diagnostic (the system is still marked damaged above).
                        final boolean forced = forceExtractCancel.get();
                        main.post(() -> endRestore(path, false,
                                forced ? getString(R.string.k2go_br_restore_damaged) : error));
                    }
                    @Override public void onProgress(String line) { }
                    // K2GO-384: the point of no return -- fired once at the verify->extract boundary, BEFORE
                    // the first write, on the extractor thread. Plant the destructive marker here (decoupled
                    // from the progress emits) so an ungraceful kill during the write is recovered next launch.
                    @Override public void onExtractStarting() {
                        currentCancelKind = DeepOpState.CancelKind.DESTRUCTIVE;   // K2GO-384: past the point of no return
                        InstallGuard.begin(DeepOpService.this);
                    }
                    // K2GO-384: cancelled before any write (Option B) -- the rootfs is untouched and no marker
                    // was planted. Delete the staged temp and end (the screen returns to the bifurcation, so
                    // no terminal message is shown -- an empty reason keeps endRestore's cleanup path).
                    @Override public void onCancelled() {
                        passRunning = false;
                        main.post(() -> endRestore(path, false, ""));
                    }
                    // K2GO-384: Cancel pressed during verify, still undecided -- `tar -t` was killed but the
                    // copied temp is intact. HOLD: keep the temp and wait. ACTION_RESUME re-runs this pass on
                    // the same temp (no re-copy); ACTION_CANCEL_CONFIRM aborts (deletes the temp, bifurcation).
                    // passRunning drops here too: the extractor thread has exited, so a Keep may start a fresh pass.
                    @Override public void onHeldForDecision() {
                        passRunning = false;
                        main.post(() -> heldTempPath = path);
                    }

                    /**
                     * K2GO-372: a restore reads the whole archive three times — the copy above, the
                     * safety listing pass and the extraction pass — and reported all of it as an
                     * indeterminate spinner, so a multi-minute wait was indistinguishable from a hang.
                     * The byte-based bar already existed for the rootfs install (ADFA-5118); this
                     * consumes the same callback and maps every pass onto one 0-100 bar, so no pass
                     * can look like a whole run.
                     */
                    @Override
                    public void onExtractPhase(TarExtractor.Phase phase, int passPercent,
                                               long etaSeconds, String line) {
                        final boolean extracting = phase == TarExtractor.Phase.EXTRACT;
                        // K2GO-384: the destructive marker (InstallGuard.begin) is now planted in
                        // onExtractStarting() -- once, at the verify->extract boundary, before the first
                        // write and decoupled from these progress emits -- so it no longer rides on the
                        // first extract progress callback.
                        final int unified = org.appdevforall.k2go.deploy.domain.ExtractProgress
                                .unifiedPercent(passPercent, extracting ? EXTRACT_PASS : VERIFY_PASS,
                                        RESTORE_PASSES);
                        final String label = getString(extracting
                                ? R.string.k2go_br_status_restoring
                                : R.string.k2go_br_status_checking);
                        // K2GO-384: pass through the per-pass ETA TarExtractor already computed (was dropped).
                        main.post(() -> setStep(label, unified, etaSeconds));
                    }
                });
    }

    /** @param failMessage why it failed, when the failure knows; {@code null} falls back to the generic. */
    private void endRestore(String tempPath, boolean ok, String failMessage) {
        if (ok) {
            // ADFA-5070: the rootfs really was replaced, and the content that arrived
            // is the backup's — so the orders placed against the old one are stale.
            org.appdevforall.k2go.system.data.ContentStateInvalidator.replacementSucceeded(this,
                    org.appdevforall.k2go.system.domain.SystemReplacement.Cause.RESTORE);
        }
        File temp = new File(tempPath);
        if (temp.exists()) temp.delete();
        // K2GO-384 (ADR-5343c): an empty reason means a user cancel in the SAFE zone (copy/verify) -- terminal
        // but not a failure. Route it to a CANCELLED terminal (the screen returns to the bifurcation, decided
        // by phase so it survives a config change), not a "Restore failed" screen. A real failure or the
        // acknowledged-damaged message (both non-empty) goes to FAILED.
        if (!ok && (failMessage == null || failMessage.trim().isEmpty())) {
            finishCancelled();
            return;
        }
        // K2GO-372: the extractor already produces the exact reason (wrong architecture, not a rootfs);
        // it used to be discarded here and replaced by a generic "Restore failed".
        finishJob(ok, getString(R.string.k2go_br_restore_done),
                failMessage == null || failMessage.trim().isEmpty()
                        ? getString(R.string.k2go_br_restore_failed) : failMessage);
    }

    // ---- single terminal path (natural completion OR cancel), run once ----
    /**
     * The service does NOT boot the environment (review #2): the hosting Activity owns that
     * (serverController.startEnvironment on return / next launch), so the service and ServerController
     * never both own the container. A CLEAN restore clears InstallGuard; a FAILED restore leaves it set
     * so next-launch recovery repairs the torn rootfs.
     */
    private void finishJob(boolean ok, String okMsg, String failMsg) {
        if (done) return;
        done = true;
        if (owner == EnvironmentLock.Owner.RESTORE && ok) InstallGuard.end(this);
        // ADFA-5343 (Phase 3): set desired=UP and drop the lock; the reconciler brings the box back on
        // holder==NONE via its one actuator (the host Activity used to do it). A failed restore leaves
        // InstallGuard set, so desired stays DOWN (healthy=false) and the box is not booted onto a
        // half-applied rootfs — the recovery path owns that, exactly as before.
        new org.appdevforall.k2go.Preferences(this).setWatchdogEnable(true);
        EnvironmentLock.release(this);
        if (ok) DeepOpProgressRepository.get().postSuccess(owner, okMsg);
        else DeepOpProgressRepository.get().postFailed(owner, failMsg);
        teardown();
    }

    /**
     * K2GO-384 (ADR-5343c): terminal for a user cancel in the safe zone (copy/verify). Same teardown as
     * finishJob's failed path -- re-enable desired and drop the lock -- but posts a CANCELLED phase (not
     * FAILED) and never touches InstallGuard: a pre-destructive cancel planted no marker, so there is nothing
     * to end and nothing damaged.
     */
    private void finishCancelled() {
        if (done) return;
        done = true;
        new org.appdevforall.k2go.Preferences(this).setWatchdogEnable(true);
        EnvironmentLock.release(this);
        DeepOpProgressRepository.get().postCancelled(owner);
        teardown();
    }

    /**
     * Cancel (from the on-screen button's confirm, or the notification action — BACKUP only). Restore has no
     * Cancel action here (it takes the CANCELLABLE hold / DESTRUCTIVE force-cancel paths below).
     */
    private void cancel() {
        if (owner == EnvironmentLock.Owner.BACKUP) {
            // K2GO-384: stop the backup for real -- the read loop sees this, kills tar, and the terminal
            // removes the incomplete file (was: mark done + FAILED, which left tar running and the file behind).
            backupCancelled.set(true);
            return;
        }
        // K2GO-384: a Cancel on any pre-destructive pass (CANCELLABLE) HOLDS the run and waits for the
        // confirm dialog. pauseRequested pauses the copy loop AND blocks the verify->extract boundary, so
        // whether we are mid-copy or mid-verify nothing advances into the destructive extract while the user
        // decides. ACTION_RESUME continues; ACTION_CANCEL_CONFIRM aborts. DESTRUCTIVE (extract) is not
        // handled here -- it takes the acknowledged force-cancel path.
        if (currentCancelKind == DeepOpState.CancelKind.CANCELLABLE) {
            pauseRequested.set(true);
        }
    }

    private void teardown() {
        finished = true;
        releaseHardwareLocks();
        stopForeground(true);
        stopSelf();
    }

    // ---- progress ----
    private void setStep(String step, int percent) { setStep(step, percent, -1L); }

    /** K2GO-384: overload carrying the current pass's ETA (seconds; {@code -1} = unknown/hidden). */
    private void setStep(String step, int percent, long etaSeconds) {
        stepText = step;
        updateNotification(step);
        post(step, percent, etaSeconds);
    }

    private void post(String step, int percent) { post(step, percent, -1L); }

    private void post(String step, int percent, long etaSeconds) {
        DeepOpProgressRepository.get().postRunning(owner, step, percent, etaSeconds, currentCancelKind);
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
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.deepop_notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true);
        // Cancel only for backup (read-only). Restore is destructive → uncancellable (hard gate).
        if (owner == EnvironmentLock.Owner.BACKUP) {
            Intent cancel = new Intent(this, DeepOpService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelIntent = PendingIntent.getService(this, 1, cancel,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(0, getString(R.string.deepop_notif_cancel), cancelIntent);
        }
        return b.build();
    }

    private void updateNotification(final String text) {
        if (finished) return;
        main.post(() -> { if (!finished) startForeground(NOTIFICATION_ID, buildNotification(text)); });
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
