/*
 ============================================================================
 Name        : Aria2NetworkProfiler.java
 Contributors: AppDevForAll
 Copyright (c) 2026 AppDevForAll
 Description : Network profiler to determine optimal network routing (IPv4 vs IPv6).
 ============================================================================
 */

package org.appdevforall.k2go;

import android.os.Handler;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Aria2NetworkProfiler {

    private static final String TAG = "IIAB-NetworkProfiler";
    private static final int TEST_DURATION_MS = 6000; // 6 seconds to bypass initial QoS burst

    /**
     * Runs a real-world speed test by downloading the target file for a few seconds.
     * Returns TRUE if IPv4 proves to be more stable and faster.
     */
    public static boolean shouldForceIpv4(File aria2Bin, File downloadDir, File dhtFile, String targetUrl, Handler mainHandler, Aria2Manager.DownloadListener listener) {
        Log.i(TAG, "Starting Network Profiling (Time-Boxed Race)...");

        // 1. Test IPv4 (Forced)
        mainHandler.post(() -> listener.onProgress(0, "Test IPv4", "Wait"));
        Log.i(TAG, "=== TEST 1: FORCED IPv4 ROUTE ===");
        double speedIpv4 = runTimeBoxedTest(aria2Bin, downloadDir, dhtFile, targetUrl, true);

        // 2. Test Default (Usually IPv6 preferred)
        mainHandler.post(() -> listener.onProgress(0, "Test IPv6", "Wait"));
        Log.i(TAG, "=== TEST 2: DEFAULT ROUTE (IPv6 Preferred) ===");
        double speedDefault = runTimeBoxedTest(aria2Bin, downloadDir, dhtFile, targetUrl, false);

        Log.i(TAG, "=== FINAL PROFILING RESULTS ===");
        Log.i(TAG, String.format("Sustained Speed IPv4: %.2f KB/s", speedIpv4));
        Log.i(TAG, String.format("Sustained Speed Default: %.2f KB/s", speedDefault));

        boolean forceIpv4 = false;

        // If Default/IPv6 is dead (0) or IPv4 is at least 20% faster, force IPv4.
        if (speedDefault == 0 || speedIpv4 > (speedDefault * 1.2)) {
            Log.w(TAG, "Conclusion: QoS or routing is throttling the default route. Forcing IPv4.");
            forceIpv4 = true;
        } else {
            Log.i(TAG, "Conclusion: Default route is stable. Using default configuration.");
        }

        // Show the winner in the UI
        final String winnerText = forceIpv4 ? "✓ IPv4" : "✓ IPv6";
        mainHandler.post(() -> listener.onProgress(0, winnerText, "Wait"));

        // Brief pause so the user can actually read the result before the real download overwrites it
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

        return forceIpv4;
    }

    private static double runTimeBoxedTest(File aria2Bin, File downloadDir, File dhtFile, String url, boolean forceIpv4) {
        Process process = null;
        double lastRecordedSpeedKB = 0.0;

        try {
            List<String> command = new ArrayList<>();
            command.add(aria2Bin.getAbsolutePath());
            command.add("--dir=" + downloadDir.getAbsolutePath());
            command.add("--continue=true"); // CRITICAL: Do not lose downloaded data
            command.add("--file-allocation=none");

            // Match your baseline configuration
            command.add("--max-connection-per-server=4");
            command.add("--split=4");
            command.add("--enable-dht=true");
            command.add("--dht-file-path=" + dhtFile.getAbsolutePath());
            command.add("--check-certificate=false");

            if (forceIpv4) {
                command.add("--disable-ipv6=true");
            }

            command.add("--console-log-level=warn");
            command.add("--summary-interval=1"); // Need speed output every second
            command.add("--download-result=hide");
            command.add(url);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;

            // Regex to capture speed (e.g., DL:1.5MiB or DL:750KiB)
            Pattern pattern = Pattern.compile("DL:([0-9.]+)([K|M|G]?iB)");

            long startTime = System.currentTimeMillis();

            // Read output until time runs out
            while ((System.currentTimeMillis() - startTime) < TEST_DURATION_MS) {
                if (reader.ready()) {
                    line = reader.readLine();
                    if (line == null) break;

                    Log.d(TAG, "[Aria2-Test] " + line);

                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        double value = Double.parseDouble(matcher.group(1));
                        String unit = matcher.group(2);

                        // Standardize to Kilobytes per second (KB/s)
                        if (unit.equals("MiB")) {
                            lastRecordedSpeedKB = value * 1024;
                        } else if (unit.equals("GiB")) {
                            lastRecordedSpeedKB = value * 1024 * 1024;
                        } else {
                            lastRecordedSpeedKB = value; // KiB
                        }
                    }
                } else {
                    Thread.sleep(50); // Short pause to prevent CPU burn
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Speed test error", e);
        } finally {
            if (process != null) {
                process.destroy(); // Kill process mercilessly after 6 seconds
                try { process.waitFor(); } catch (InterruptedException ignored) {}
            }
        }

        return lastRecordedSpeedKB;
    }
}