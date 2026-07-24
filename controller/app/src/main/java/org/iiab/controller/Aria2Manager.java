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
import java.net.HttpURLConnection;
import java.net.URL;
import org.iiab.controller.download.domain.MetalinkSplit;
import org.iiab.controller.download.domain.MetalinkFile;
import org.iiab.controller.download.domain.DownloadVerifier;

public class Aria2Manager {

    private static final String TAG = "IIAB-Aria2-Native";
    private Process aria2Process;
    private boolean isCancelled = false;

    public interface DownloadListener {
        void onProgress(int percentage, String speed, String eta);
        void onComplete(String downloadPath);
        void onError(String error);
        /** ADFA-4676: post-download integrity gate failed (size/SHA-256 mismatch). */
        default void onIntegrityFailure(String reason) { onError(reason); }
        /** ADFA-4676: the user stopped the download; a clean stop, not a failure. */
        default void onCancelled() { }
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

                // ADFA-4676: fetch the .meta4 once — it drives the integrity gate, the
                // --split mirror count, and reconciling whatever is already on disk so that
                // stopping and restarting a download is safe (no wasted re-download, and the
                // network profiler never trips over a leftover completed file).
                MetalinkFile mf = MetalinkSplit.isMetalinkUrl(url) ? fetchMetalink(url) : null;
                // resumeOrRepair: an existing (partial or complete-but-corrupt) file is on
                // disk. Keep it and let the main aria2 run below salvage it via
                // --check-integrity against the .meta4 piece hashes (re-downloading only the
                // damaged/missing 1 MiB pieces). Skip the profiler in that case: it lacks
                // --allow-overwrite/--check-integrity and would trip aria2 errorCode=13.
                boolean resumeOrRepair = false;
                if (mf != null && mf.canVerify()) {
                    File existing = new File(downloadDir, mf.fileName());
                    File control = new File(downloadDir, mf.fileName() + ".aria2");
                    if (existing.isFile()) {
                        long len = existing.length();
                        if (control.isFile()) {
                            // Interrupted download: aria2 keeps its .aria2 control file and
                            // pre-allocates the full size, so length alone cannot tell
                            // "complete" from "in progress". Resume via --continue /
                            // --check-integrity; do not waste a full-file hash here.
                            resumeOrRepair = true;
                            Log.d(TAG, "Interrupted download present (" + len + "/" + mf.sizeBytes()
                                    + " bytes); resuming via --check-integrity.");
                        } else if (len == mf.sizeBytes()
                                && DownloadVerifier.verify(existing, mf.sizeBytes(), mf.sha256()) == DownloadVerifier.Result.OK) {
                            // Fully downloaded and verified (no control file) -> skip download.
                            pruneStaleSiblings(downloadDir, mf.fileName());
                            Log.d(TAG, "Reusing already-verified artifact: " + mf.fileName() + " (skipping download)");
                            mainHandler.post(() -> listener.onComplete(downloadDir.getAbsolutePath()));
                            return;
                        } else if (len > mf.sizeBytes()) {
                            // Larger than the declared size: no piece map can match it. Discard.
                            Log.w(TAG, "Discarding oversized on-disk artifact (len=" + len
                                    + " > expected=" + mf.sizeBytes() + ").");
                            existing.delete();
                        } else {
                            // Complete-but-corrupt with no control file: let --check-integrity
                            // repair only the damaged pieces instead of re-downloading all.
                            resumeOrRepair = true;
                            Log.d(TAG, "Unverified artifact with no control file; will piece-repair via --check-integrity.");
                        }
                    }
                }

                // --- RUN NETWORK PROFILER (Time-boxed speed test) ---
                // UI updates are now handled inside the profiler
                // Skip speed profiling when resuming/repairing an existing file: the
                // profiler cannot write over a file that has no control file.
                boolean forceIpv4 = resumeOrRepair ? false
                        : Aria2NetworkProfiler.shouldForceIpv4(aria2Bin, downloadDir, dhtFile, url, mainHandler, listener);
                // ----------------------------------------------------

                // 3. Build the command dynamically
                java.util.List<String> command = new java.util.ArrayList<>();
                command.add(aria2Bin.getAbsolutePath());
                command.add("--dir=" + downloadDir.getAbsolutePath());
                command.add("--continue=true");
                command.add("--allow-overwrite=true");
                command.add("--auto-file-renaming=false");
                // ADFA-4473: per-server connections stay fixed (polite); --split
                // scales with the number of HTTP mirrors in the metalink (clamp
                // and counting live in MetalinkSplit; fallback to BASE_SPLIT if
                // not a metalink / torrent / unreadable).
                int split = (mf != null) ? MetalinkSplit.splitForMirrorCount(mf.mirrors().size())
                                         : MetalinkSplit.BASE_SPLIT;
                command.add("--max-connection-per-server=" + MetalinkSplit.CONNECTIONS_PER_MIRROR);
                command.add("--split=" + split);
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
                        // ADFA-4830: aria2's DL field is a transfer rate, so append the localized
                        // per-second unit here — the only place that knows the value is a rate. The
                        // profiler's status ("Test IPv4", "✓ IPv6") comes through a different path and
                        // stays unit-free, and the display strings no longer bake the unit.
                        String speed = matcher.group(2) + context.getString(R.string.k2go_rate_per_second);
                        String eta = matcher.group(3);

                        mainHandler.post(() -> listener.onProgress(percent, speed, eta));
                    }
                }

                int exitCode = aria2Process.waitFor();
                Log.d(TAG, "Native Aria2c exited with code: " + exitCode);

                if (isCancelled) {
                    Log.d(TAG, "Download cancelled by user.");
                    mainHandler.post(listener::onCancelled);
                    return;
                }

                if (exitCode != 0) {
                    mainHandler.post(() -> listener.onError("Aria2c native process failed with code " + exitCode));
                    return;
                }
                // ADFA-4676: never trust the exit code alone. For a Metalink download,
                // verify the artifact against the .meta4 <size> + file-level SHA-256
                // before reporting success, so an incomplete/corrupt/stale file cannot
                // reach extraction.
                if (mf != null && mf.canVerify()) {
                    File artifact = new File(downloadDir, mf.fileName());
                    DownloadVerifier.Result vr = DownloadVerifier.verify(artifact, mf.sizeBytes(), mf.sha256());
                    if (vr != DownloadVerifier.Result.OK) {
                        Log.e(TAG, "Integrity check failed (" + vr + ") for " + mf.fileName()
                                + " expectedSize=" + mf.sizeBytes()
                                + " actualSize=" + (artifact.exists() ? artifact.length() : -1));
                        artifact.delete();
                        new File(downloadDir, mf.fileName() + ".aria2").delete();
                        final String reason = vr.name();
                        mainHandler.post(() -> listener.onIntegrityFailure(reason));
                        return;
                    }
                    pruneStaleSiblings(downloadDir, mf.fileName());
                    Log.d(TAG, "Integrity verified: " + mf.fileName() + " (" + mf.sizeBytes() + " bytes, sha-256 OK)");
                }
                mainHandler.post(() -> listener.onComplete(downloadDir.getAbsolutePath()));

            } catch (Exception e) {
                if (isCancelled) {
                    // The stream was closed by our own stopDownload(); a user stop is a
                    // clean cancellation, not a fatal error.
                    Log.d(TAG, "Download cancelled by user.");
                    mainHandler.post(listener::onCancelled);
                    return;
                }
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
    /** ADFA-4676: fetch + parse the .meta4 into a verifiable MetalinkFile, or null on any error. */
    private MetalinkFile fetchMetalink(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "K2Go");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return MetalinkFile.parse(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "fetchMetalink fallback (" + e.getMessage() + ")");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** ADFA-4676: remove any other rootfs tarball so only the verified artifact remains. */
    private static void pruneStaleSiblings(File downloadDir, String keepName) {
        File[] stale = downloadDir.listFiles((d, n) ->
                (n.endsWith(".tar.gz") || n.endsWith(".tar.xz")) && !n.equals(keepName));
        if (stale != null) { for (File f : stale) f.delete(); }
    }

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