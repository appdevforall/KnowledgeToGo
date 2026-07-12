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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

    public interface ExtractionListener {
        void onComplete(String destDir);

        void onError(String error);

        /** A streamed line of extraction output (verbose tar). Default no-op. */
        default void onProgress(String line) { }
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
                List<String> entries = listEntries(tarBinary, archivePath, isGzip);
                for (String entry : entries) {
                    if (ArchiveEntry.escapesRoot(entry)) {
                        throw new Exception("Unsafe archive entry (path traversal): " + entry);
                    }
                }

                // For untrusted imports/restores: it must be a valid rootfs of THIS
                // app's architecture (ABI policy: 32<->32, 64<->64). Reuses the
                // listing above. Fail closed before extracting.
                if (validateRootfs) {
                    org.iiab.controller.deploy.data.RootfsArchiveValidator.Result vr =
                            org.iiab.controller.deploy.data.RootfsArchiveValidator
                                    .validateWithEntries(context, archivePath, isGzip, tarBinary, entries);
                    if (vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.NOT_A_ROOTFS) {
                        throw new Exception(context.getString(R.string.install_error_not_rootfs));
                    }
                    if (vr == org.iiab.controller.deploy.data.RootfsArchiveValidator.Result.WRONG_ARCH) {
                        throw new Exception(context.getString(R.string.install_error_wrong_arch));
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
                            if (now - lastEmit[0] >= 50) {
                                lastEmit[0] = now;
                                final String l = line;
                                uiHandler.post(() -> listener.onProgress(l));
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
                    try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(archivePath))) {

                        byte[] buffer = new byte[8192]; // 8KB RAM chunk
                        int bytesRead;
                        while ((bytesRead = gis.read(buffer)) != -1) {
                            try {
                                tarInput.write(buffer, 0, bytesRead);
                                totalWritten += bytesRead;
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
     * D11: enumerate the archive's member names without extracting, so we can
     * reject path-traversal before any file is written. Mirrors the extraction
     * invocation (gzip is decompressed in Java and piped to {@code tar -t}).
     */
    private List<String> listEntries(String tarBinary, String archivePath, boolean isGzip) throws Exception {
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

        Thread feeder = null;
        if (isGzip) {
            feeder = new Thread(() -> {
                try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(archivePath));
                     OutputStream os = listProcess.getOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = gis.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
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