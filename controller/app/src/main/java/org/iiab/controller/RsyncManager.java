/*
 * ============================================================================
 * Name        : RsyncManager.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : Java wrapper for the native librsync.so binary
 * ============================================================================
 */

package org.iiab.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.iiab.controller.sync.domain.SyncCredentialValidator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RsyncManager {

    private static final String TAG = "IIAB-RsyncManager";
    private volatile Process rsyncProcess;
    private boolean isCancelled = false;

    private static final String SYNC_MODULE_NAME = "iiab_sync";

    public interface SyncListener {
        void onProgress(int percentage, String speed, String eta, String currentFile);

        void onComplete(String message);

        void onError(String error);
    }

    public boolean startServer(Context context, int port, String user, String pass, String dirToShare) {
        stop();
        isCancelled = false;

        // S1 (defence in depth): never write attacker-controllable text into
        // rsyncd.conf without validation. user/pass are app-generated and
        // dirToShare is app-controlled, but a stray CR/LF here would let new
        // config directives or module sections be injected.
        if (!SyncCredentialValidator.isValidUsername(user)
                || !SyncCredentialValidator.isValidPassword(pass)
                || !SyncCredentialValidator.isValidPort(port)
                || !SyncCredentialValidator.isSafeConfigValue(dirToShare)) {
            Log.e(TAG, "Refusing to start rsync daemon: invalid credentials or share path");
            return false;
        }

        try {
            File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
            File rsyncBin = new File(nativeLibDir, "librsync.so");

            if (!rsyncBin.exists()) {
                Log.e(TAG, context.getString(R.string.rsync_error_binary_missing));
                return false;
            }

            File cacheDir = context.getCacheDir();
            File configFile = new File(cacheDir, "rsyncd.conf");
            File secretsFile = new File(cacheDir, "rsyncd.secrets");
            File pidFile = new File(cacheDir, "rsyncd.pid");
            File lockFile = new File(cacheDir, "rsyncd.lock");

            if (pidFile.exists()) {
                pidFile.delete();
            }

            writeTextToFile(secretsFile, user + ":" + pass);

            secretsFile.setExecutable(false, false);
            secretsFile.setReadable(false, false);
            secretsFile.setWritable(false, false);
            secretsFile.setReadable(true, true);
            secretsFile.setWritable(true, true);

            // PHASE 1 FIX: Added 'max connections = 3' to protect I/O bottleneck
            String configContent =
                    "pid file = " + pidFile.getAbsolutePath() + "\n" +
                            "lock file = " + lockFile.getAbsolutePath() + "\n" +
                            "port = " + port + "\n" +
                            "use chroot = no\n" +
                            "log file = /dev/null\n" +
                            "reverse lookup = no\n" +
                            "\n" +
                            "[" + SYNC_MODULE_NAME + "]\n" +
                            "path = " + dirToShare + "\n" +
                            "comment = IIAB Peer-to-Peer Sync\n" +
                            "read only = yes\n" +
                            "timeout = 300\n" +
                            "max connections = 3\n" +
                            "auth users = " + user + "\n" +
                            "secrets file = " + secretsFile.getAbsolutePath() + "\n";

            writeTextToFile(configFile, configContent);

            ProcessBuilder pb = new ProcessBuilder(
                    rsyncBin.getAbsolutePath(),
                    "--daemon",
                    "--no-detach",
                    "--config=" + configFile.getAbsolutePath()
            );

            pb.redirectErrorStream(true);
            rsyncProcess = pb.start();
            Log.i(TAG, "Rsync Daemon started on port " + port);

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start Rsync Daemon", e);
            return false;
        }
    }

    public void startClient(Context context, String hostIp, int port, String user, String pass, String destinationDir, SyncListener listener) {
        stop();
        isCancelled = false;
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
                File rsyncBin = new File(nativeLibDir, "librsync.so");

                if (!rsyncBin.exists()) {
                    throw new Exception(context.getString(R.string.rsync_error_binary_missing));
                }

                // S1: reject credentials that could break out of the rsync:// URL.
                if (!SyncCredentialValidator.validateCredentials(hostIp, port, user, pass).valid) {
                    mainHandler.post(() -> listener.onError(
                            context.getString(R.string.rsync_error_invalid_credentials)));
                    return;
                }

                File passFile = new File(context.getCacheDir(), "rsync_client.pass");
                writeTextToFile(passFile, pass);

                passFile.setExecutable(false, false);
                passFile.setReadable(false, false);
                passFile.setWritable(false, false);
                passFile.setReadable(true, true);
                passFile.setWritable(true, true);

                String remoteUrl = "rsync://" + user + "@" + hostIp + ":" + port + "/" + SYNC_MODULE_NAME + "/";

                ProcessBuilder pb = new ProcessBuilder(
                        rsyncBin.getAbsolutePath(),
                        "-av",
                        "--delete",
                        "--info=progress2",
                        "--partial",
                        "--password-file=" + passFile.getAbsolutePath(),
                        remoteUrl,
                        destinationDir
                );

                pb.redirectErrorStream(true);
                rsyncProcess = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(rsyncProcess.getInputStream()));
                String line;

                Pattern progressPattern = Pattern.compile("(\\d+)%\\s+([\\d\\.]+[a-zA-Z/s]+)\\s+([\\d:]+)");
                String lastFile = "";

                while ((line = reader.readLine()) != null) {
                    if (isCancelled) {
                        rsyncProcess.destroy();
                        break;
                    }

                    Matcher matcher = progressPattern.matcher(line);
                    if (matcher.find()) {
                        try {
                            int percent = Integer.parseInt(matcher.group(1));
                            String speed = matcher.group(2);
                            String eta = matcher.group(3);
                            String finalFile = lastFile;
                            mainHandler.post(() -> listener.onProgress(percent, speed, eta, finalFile));
                        } catch (Exception ignored) {
                        }
                    }
                    // PHASE 1 FIX: Strict match for actual rsync errors, ignoring files named "error"
                    else if (line.contains("@ERROR:") || line.contains("rsync error:")) {
                        Log.e(TAG, "[Rsync Output Error] " + line);
                    } else if (!line.trim().isEmpty() && !line.startsWith("sending incremental file list") && !line.contains("bytes/sec")) {
                        lastFile = line.trim();
                    }
                }

                int exitCode = rsyncProcess.waitFor();

                if (passFile.exists()) passFile.delete();

                if (isCancelled) {
                    mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_cancelled)));
                } else if (exitCode == 0 || exitCode == 23 || exitCode == 24) {
                    mainHandler.post(() -> listener.onComplete(context.getString(R.string.rsync_success_complete)));
                }
                // PHASE 1 FIX: Clean handling of unexpected server drops (Codes 10, 12, 20 are socket/stream errors)
                else if (exitCode == 10 || exitCode == 12 || exitCode == 20) {
                    mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_host_dropped)));
                } else {
                    mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_exit_code, exitCode)));
                }

            } catch (Exception e) {
                Log.e(TAG, "Rsync Client Exception", e);
                mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_fatal, e.getMessage())));
            }
        }).start();
    }

    public interface DryRunListener {
        void onCalculated(long bytesToTransfer);

        void onError(String error);
    }

    public void calculateTransferPlan(Context context, String hostIp, int port, String user, String pass, String destinationDir, DryRunListener listener) {
        stop();
        isCancelled = false;
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
                File rsyncBin = new File(nativeLibDir, "librsync.so");

                if (!rsyncBin.exists())
                    throw new Exception(context.getString(R.string.rsync_error_binary_missing));

                // S1: reject credentials that could break out of the rsync:// URL.
                if (!SyncCredentialValidator.validateCredentials(hostIp, port, user, pass).valid) {
                    mainHandler.post(() -> listener.onError(
                            context.getString(R.string.rsync_error_invalid_credentials)));
                    return;
                }

                File passFile = new File(context.getCacheDir(), "rsync_client.pass");
                writeTextToFile(passFile, pass);
                passFile.setExecutable(false, false);
                passFile.setReadable(false, false);
                passFile.setWritable(false, false);
                passFile.setReadable(true, true);
                passFile.setWritable(true, true);

                String remoteUrl = "rsync://" + user + "@" + hostIp + ":" + port + "/" + SYNC_MODULE_NAME + "/";

                ProcessBuilder pb = new ProcessBuilder(
                        rsyncBin.getAbsolutePath(),
                        "-av",
                        "--delete",
                        "--dry-run",
                        "--stats",
                        "--password-file=" + passFile.getAbsolutePath(),
                        remoteUrl,
                        destinationDir
                );

                pb.redirectErrorStream(true);
                rsyncProcess = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(rsyncProcess.getInputStream()));
                String line;
                long totalTransferredBytes = 0;

                Pattern statsPattern = Pattern.compile("Total transferred file size:\\s+([\\d,\\.]+)\\s+bytes");

                while ((line = reader.readLine()) != null) {
                    if (isCancelled) {
                        rsyncProcess.destroy();
                        break;
                    }

                    Matcher matcher = statsPattern.matcher(line);
                    if (matcher.find()) {
                        String cleanNumber = matcher.group(1).replaceAll("[,\\.]", "");
                        totalTransferredBytes = Long.parseLong(cleanNumber);
                    }
                }

                int exitCode = rsyncProcess.waitFor();
                if (passFile.exists()) passFile.delete();

                if (isCancelled) {
                    mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_dry_run_cancelled)));
                } else if (exitCode == 0 || exitCode == 23 || exitCode == 24) {
                    final long finalBytes = totalTransferredBytes;
                    mainHandler.post(() -> listener.onCalculated(finalBytes));
                } else {
                    mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_dry_run_failed, exitCode)));
                }

            } catch (Exception e) {
                Log.e(TAG, "Rsync Dry-Run Exception", e);
                mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_dry_run_fatal, e.getMessage())));
            }
        }).start();
    }

    public void stop() {
        isCancelled = true;
        if (rsyncProcess != null) {
            try {
                rsyncProcess.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    private void writeTextToFile(File file, String text) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        PrintWriter pw = new PrintWriter(fos);
        pw.print(text);
        pw.flush();
        pw.close();
        fos.close();
    }
}