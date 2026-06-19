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
    }

    public void startExtraction(Context context, String archivePath, String destDir, ExtractionListener listener) {
        if (isExtracting) return;

        new Thread(() -> {
            isExtracting = true;
            try {
                File destination = new File(destDir);
                if (!destination.exists()) {
                    destination.mkdirs();
                }
                // 1. DYNAMIC BINARY SELECTION
                File staticTar = new File(context.getApplicationInfo().nativeLibraryDir, "libtar.so");
                String tarBinary = staticTar.exists() ? staticTar.getAbsolutePath() : "/system/bin/tar";
                Log.d(TAG, "Using tar binary: " + tarBinary);

                boolean isGzip = archivePath.toLowerCase().endsWith(".gz");

                // D11: refuse path-traversal. List the archive members first and
                // bail out (without extracting anything) if any member is absolute
                // or climbs out of destDir via "..". An imported/restored backup is
                // untrusted, so this runs for every extraction.
                for (String entry : listEntries(tarBinary, archivePath, isGzip)) {
                    if (ArchiveEntry.escapesRoot(entry)) {
                        throw new Exception("Unsafe archive entry (path traversal): " + entry);
                    }
                }

                // 2. BUILD THE COMMAND
                List<String> command = new ArrayList<>();
                command.add(tarBinary);
                command.add("-xf");

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
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(tarProcess.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Log.d(TAG, "Tar Output: " + line);
                        }
                    } catch (Exception ignored) {
                    }
                }).start();

                // 4. THE JAVA DECOMPRESSION PIPE (If it's a .gz file)
                if (isGzip) {
                    Log.d(TAG, "Starting Java GZIP Pipe stream to tar process...");
                    // Try-with-resources automatically safely closes the streams when done
                    try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(archivePath));
                         OutputStream tarInput = tarProcess.getOutputStream()) {

                        byte[] buffer = new byte[8192]; // 8KB RAM chunk
                        int bytesRead;
                        while ((bytesRead = gis.read(buffer)) != -1) {
                            tarInput.write(buffer, 0, bytesRead);
                        }
                        tarInput.flush();
                        Log.d(TAG, "Java GZIP Pipe finished pushing data.");
                    } catch (Exception streamError) {
                        Log.e(TAG, "Stream decompression error", streamError);
                        throw new Exception("Decompression failed: " + streamError.getMessage());
                    }
                }

                // 5. WAIT FOR COMPLETION
                // By this point, the OutputStream is closed. Tar knows EOF is reached and will exit.
                int exitCode = tarProcess.waitFor();
                isExtracting = false;

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (exitCode == 0) {
                        Log.d(TAG, "Extraction successful.");
                        listener.onComplete(destDir);
                    } else {
                        Log.e(TAG, "Extraction failed with code " + exitCode);
                        listener.onError("Tar process exited with error code: " + exitCode);
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

    public void stopExtraction() {
        if (tarProcess != null) {
            tarProcess.destroy();
            isExtracting = false;
        }
    }
}