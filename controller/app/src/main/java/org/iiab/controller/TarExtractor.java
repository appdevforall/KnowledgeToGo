/*
 * ============================================================================
 * Name        : TarExtractor.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Native wrapper for tar archive extraction with Java GZIP Pipe
 * ============================================================================
 */

package org.iiab.controller;

import org.iiab.controller.deploy.domain.ArchiveEntry;
import org.iiab.controller.deploy.domain.ExtractProgress;

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
import java.util.List;
import java.util.zip.GZIPInputStream;

public class TarExtractor {
    private static final String TAG = "IIAB-TarExtractor";
    private Process tarProcess;
    private boolean isExtracting = false;

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
         * [0,99] while extracting and set to 100 exactly once on completion; {@code done}
         * / {@code total} are archive-member counts; {@code line} is the current verbose
         * tar line (may be empty). Default no-op.
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
    }

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
                if (validateRootfs) {
                    org.iiab.controller.deploy.data.RootfsArchiveValidator.Result early =
                            org.iiab.controller.deploy.data.RootfsArchiveValidator.identityRejection(
                                    org.iiab.controller.deploy.data.RootfsManifest.read(archivePath));
                    String why = org.iiab.controller.deploy.data.RootfsArchiveValidator
                            .rejectionMessage(context, early);
                    if (why != null) {
                        throw new Exception(why);
                    }
                }

                List<String> entries = listEntries(tarBinary, archivePath, isGzip, listener);
                for (String entry : entries) {
                    if (ArchiveEntry.escapesRoot(entry)) {
                        throw new Exception("Unsafe archive entry (path traversal): " + entry);
                    }
                }

                // ADFA-4915: entries.size() is the extraction denominator (members), already
                // computed above for the traversal/ABI checks — no extra tar pass.
                final long totalMembers = entries.size();
                final long[] doneMembers = {0L};

                // For untrusted imports/restores: it must be a valid rootfs of THIS
                // app's architecture (ABI policy: 32<->32, 64<->64). Reuses the
                // listing above. Fail closed before extracting.
                if (validateRootfs) {
                    org.iiab.controller.deploy.data.RootfsArchiveValidator.Result vr =
                            org.iiab.controller.deploy.data.RootfsArchiveValidator
                                    .validateWithEntries(context, archivePath, isGzip, tarBinary, entries);
                    String why = org.iiab.controller.deploy.data.RootfsArchiveValidator
                            .rejectionMessage(context, vr);
                    if (why != null) {
                        throw new Exception(why);
                    }
                }

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
                // ADFA-5118: EXTRACT-pass byte progress. The current member comes from the reader
                // thread (below); the compressed byte count comes from the feeder (further below),
                // measured against the archive size on disk — the same currency as the VERIFY pass.
                final String[] lastExtractFile = {""};
                final long extractStartMs = SystemClock.elapsedRealtime();
                final long compressedTotalBytes = new File(archivePath).length();
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
                            lastExtractFile[0] = line;   // ADFA-5118: current member for the byte-based emit
                            long now = System.currentTimeMillis();
                            if (now - lastLog[0] >= 250) {
                                lastLog[0] = now;
                                Log.d(TAG, "Tar Output: " + line);
                            }
                            doneMembers[0]++;   // ADFA-4915: ~one verbose line per member (stray stderr lines just push toward the 99% clamp)
                            if (now - lastEmit[0] >= 50) {
                                lastEmit[0] = now;
                                final String l = line;
                                final long d = doneMembers[0];
                                final int pct = ExtractProgress.percent(d, totalMembers);
                                uiHandler.post(() -> {
                                    listener.onProgress(l);
                                    listener.onProgress(pct, d, totalMembers, l);
                                });
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
                    long lastByteEmit = 0L;
                    // ADFA-5118: count compressed bytes pulled from disk for the EXTRACT-pass bar; cis
                    // lives in the try-with-resources so it closes even if GZIPInputStream throws.
                    try (CountingInputStream cis = new CountingInputStream(new FileInputStream(archivePath));
                         GZIPInputStream gis = new GZIPInputStream(cis)) {

                        byte[] buffer = new byte[8192]; // 8KB RAM chunk
                        int bytesRead;
                        while ((bytesRead = gis.read(buffer)) != -1) {
                            try {
                                tarInput.write(buffer, 0, bytesRead);
                                totalWritten += bytesRead;
                                long now = SystemClock.elapsedRealtime();
                                if (compressedTotalBytes > 0L && now - lastByteEmit >= 200) {
                                    lastByteEmit = now;
                                    emitPhase(listener, uiHandler, Phase.EXTRACT,
                                            cis.count, compressedTotalBytes, now - extractStartMs, lastExtractFile[0]);
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

            } catch (Exception e) {
                isExtracting = false;
                Log.e(TAG, "Fatal Extraction Error", e);
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            }
        }).start();
    }


    /**
     * ADFA-5118: compute this pass's percent + a live ETA (rate averaged by TransferRate over the
     * pass so far) and hand them to the listener on the UI thread. Used by both the VERIFY and the
     * EXTRACT feeder; the unified 0..100 mapping is done by the presentation layer.
     */
    private static void emitPhase(ExtractionListener listener, Handler uiHandler, Phase phase,
                                  long done, long total, long elapsedMs, String line) {
        final long rate = org.iiab.controller.system.domain.TransferRate.perSecond(done, elapsedMs);
        final int pct = ExtractProgress.percent(done, total);
        final long eta = ExtractProgress.etaSeconds(done, total, rate);
        final String l = line == null ? "" : line;
        uiHandler.post(() -> listener.onExtractPhase(phase, pct, eta, l));
    }

    /**
     * D11: enumerate the archive's member names without extracting, so we can
     * reject path-traversal before any file is written. Mirrors the extraction
     * invocation (gzip is decompressed in Java and piped to {@code tar -t}).
     *
     * <p>ADFA-5118: this is the VERIFY pass of the unified bar. On the gzip path it counts
     * compressed bytes consumed against the archive size on disk and emits determinate
     * progress + an ETA (via {@code onExtractPhase}); {@code tar -t} already streams each
     * member name, so the current file is surfaced too — symmetric with the extract pass.
     */
    private List<String> listEntries(String tarBinary, String archivePath, boolean isGzip,
                                     ExtractionListener listener) throws Exception {
        List<String> names = new ArrayList<>();
        List<String> listCmd = new ArrayList<>();
        listCmd.add(tarBinary);
        if (isGzip) {
            listCmd.add("-t");
            listCmd.add("-f");
            listCmd.add("-");
        } else {
            listCmd.add("-tf");
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
                            emitPhase(listener, uiHandler, Phase.VERIFY,
                                    cis.count, compressedTotal, now - startMs, lastFile[0]);
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
                names.add(line);
                lastFile[0] = line;   // ADFA-5118: the member tar -t is listing right now
            }
        }

        int exitCode = listProcess.waitFor();
        if (feeder != null) feeder.join();
        if (exitCode != 0) {
            // Could not verify the archive -> fail closed rather than extract blind.
            throw new Exception("Could not read archive listing for verification (tar exit " + exitCode + ")");
        }
        return names;
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