/*
 ============================================================================
 Name        : Aria2Manager.java
 Contributors: IIAB Project
 Copyright (c) 2026 IIAB Project
 Description : Java wrapper for the native libaria2c.so binary.
 Handles native aria2c download tasks.
 ============================================================================
 */

package org.iiab.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
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

                Log.d(TAG, "Executing Native Aria2c...");
                Log.d(TAG, "Target URL: " + url);

                // 3. Build the command with the magic of Bash translated to Java
                ProcessBuilder pb = new ProcessBuilder(
                        aria2Bin.getAbsolutePath(),
                        "--dir=" + downloadDir.getAbsolutePath(),
                        "--continue=true",
                        "--allow-overwrite=true",
                        "--auto-file-renaming=false",
                        "--max-connection-per-server=4",
                        "--split=4",
                        "--follow-metalink=mem",
                        "--enable-dht=true",
                        "--dht-file-path=" + dhtFile.getAbsolutePath(),
                        "--bt-enable-lpd=true",
                        "--seed-time=0",
                        "--check-certificate=false",   // <--- Ignore missing SSL certificates in native Android
                        "--console-log-level=warn",
                        "--summary-interval=1",
                        "--download-result=hide",
                        url
                );

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

                    Log.d(TAG, "[Aria2] " + line); // Ver todo en el Logcat para debuggear

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
}