/*
 ============================================================================
 Name        : Aria2Manager.java
 Contributors: IIAB Project
 Copyright (c) 2026 IIAB Project
 Description : Java wrapper for the native libaria2c.so binary.
 ============================================================================
 */

package org.iiab.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Aria2Manager {

    private static final String TAG = "IIAB-Aria2-Native";
    private Process aria2Process;
    private boolean isCancelled = false;

    public interface DownloadListener {
        void onProgress(int percentage, String speed, String eta);
        void onComplete(String downloadPath);
        void onError(String error);
    }

    public void startDownload(Context context, String url, DownloadListener listener) {
        isCancelled = false;
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                // 1. Get the path of our native binary
                String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
                File aria2Bin = new File(nativeLibDir, "libaria2c.so");

                if (!aria2Bin.exists()) {
                    throw new Exception("Native aria2c binary not found at: " + aria2Bin.getAbsolutePath());
                }

                // 2. Prepare the download directory and auxiliary files
                File downloadDir = new File(context.getFilesDir(), "rootfs/downloads");
                if (!downloadDir.exists()) downloadDir.mkdirs();

                // Secure DHT file within our app (avoids the com.termux crash)
                File dhtFile = new File(context.getFilesDir(), "dht.dat");
                if (!dhtFile.exists()) {
                    try { dhtFile.createNewFile(); } catch (Exception e) { Log.w(TAG, "Could not create dht.dat"); }
                }

                // --- Extract SSL Certificate from assets ---
                File caCertFile = new File(context.getFilesDir(), "cacert.pem");
                if (!caCertFile.exists()) {
                    extractAsset(context, "cacert.pem", caCertFile);
                }
                // D6: fail closed. The downloaded rootfs is extracted and executed as
                // root, so we must never fall back to an unverified TLS connection
                // where a MITM could swap it. If the CA bundle is unavailable, abort.
                if (!caCertFile.exists()) {
                    throw new Exception("Secure download aborted: CA certificate bundle (cacert.pem) is unavailable.");
                }

                Log.d(TAG, "Executing Native Aria2c...");
                Log.d(TAG, "Target URL: " + url);

                // --- RUN NETWORK PROFILER (Time-boxed speed test) ---
                // UI updates are now handled inside the profiler
                boolean forceIpv4 = Aria2NetworkProfiler.shouldForceIpv4(aria2Bin, downloadDir, dhtFile, url, mainHandler, listener);
                // ----------------------------------------------------

                // 3. Build the command dynamically
                java.util.List<String> command = new java.util.ArrayList<>();
                command.add(aria2Bin.getAbsolutePath());
                command.add("--dir=" + downloadDir.getAbsolutePath());
                command.add("--continue=true");
                command.add("--allow-overwrite=true");
                command.add("--auto-file-renaming=false");
                command.add("--max-connection-per-server=4");
                command.add("--split=4");
                command.add("--follow-metalink=mem");
                // D6: verify the SHA-256 checksums embedded in the .meta4 (Metalink)
                // while downloading. On mismatch aria2 exits non-zero, so onError fires
                // and the archive is never extracted/executed.
                command.add("--check-integrity=true");
                command.add("--enable-dht=true");
                command.add("--dht-file-path=" + dhtFile.getAbsolutePath());
                command.add("--bt-enable-lpd=true");
                command.add("--seed-time=0");

                // --- Apply SSL Certificate Validation (D6: always strict; cacert
                // presence was already enforced above, so there is no insecure path) ---
                Log.d(TAG, "Enforcing strict TLS certificate validation.");
                command.add("--check-certificate=true");
                command.add("--ca-certificate=" + caCertFile.getAbsolutePath());

                command.add("--console-log-level=warn");
                command.add("--summary-interval=1");
                command.add("--download-result=hide");

                // Apply network decision
                if (forceIpv4) {
                    Log.w(TAG, "Network profiler decided to FORCE IPv4.");
                    command.add("--disable-ipv6=true");
                }

                command.add(url);

                ProcessBuilder pb = new ProcessBuilder(command);

                // Redirect errors to the same input stream
                pb.redirectErrorStream(true);
                aria2Process = pb.start();

                // 4. Read the output in real-time (stdout)
                BufferedReader reader = new BufferedReader(new InputStreamReader(aria2Process.getInputStream()));
                String line;

                // Regex to capture typical Aria2c output
                // Example: [#2089b0 400MiB/1.0GiB(39%) CN:4 DL:4.5MiB ETA:2m20s]
                Pattern pattern = Pattern.compile("\\((\\d+)%\\).*?DL:([^\\s]+).*?ETA:([^\\s\\]]+)");

                while ((line = reader.readLine()) != null) {
                    if (isCancelled) {
                        aria2Process.destroy();
                        break;
                    }

                    Log.d(TAG, "[Aria2] " + line);

                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        int percent = Integer.parseInt(matcher.group(1));
                        String speed = matcher.group(2);
                        String eta = matcher.group(3);

                        mainHandler.post(() -> listener.onProgress(percent, speed, eta));
                    }
                }

                int exitCode = aria2Process.waitFor();
                Log.d(TAG, "Native Aria2c exited with code: " + exitCode);

                if (isCancelled) {
                    mainHandler.post(() -> listener.onError("Download cancelled."));
                    return;
                }

                if (exitCode == 0) {
                    mainHandler.post(() -> listener.onComplete(downloadDir.getAbsolutePath()));
                } else {
                    mainHandler.post(() -> listener.onError("Aria2c native process failed with code " + exitCode));
                }

            } catch (Exception e) {
                Log.e(TAG, "Native Execution Error", e);
                mainHandler.post(() -> listener.onError("Fatal Error: " + e.getMessage()));
            }
        }).start();
    }

    public void stopDownload() {
        isCancelled = true;
        if (aria2Process != null) {
            aria2Process.destroy();
        }
    }

    /**
     * Helper method to copy files from the APK assets folder to internal storage.
     */
    private void extractAsset(Context context, String assetName, File destination) {
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract " + assetName + " from assets", e);
        }
    }
}