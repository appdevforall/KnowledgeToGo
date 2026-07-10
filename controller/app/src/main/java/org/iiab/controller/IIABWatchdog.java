/*
 * ============================================================================
 * Name        : IIABWatchdog.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Watchdog activity
 * ============================================================================
 */
package org.iiab.controller;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A stateless utility class to perform keep-alive actions for Termux.
 * The lifecycle (start/stop/loop) is managed by the calling service.
 */
public class IIABWatchdog {
    private static final String TAG = "IIAB-Controller";

    public static final String ACTION_LOG_MESSAGE = "org.iiab.controller.LOG_MESSAGE";
    public static final String EXTRA_MESSAGE = "org.iiab.controller.EXTRA_MESSAGE";
    public static final String ACTION_TERMUX_OUTPUT = "org.iiab.controller.TERMUX_OUTPUT";

    public static final String PREF_RAPID_GROWTH = "log_rapid_growth";

    // Debug logging is gated to debug builds only (ADFA-4452 / M19); was hardcoded true.
    private static final boolean DEBUG_ENABLED = BuildConfig.DEBUG;
    private static final String BLACKBOX_FILE = "watchdog_heartbeat_log.txt";
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_DAYS = 5;

    /**
     * Performs a full heartbeat pulse: sending stimulus.
     */
    public static void performHeartbeat(Context context) {
        sendStimulus(context);
        // TROUBLESHOOTING: Uncomment to test NGINX status.
        // performDebugPing(context);
    }

    /**
     * Sends a keep-alive command to Termux via Intent.
     */
    public static void sendStimulus(Context context) {
        if (DEBUG_ENABLED) {
            writeToBlackBox(context, context.getString(R.string.pulse_stimulating));
        }

        // Build the intent for Termux with exact payload requirements
        Intent intent = new Intent();
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.setAction("com.termux.RUN_COMMAND");

        // 1. Absolute path to the command (String)
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/true");
        // 2. Execute silently in the background (Boolean, critical)
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        // 3. Avoid saving session history (String "0" = no action)
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");

        // Callback mechanism to confirm execution
        Intent callbackIntent = new Intent(context, TermuxCallbackReceiver.class);
        callbackIntent.setAction(ACTION_TERMUX_OUTPUT);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, callbackIntent, flags);
        intent.putExtra("com.termux.service.RUN_COMMAND_CALLBACK", pendingIntent);

        try {
            context.startService(intent);
        } catch (SecurityException e) {
            // This catches specific permission errors on newer Android versions
            Log.e(TAG, context.getString(R.string.permission_denied_log), e);
            writeToBlackBox(context, context.getString(R.string.critical_os_blocked));
        } catch (Exception e) {
            Log.e(TAG, context.getString(R.string.unexpected_error_termux), e);
            writeToBlackBox(context, context.getString(R.string.pulse_error_log, e.getMessage()));
        }
    }

    /**
     * Pings the Termux NGINX server to check responsiveness.
     */
    public static void performDebugPing(Context context) {
        final String NGINX_IP = "127.0.0.1";
        final int NGINX_PORT = 8085;

        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(NGINX_IP, NGINX_PORT), 2000);
                if (DEBUG_ENABLED) {
                    writeToBlackBox(context, context.getString(R.string.ping_ok));
                }
            } catch (IOException e) {
                if (DEBUG_ENABLED) {
                    writeToBlackBox(context, context.getString(R.string.ping_fail, e.getMessage()));
                }
            }
        }).start();
    }

    public static void logSessionStart(Context context) {
        if (DEBUG_ENABLED) {
            writeToBlackBox(context, context.getString(R.string.session_started));
        }
    }

    public static void logSessionStop(Context context) {
        if (DEBUG_ENABLED) {
            writeToBlackBox(context, context.getString(R.string.session_stopped));
        }
    }

    /**
     * Writes a message to the local log file and broadcasts it for UI update.
     */
    public static void writeToBlackBox(Context context, String message) {
        File logFile = new File(context.getFilesDir(), BLACKBOX_FILE);

        // 1. Perform maintenance if file size is nearing limit
        if (logFile.exists() && logFile.length() > MAX_FILE_SIZE * 0.9) {
            maintenance(context, logFile);
        }

        try (FileWriter writer = new FileWriter(logFile, true)) {
            String datePrefix = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            writer.append(datePrefix).append(" - ").append(message).append("\n");
            broadcastLog(context, message);
        } catch (IOException e) {
            Log.e(TAG, context.getString(R.string.failed_write_blackbox), e);
        }
    }

    /**
     * Handles log rotation based on date (5 days) and size (10MB).
     */
    private static void maintenance(Context context, File logFile) {
        List<String> lines = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -MAX_DAYS);
        Date cutoffDate = cal.getTime();

        boolean deletedByDate = false;

        try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() > 19) {
                    try {
                        Date lineDate = sdf.parse(line.substring(0, 19));
                        if (lineDate != null && lineDate.after(cutoffDate)) {
                            lines.add(line);
                        } else {
                            deletedByDate = true;
                        }
                    } catch (ParseException e) {
                        lines.add(line);
                    }
                } else {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            return;
        }

        // If after date cleanup it's still too large, trim the oldest 20%
        if (calculateSize(lines) > MAX_FILE_SIZE) {
            int toRemove = lines.size() / 5;
            if (toRemove > 0) {
                lines = lines.subList(toRemove, lines.size());
            }
            // If deleting by size but not by date, it indicates rapid log growth
            if (!deletedByDate) {
                setRapidGrowthFlag(context, true);
            }
        }

        // Write cleaned logs back to file
        try (PrintWriter pw = new PrintWriter(new FileWriter(logFile))) {
            for (String l : lines) {
                pw.println(l);
            }
        } catch (IOException e) {
            Log.e(TAG, context.getString(R.string.maintenance_write_failed), e);
        }
    }

    private static long calculateSize(List<String> lines) {
        long size = 0;
        for (String s : lines) size += s.length() + 1;
        return size;
    }

    private static void setRapidGrowthFlag(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences("IIAB_Internal", Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_RAPID_GROWTH, enabled).apply();
    }

    private static void broadcastLog(Context context, String message) {
        Intent intent = new Intent(ACTION_LOG_MESSAGE);
        intent.putExtra(EXTRA_MESSAGE, message);
        // M4: scope to our own package. An implicit broadcast would leak the log
        // text to any installed app, and broadcasting to dynamic receivers without
        // a package is rejected on API 34+ (would crash). Matches the other senders.
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
