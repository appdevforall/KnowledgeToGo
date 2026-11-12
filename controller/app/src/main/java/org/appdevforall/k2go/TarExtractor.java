/*
 * ============================================================================
 * Name        : TarExtractor.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Native wrapper for tar archive extraction with Java GZIP Pipe
 * ============================================================================
 */

package org.appdevforall.k2go;

import org.appdevforall.k2go.deploy.domain.ArchiveEntry;
import org.appdevforall.k2go.deploy.domain.ExtractProgress;
import org.appdevforall.k2go.deploy.domain.TarVerboseLine;
import org.appdevforall.k2go.system.domain.TransferRate;
import org.appdevforall.k2go.system.domain.WindowedRate;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class TarExtractor {
    private static final String TAG = "IIAB-TarExtractor";
    private Process tarProcess;
    private boolean isExtracting = false;

    /** K2GO-555: a wide trailing window for the extract rate. tar names a member at its start, so the
     *  per-member byte delta is bursty; a wide window averages the burst into honest throughput and
     *  pairs with the presentation EtaSmoother's dwell. */
    private static final long EXTRACT_RATE_WINDOW_MS = 5_000L;

    /** K2GO-555: the archive listing: member paths (for the traversal and rootfs guards) plus the
     *  per-path uncompressed size and their total, so the extract phase can weight progress by bytes. */
    private static final class Listing {
        final List<String> paths;
        final Map<String, Long> sizeByPath;
        final long totalBytes;
        Listing(List<String> paths, Map<String, Long> sizeByPath, long totalBytes) {
            this.paths = paths;
            this.sizeByPath = sizeByPath;
            this.totalBytes = totalBytes;
        }
    }

    /** ADFA-5118: the two passes the app makes over the same .tar.gz — the safety/listing pass
     *  ({@code VERIFY}, {@link #listEntries}) and the extraction pass ({@code EXTRACT}). */
    public enum Phase { VERIFY, EXTRACT }

    public interface ExtractionListener {
        void onComplete(String destDir);

        void onError(String error);

        /** A streamed line of extraction output (verbose tar). Default no-op. */
        default void onProgress(String line) { }

        /**
         * ADFA-4915: determinate extraction progress. {@code percent} is clamped to
         * [0,99] while extracting and set to 100 exactly once on completion. K2GO-555:
         * {@code done} / {@code total} are uncompressed bytes when the listing has sizes,
         * else archive-member counts; {@code line} is the current verbose tar line (may be
         * empty). Consumers use {@code percent}; the counts are advisory. Default no-op.
         */
        default void onProgress(int percent, long done, long total, String line) { }

        /**
         * ADFA-5118: byte-based progress for the unified verify+extract bar (gzip path only).
         * Both passes stream the whole .tar.gz, so progress is measured by compressed bytes
         * consumed against the archive size on disk. {@code passPercent} is 0..99 within the
         * given {@code phase}; {@code etaSeconds} is that phase's live estimate, or -1 when not
         * yet estimable; {@code line} is the current member (may be empty). Default no-op, so
         * callers on the non-gzip path (e.g. .xz restores) keep the member-count behavior.
         */
        default void onExtractPhase(Phase phase, int passPercent, long etaSeconds, String line) { }

        /**
         * K2GO-384: the extract phase is about to write its first byte over the destination -- the
         * point of no return. Fires once, after verify and every fail-closed check pass and after the
         * cancel check, BEFORE any write. The restore plants its destructive recovery marker here,
         * decoupled from the progress callbacks. Default no-op.
         */
        default void onExtractStarting() { }

        /**
         * K2GO-384: extraction was cancelled in the pre-destructive zone (copy/verify or the
         * verify->extract boundary) -- nothing was written to the destination. Restore-only; other
         * callers pass no cancel token and never see this. Default no-op.
         */
        default void onCancelled() { }

        /**
         * K2GO-384: the user pressed Cancel DURING verify and is still deciding. The `tar -t` was killed
         * for responsiveness, but nothing was written and the copied archive is intact, so the caller HOLDS
         * (keeps the temp) and offers Keep (re-run this same pass) or Cancel (abort). Restore-only; other
         * callers pass no hold token and never see this. Default no-op.
         */
        default void onHeldForDecision() { }
    }

    /** K2GO-384: thrown when verify is stopped for a still-undecided Cancel -- distinct from a confirmed
     *  cancel so the caller can HOLD (keep the temp) and re-run this pass, rather than abort. */
    private static final class HeldForDecisionException extends RuntimeException { }

    /** ADFA-5118: counts bytes pulled from the underlying (compressed) stream, so the feeders can
     *  measure progress against the archive size on disk without a sidecar. */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        volatile long count = 0L;
        CountingInputStream(java.io.InputStream in) { super(in); }
        @Override public int read() throws java.io.IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    public void startExtraction(Context context, String archivePath, String destDir, ExtractionListener listener) {
        startExtraction(context, archivePath, destDir, false, listener);
    }

    /**
     * @param validateRootfs when true (untrusted import/restore), also require the
     *        archive to look like a rootfs of THIS app's architecture before extracting.
     */
    public void startExtraction(Context context, String archivePath, String destDir, boolean validateRootfs, ExtractionListener listener) {
        startExtraction(context, archivePath, destDir, validateRootfs, null, null, null, 0L, listener);
    }

    /**
     * K2GO-554 D6: the install path for an OFFICIAL, SIGNED download. A {@code trustedTotalBytes}
     * above 0 (the published installed size) means the caller verified the tarball against its signed
     * manifest, so a gzip archive skips the listing pass (the bake gate already checked traversal
     * before signing) and the extract bar runs on the uncompressed bytes fed to tar (K2GO-554 D8). A
     * value of 0, or a non-gzip archive, takes the full, untrusted path.
     */
    public void startExtraction(Context context, String archivePath, String destDir,
                                long trustedTotalBytes, ExtractionListener listener) {
        startExtraction(context, archivePath, destDir, false, null, null, null, trustedTotalBytes, listener);
    }

    /**
     * K2GO-384: as above, plus three tokens with strictly separated zones (ADR-5343c de-aliasing):
     * <ul>
     *   <li>{@code cancelledBeforeExtract} — a confirmed abort in the SAFE zone. Read ONLY during copy/verify
     *       and at the verify->extract boundary; aborts before any byte is written ({@link
     *       ExtractionListener#onCancelled}). It is NOT read once the extract is writing.</li>
     *   <li>{@code pauseAtBoundary} — HOLD at the verify->extract boundary (verify done, nothing written)
     *       until it clears (resume -> extract) or a cancel is confirmed.</li>
     *   <li>{@code forceCancelDuringExtract} — an ACKNOWLEDGED destructive kill. Read ONLY by the extract
     *       feeder (past the point of no return); kills tar mid-write, leaving the rootfs torn. Kept separate
     *       from {@code cancelledBeforeExtract} so a safe-zone abort can never reach the feeder and damage the
     *       system.</li>
     * </ul>
     * {@code null} tokens = never cancellable (the import/install paths).
     */
    public void startExtraction(Context context, String archivePath, String destDir, boolean validateRootfs,
                                java.util.concurrent.atomic.AtomicBoolean cancelledBeforeExtract,
                                java.util.concurrent.atomic.AtomicBoolean pauseAtBoundary,
                                java.util.concurrent.atomic.AtomicBoolean forceCancelDuringExtract,
                                long trustedTotalBytes,
                                ExtractionListener listener) {
        if (isExtracting) return;

        new Thread(() -> {
            isExtracting = true;
            try {
                File destination = new File(destDir);
                if (!destination.exists()) {
                    destination.mkdirs();
                }
                // ADFA-4544: record free space + archive size for failure diagnostics.
                final long freeBefore = freeBytes(destination);
                Log.d(TAG, "Extract start: freeBytes=" + freeBefore
                        + ", archiveCompressed=" + new File(archivePath).length() + ", dest=" + destDir);
                // 1. DYNAMIC BINARY SELECTION
                File staticTar = new File(context.getApplicationInfo().nativeLibraryDir, "libtar.so");
                String tarBinary = staticTar.exists() ? staticTar.getAbsolutePath() : "/system/bin/tar";
                Log.d(TAG, "Using tar binary: " + tarBinary);

                boolean isGzip = archivePath.toLowerCase().endsWith(".gz");
                // K2GO-554 D6: trust skips the listing only on the gzip path, where the feeder drives the
                // bar, and never when the caller asked for rootfs validation, which needs the listing.
                // Any other combination takes the full path (fail closed).
                final boolean trusted = trustedTotalBytes > 0L && isGzip && !validateRootfs;

                // D11: refuse path-traversal. List the archive members first and
                // bail out (without extracting anything) if any member is absolute
                // or climbs out of destDir via "..". An imported/restored backup is
                // untrusted, so this runs for every extraction.
                // K2GO-372: identity before the listing pass. The listing below is irreducible —
                // the traversal guard has to see every member name — but it costs a full pass over
                // the archive, and a wrong-ABI or non-rootfs file used to be rejected only after
                // paying for it. The manifest that answers identity is packed first, so reading it
                // here refuses the same files in about a second, and leaves the long pass for
                // archives that are actually going to be extracted.
                // K2GO-372: DeepOpService runs this same check on the picked stream before it copies,
                // purely to avoid paying for a copy it will throw away. This one is not a duplicate of
                // it: this is the fail-closed gate every caller shares (import, install, restore), it
                // judges the artifact actually about to be extracted, and both delegate to the one rule
                // in RootfsIdentity. Deleting either does not make the other cover it.
                // K2GO-554 D6: a trusted (officially signed, pre-verified) download skips the listing
                // pass. The bake gate already ran the path-traversal check on this exact tarball before
                // it was signed, and the install path never validates ABI, so the on-device listing would
                // only pay a second full gzip decompression for nothing. The extract bar is driven by the
                // feeder bytes (D8) against the uncompressed stream size.
                final Listing listing;
                final long totalMembers;
                final long totalBytes;
                final boolean byteMode;
                if (trusted) {
                    listing = null;
                    totalMembers = 0L;
                    // K2GO-554 D8: the honest denominator is the uncompressed-stream size, which is exactly
                    // what the feeder reads out of the gzip. The ISIZE trailer gives it without
                    // decompression, but modulo 2^32; the published total fixes the wraps (full tiers
                    // are above 4 GiB).
                    totalBytes = ExtractProgress.trustedStreamTotal(
                            gzipUncompressedSize(archivePath), trustedTotalBytes);
                    byteMode = true;
                } else {
                    if (validateRootfs) {
                        org.appdevforall.k2go.deploy.data.RootfsArchiveValidator.Result early =
                                org.appdevforall.k2go.deploy.data.RootfsArchiveValidator.identityRejection(
                                        org.appdevforall.k2go.deploy.data.RootfsManifest.read(archivePath));
                        String why = org.appdevforall.k2go.deploy.data.RootfsArchiveValidator
                                .rejectionMessage(context, early);
                        if (why != null) {
                            throw new Exception(why);
                        }
                    }

                    listing = listEntries(tarBinary, archivePath, isGzip, cancelledBeforeExtract, pauseAtBoundary, listener);
                    for (String entry : listing.paths) {
                        if (ArchiveEntry.escapesRoot(entry)) {
                            throw new Exception("Unsafe archive entry (path traversal): " + entry);
                        }
                    }

                    // ADFA-4915 / K2GO-555: the extraction denominator, from the one listing pass (no extra
                    // tar pass). totalBytes (uncompressed) weights progress by real work; totalMembers is the
                    // fallback when the listing yields no sizes, so the bar can never stall at zero.
                    totalMembers = listing.paths.size();
                    totalBytes = listing.totalBytes;
                    byteMode = totalBytes > 0L;

                    // For untrusted imports/restores: it must be a valid rootfs of THIS
                    // app's architecture (ABI policy: 32<->32, 64<->64). Reuses the
                    // listing above. Fail closed before extracting.
                    if (validateRootfs) {
                        org.appdevforall.k2go.deploy.data.RootfsArchiveValidator.Result vr =
                                org.appdevforall.k2go.deploy.data.RootfsArchiveValidator
                                        .validateWithEntries(context, archivePath, isGzip, tarBinary, listing.paths);
                        String why = org.appdevforall.k2go.deploy.data.RootfsArchiveValidator
                                .rejectionMessage(context, vr);
                        if (why != null) {
                            throw new Exception(why);
                        }
                    }
                }
                final long[] doneMembers = {0L};
                final long[] doneBytes = {0L};

                // K2GO-384: the point of no return. Verify and every fail-closed check have passed and
                // nothing has been written yet. A cancel confirmed during verify (or in the tiny window at
                // this boundary) aborts here with the destination untouched. Otherwise announce the
                // destructive phase (the caller plants its recovery marker) and proceed -- from the first
                // write on, cancel is ignored.
                if (cancelledBeforeExtract != null && cancelledBeforeExtract.get()) {
                    throw new java.util.concurrent.CancellationException("cancelled before extract");
                }
                listener.onExtractStarting();

                // 2. BUILD THE COMMAND
                List<String> command = new ArrayList<>();
                command.add(tarBinary);
                command.add("-xvf");

                if (isGzip) {
                    // Tell tar to read the uncompressed raw bytes from standard input (stdin)
                    command.add("-");
                } else {
                    // For .xz or raw .tar, we pass the file directly and hope tar supports it
                    command.add(archivePath);
                }

                command.add("-C");
                command.add(destDir);

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true); // Catch all warnings/errors in one stream
                tarProcess = pb.start();

                // 3. READ TAR OUTPUT (Prevents buffer blocking and logs errors)
                // ADFA-4544: retain the last output lines (stderr is merged) for diagnostics.
                final java.util.concurrent.ConcurrentLinkedDeque<String> tarTail = new java.util.concurrent.ConcurrentLinkedDeque<>();
                final Handler uiHandler = new Handler(Looper.getMainLooper());
                // K2GO-384: EXTRACT-pass progress + ETA are driven by MEMBERS WRITTEN, tracked in the
                // reader thread below (tar -v emits one line per member as it lands on disk). extractStartMs
                // is the pass clock that thread uses for the rate/ETA.
                final long extractStartMs = SystemClock.elapsedRealtime();
                // K2GO-555: the EXTRACT rate is a trailing window, not the cumulative average. Exactly one
                // thread samples it per run: the reader thread on the untrusted path, the gzip feeder on
                // the trusted path (K2GO-554 D8), never both at once, so it needs no synchronization.
                final WindowedRate extractRate = new WindowedRate(EXTRACT_RATE_WINDOW_MS);
                // K2GO-554 D8: the member tar is writing right now, set by the reader thread and read by
                // the feeder (a different thread) so the trusted-path bar can still name the file (the
                // trusted path has no listing). AtomicReference for cross-thread visibility of the label.
                final java.util.concurrent.atomic.AtomicReference<String> lastMember =
                        new java.util.concurrent.atomic.AtomicReference<>("");
                Thread readerThread = new Thread(() -> {
                    long[] lastEmit = {0L};
                    long[] lastLog = {0L};
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(tarProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            // Always keep the tail (tar's stderr is merged here) so the failure
                            // diagnostic has the real cause; throttle the per-file logcat line so
                            // it does not flood logcat and get dropped "over proc quota" (ADFA-4544).
                            tarTail.addLast(line);
                            while (tarTail.size() > 20) tarTail.pollFirst();
                            long now = System.currentTimeMillis();
                            if (now - lastLog[0] >= 250) {
                                lastLog[0] = now;
                                Log.d(TAG, "Tar Output: " + line);
                            }
                            doneMembers[0]++;   // ADFA-4915: ~one verbose line per member (stray stderr lines just push toward the 99% clamp)
                            lastMember.set(line);   // K2GO-554 D8: the feeder reads this to name the file on the trusted path
                            // K2GO-554 D6: on the trusted path there is no listing, so the reader only drains
                            // tar output (keeping the diagnostic tail) and the feeder owns the bar below.
                            if (!trusted) {
                                // K2GO-555: weight the EXTRACT bar + ETA by uncompressed bytes written, not
                                // member count. tar -v names each member as it lands on disk; look its size up
                                // from the listing and accumulate. An unknown line (stray/stderr) adds 0 bytes,
                                // so there is no index drift. Members vary hugely in size, so the file-count ETA
                                // climbed to absurd values mid-extract; bytes track the real work.
                                Long sz = listing.sizeByPath.get(line);
                                if (sz != null) doneBytes[0] += sz;
                                if (now - lastEmit[0] >= 50) {
                                    lastEmit[0] = now;
                                    final String l = line;
                                    final long d = byteMode ? doneBytes[0] : doneMembers[0];
                                    final long tot = byteMode ? totalBytes : totalMembers;
                                    final int pct = ExtractProgress.percent(d, tot);
                                    final long rate = extractRate.sample(d, SystemClock.elapsedRealtime() - extractStartMs);
                                    uiHandler.post(() -> {
                                        listener.onProgress(l);
                                        listener.onProgress(pct, d, tot, l);
                                    });
                                    emitPhase(listener, uiHandler, Phase.EXTRACT, d, tot, rate, l);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                });
                readerThread.start();

                // 4. THE JAVA DECOMPRESSION PIPE (If it's a .gz file)
                boolean pipeBroke = false;
                long totalWritten = 0;
                if (isGzip) {
                    Log.d(TAG, "Starting Java GZIP Pipe stream to tar process...");
                    // tarInput is closed defensively in the finally below (NOT via
                    // try-with-resources): once tar dies its stdin flush/close re-throws EPIPE,
                    // which would escape to the outer catch and hide tar's real cause (ADFA-4544).
                    OutputStream tarInput = tarProcess.getOutputStream();
                    // K2GO-384: this feeder only decompresses the .gz into tar's stdin now; the EXTRACT-pass
                    // bar/ETA are emitted from the reader thread (members written), which is what actually
                    // tracks tar writing the rootfs to disk. (VERIFY still counts bytes read — there,
                    // reading the archive IS the work, so that measure is honest.)
                    try (FileInputStream fis = new FileInputStream(archivePath);
                         GZIPInputStream gis = new GZIPInputStream(fis)) {

                        byte[] buffer = new byte[8192]; // 8KB RAM chunk
                        int bytesRead;
                        long lastFeedEmit = 0L;   // K2GO-554 D8: throttle for the trusted-path feeder-byte emit
                        while ((bytesRead = gis.read(buffer)) != -1) {
                            // K2GO-384 (ADR-5343c): force-cancel DURING extract (acknowledged destructive).
                            // Reads its OWN token, never cancelledBeforeExtract -- a safe-zone abort must not be
                            // able to reach here and tear the rootfs. Kill tar mid-write; InstallGuard was
                            // planted at extract start, so recovery reinstalls. The non-zero tar exit surfaces
                            // via onError, which the caller maps to the "damaged" message.
                            if (forceCancelDuringExtract != null && forceCancelDuringExtract.get()) {
                                tarProcess.destroy();
                                pipeBroke = true;
                                break;
                            }
                            try {
                                tarInput.write(buffer, 0, bytesRead);
                                totalWritten += bytesRead;
                                // K2GO-554 D8: on the trusted path there is no listing to weight the bar by
                                // member, so drive it by uncompressed bytes fed to tar. OS pipe backpressure
                                // (~64 KB) keeps the feeder within a pipe-buffer of tar's real consumption,
                                // so this tracks disk progress without saturating early.
                                if (trusted) {
                                    long now = SystemClock.elapsedRealtime();
                                    if (now - lastFeedEmit >= 50L) {
                                        lastFeedEmit = now;
                                        final long rate = extractRate.sample(totalWritten, now - extractStartMs);
                                        emitPhase(listener, uiHandler, Phase.EXTRACT,
                                                totalWritten, totalBytes, rate, lastMember.get());
                                    }
                                }
                            } catch (java.io.IOException pipe) {
                                // ADFA-4544: tar (the pipe reader) closed its stdin early -> it
                                // failed or was killed. Don't report a generic decompression error;
                                // stop feeding and let waitFor() below surface tar's real exit code.
                                pipeBroke = true;
                                Log.e(TAG, "tar closed stdin early after " + totalWritten + " bytes", pipe);
                                break;
                            }
                        }
                        if (!pipeBroke) {
                            try {
                                tarInput.flush();
                            } catch (java.io.IOException pipe) {
                                pipeBroke = true;
                                Log.e(TAG, "tar closed stdin early on flush after " + totalWritten + " bytes", pipe);
                            }
                            Log.d(TAG, "Java GZIP Pipe finished pushing data.");
                        }
                    } finally {
                        // ADFA-4544: swallow the EPIPE that flush/close throws once tar is gone,
                        // so we still reach waitFor() + the rich diagnostic below instead of a bare
                        // "Fatal Extraction Error: EPIPE" from the outer catch.
                        try { tarInput.close(); } catch (java.io.IOException ignored) { }
                    }
                    // A GZIPInputStream read error (corrupt archive) still propagates to the
                    // outer catch as a genuine decompression failure.
                }

                // 5. WAIT FOR COMPLETION + DIAGNOSE
                int exitCode = tarProcess.waitFor();
                try { readerThread.join(1500); } catch (InterruptedException ignored) { }
                isExtracting = false;
                final long freeAfter = freeBytes(destination);

                final boolean broke = pipeBroke;
                final long wrote = totalWritten;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (exitCode == 0 && !broke) {
                        Log.d(TAG, "Extraction successful.");
                        listener.onProgress(100, totalMembers, totalMembers, "");   // ADFA-4915: 100% only on completion
                        listener.onComplete(destDir);
                    } else {
                        String diag = "tar exit=" + exitCode
                                + (broke ? " (stdin pipe broke: tar died mid-stream; 137/killed => phantom-process or OOM)" : "")
                                + ", wrote=" + wrote + "B, freeBefore=" + freeBefore + "B, freeAfter=" + freeAfter
                                + "B, lastTarOutput=" + tarTail;
                        Log.e(TAG, "Extraction failed: " + diag);
                        listener.onError(diag);
                    }
                });

            } catch (HeldForDecisionException held) {
                // K2GO-384: verify stopped for an undecided Cancel -- nothing written, the copied temp is
                // intact. The caller holds and offers re-run / abort.
                isExtracting = false;
                Log.d(TAG, "Verify held for a pending cancel decision");
                new Handler(Looper.getMainLooper()).post(listener::onHeldForDecision);
            } catch (java.util.concurrent.CancellationException ce) {
                // K2GO-384: cancelled in the pre-destructive zone -- nothing was written, so this is not
                // a failure. The destination rootfs is untouched and no InstallGuard marker was planted.
                isExtracting = false;
                Log.d(TAG, "Extraction cancelled before any write");
                new Handler(Looper.getMainLooper()).post(listener::onCancelled);
            } catch (Exception e) {
                isExtracting = false;
                Log.e(TAG, "Fatal Extraction Error", e);
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            }
        }).start();
    }


    /**
     * ADFA-5118 / K2GO-555: compute this pass's percent and a live ETA from a caller-supplied rate,
     * then hand them to the listener on the UI thread. The caller owns the rate source: VERIFY passes
     * the cumulative average (reading the archive is steady work), EXTRACT passes a trailing-window
     * rate (tar names a member at its start, so the per-member byte delta is bursty). The unified
     * 0..100 mapping is done by the presentation layer. {@code ratePerSec} is in the same unit as
     * {@code done}/{@code total} per second (bytes, or members on the no-size fallback).
     */
    private static void emitPhase(ExtractionListener listener, Handler uiHandler, Phase phase,
                                  long done, long total, long ratePerSec, String line) {
        final int pct = ExtractProgress.percent(done, total);
        final long eta = ExtractProgress.etaSeconds(done, total, ratePerSec);
        final String l = line == null ? "" : line;
        uiHandler.post(() -> listener.onExtractPhase(phase, pct, eta, l));
    }

    /**
     * K2GO-554 D8: the uncompressed size a single-stream gzip stores in its last 4 bytes (ISIZE,
     * RFC 1952, little-endian, MODULO 2^32). Below 4 GiB it is exactly the number of bytes the feeder
     * reads out of the gzip; above, it wraps, so callers lift it with
     * {@link ExtractProgress#trustedStreamTotal}. Returns -1 when the trailer cannot be read.
     */
    private static long gzipUncompressedSize(String path) {
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(path, "r")) {
            long len = f.length();
            if (len < 18L) {
                return -1L;   // smaller than a gzip header + trailer
            }
            f.seek(len - 4L);
            byte[] b = new byte[4];
            f.readFully(b);
            return (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8) | ((b[2] & 0xFFL) << 16) | ((b[3] & 0xFFL) << 24);
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * D11: enumerate the archive's member names without extracting, so we can
     * reject path-traversal before any file is written. Mirrors the extraction
     * invocation (gzip is decompressed in Java and piped to {@code tar -t}).
     *
     * <p>ADFA-5118: this is the VERIFY pass of the unified bar. On the gzip path it counts
     * compressed bytes consumed against the archive size on disk and emits determinate
     * progress + an ETA (via {@code onExtractPhase}); {@code tar -tv} already streams each
     * member, so the current file is surfaced too — symmetric with the extract pass.
     *
     * <p>K2GO-555: the listing is {@code tar -tv} (verbose), so each member carries its byte
     * size. {@link TarVerboseLine} parses every line into a path (for the traversal and rootfs
     * guards) plus the size, and the sizes feed the byte-weighted extract bar.
     */
    private Listing listEntries(String tarBinary, String archivePath, boolean isGzip,
                                java.util.concurrent.atomic.AtomicBoolean cancelledBeforeExtract,
                                java.util.concurrent.atomic.AtomicBoolean pauseAtBoundary,
                                ExtractionListener listener) throws Exception {
        List<String> names = new ArrayList<>();
        Map<String, Long> sizeByPath = new LinkedHashMap<>();
        long[] totalBytes = {0L};
        List<String> listCmd = new ArrayList<>();
        listCmd.add(tarBinary);
        if (isGzip) {
            // K2GO-555: -v so each member line carries its size; gzip is decompressed in Java and piped.
            listCmd.add("-tv");
            listCmd.add("-f");
            listCmd.add("-");
        } else {
            listCmd.add("-tvf");
            listCmd.add(archivePath);
        }

        Process listProcess = new ProcessBuilder(listCmd).start();

        // ADFA-5118: archive size on disk = the exact denominator for compressed-bytes progress.
        final long compressedTotal = new File(archivePath).length();
        final Handler uiHandler = new Handler(Looper.getMainLooper());
        final long startMs = SystemClock.elapsedRealtime();
        final String[] lastFile = {""};

        Thread feeder = null;
        if (isGzip) {
            feeder = new Thread(() -> {
                try (CountingInputStream cis = new CountingInputStream(new FileInputStream(archivePath));
                     GZIPInputStream gis = new GZIPInputStream(cis);
                     OutputStream os = listProcess.getOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long lastEmit = 0L;
                    while ((read = gis.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                        long now = SystemClock.elapsedRealtime();
                        if (compressedTotal > 0L && now - lastEmit >= 200) {
                            lastEmit = now;
                            emitPhase(listener, uiHandler, Phase.VERIFY, cis.count, compressedTotal,
                                    TransferRate.perSecond(cis.count, now - startMs), lastFile[0]);
                        }
                    }
                    os.flush();
                } catch (Exception ignored) {
                }
            });
            feeder.start();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // K2GO-384: Cancel during verify. A confirmed cancel aborts (kill the lister -> onCancelled).
                // A still-undecided Cancel (pauseAtBoundary) kills the lister too -- for responsiveness --
                // but unwinds to onHeldForDecision so the caller keeps the copied temp and can re-run this
                // pass. Both act before any write.
                if (cancelledBeforeExtract != null && cancelledBeforeExtract.get()) {
                    listProcess.destroy();
                    throw new java.util.concurrent.CancellationException("cancelled during verify");
                }
                if (pauseAtBoundary != null && pauseAtBoundary.get()) {
                    listProcess.destroy();
                    throw new HeldForDecisionException();
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                // K2GO-555: stdout of `tar -tv` is member lines only (stderr is separate here). Parse
                // each into its path and size. The path-traversal and rootfs guards read these paths, so
                // a line we cannot parse is a path we cannot verify -> fail closed, do not extract blind.
                TarVerboseLine e = TarVerboseLine.parse(line);
                if (e == null) {
                    listProcess.destroy();
                    throw new Exception("Unparseable archive listing line: " + line);
                }
                names.add(e.path);
                if (e.size > 0L) {
                    sizeByPath.put(e.path, e.size);
                    totalBytes[0] += e.size;
                }
                lastFile[0] = e.path;   // ADFA-5118: the member tar is listing right now
            }
        }

        int exitCode = listProcess.waitFor();
        if (feeder != null) feeder.join();
        if (exitCode != 0) {
            // Could not verify the archive -> fail closed rather than extract blind.
            throw new Exception("Could not read archive listing for verification (tar exit " + exitCode + ")");
        }
        return new Listing(names, sizeByPath, totalBytes[0]);
    }

    /** Available bytes on the destination's filesystem, or -1 if unavailable (ADFA-4544). */
    private static long freeBytes(File dir) {
        try {
            return new android.os.StatFs(dir.getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return -1L;
        }
    }

    public void stopExtraction() {
        if (tarProcess != null) {
            tarProcess.destroy();
            isExtracting = false;
        }
    }
}