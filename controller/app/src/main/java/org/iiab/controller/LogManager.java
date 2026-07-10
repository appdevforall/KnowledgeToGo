/*
 * ============================================================================
 * Name        : LogManager.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Watchdog log manager
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

public class LogManager {
    private static final String LOG_FILE_NAME = "watchdog_heartbeat_log.txt";

    // Callbacks to communicate with MainActivity
    public interface LogReadCallback {
        void onResult(String logContent, boolean isRapidGrowth);
    }

    public interface LogClearCallback {
        void onSuccess();

        void onError(String message);
    }

    // Read the file in the background and return the result to the main thread
    public static void readLogsAsync(Context context, LogReadCallback callback) {
        new Thread(() -> {
            File logFile = new File(context.getFilesDir(), LOG_FILE_NAME);
            StringBuilder sb = new StringBuilder();

            if (!logFile.exists()) {
                sb.append(context.getString(R.string.no_blackbox_found)).append("\n");
            } else {
                sb.append(context.getString(R.string.loading_history)).append("\n");
                try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                } catch (IOException e) {
                    sb.append(context.getString(R.string.error_reading_history, e.getMessage())).append("\n");
                }
                sb.append(context.getString(R.string.end_of_history)).append("\n");
            }

            SharedPreferences internalPrefs = context.getSharedPreferences("IIAB_Internal", Context.MODE_PRIVATE);
            boolean isRapid = internalPrefs.getBoolean(IIABWatchdog.PREF_RAPID_GROWTH, false);
            String result = sb.toString();

            // We return the call on the main UI thread
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result, isRapid));
        }).start();
    }

    // Delete the file securely
    public static void clearLogs(Context context, LogClearCallback callback) {
        File logFile = new File(context.getFilesDir(), LOG_FILE_NAME);
        try (PrintWriter pw = new PrintWriter(logFile)) {
            pw.print("");
            context.getSharedPreferences("IIAB_Internal", Context.MODE_PRIVATE)
                    .edit().putBoolean(IIABWatchdog.PREF_RAPID_GROWTH, false).apply();
            callback.onSuccess();
        } catch (IOException e) {
            callback.onError(e.getMessage());
        }
    }

    // Format an arbitrary byte size using the same units/strings as the size readout.
    public static String formatSize(Context context, long size) {
        if (size < 1024) {
            return context.getString(R.string.log_size_bytes, size);
        } else if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), context.getString(R.string.log_size_kb), size / 1024.0);
        } else {
            return String.format(Locale.getDefault(), context.getString(R.string.log_size_mb), size / (1024.0 * 1024.0));
        }
    }

    // Calculate the file size
    public static String getFormattedSize(Context context) {
        File logFile = new File(context.getFilesDir(), LOG_FILE_NAME);
        long size = logFile.exists() ? logFile.length() : 0;

        if (size < 1024) {
            return context.getString(R.string.log_size_bytes, size);
        } else if (size < 1024 * 1024) {
            return String.format(Locale.getDefault(), context.getString(R.string.log_size_kb), size / 1024.0);
        } else {
            return String.format(Locale.getDefault(), context.getString(R.string.log_size_mb), size / (1024.0 * 1024.0));
        }
    }
}
