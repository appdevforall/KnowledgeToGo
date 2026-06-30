/*
 * ============================================================================
 * Name        : RsyncManager.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Java wrapper for the native librsync.so binary
 * ============================================================================
 */

package org.iiab.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.iiab.controller.sync.domain.RsyncConfig;
import org.iiab.controller.sync.domain.RsyncOutcome;
import org.iiab.controller.sync.domain.RsyncProgress;
import org.iiab.controller.sync.domain.ShareConfig;
import org.iiab.controller.sync.domain.SyncCredentialValidator;
import org.iiab.controller.sync.transport.SecretStore;
import org.iiab.controller.sync.transport.TransportEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;

public class RsyncManager implements TransportEngine {

    private static final String TAG = "IIAB-RsyncManager";
    private volatile Process rsyncProcess;
    private volatile boolean isCancelled = false; // S10: read/written across threads
    private SecretStore secretStore;

    @Override
    public boolean startServer(Context context, ShareConfig config, String pass, String dirToShare) {
        stop();
        isCancelled = false;

        // S1 (defence in depth): never write attacker-controllable text into
        // rsyncd.conf without validation. user/pass are app-generated and
        // dirToShare is app-controlled, but a stray CR/LF here would let new
        // config directives or module sections be injected.
        if (!SyncCredentialValidator.isValidUsername(config.user)
                || !SyncCredentialValidator.isValidPassword(pass)
                || !SyncCredentialValidator.isValidPort(config.rsyncPort)
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
            File pidFile = new File(cacheDir, "rsyncd.pid");
            File lockFile = new File(cacheDir, "rsyncd.lock");

            if (pidFile.exists()) {
                pidFile.delete();
            }

            secretStore = new SecretStore(cacheDir);
            File secretsFile = secretStore.writeServerSecrets(config.user, pass);

            // PHASE 1 FIX: 'max connections = 3' protects the I/O bottleneck.
            // Config/argv assembly lives in the pure RsyncConfig domain (S14 step 1).
            String configContent = RsyncConfig.buildDaemonConf(
                    pidFile.getAbsolutePath(), lockFile.getAbsolutePath(), config.rsyncPort,
                    config.moduleName, dirToShare, config.user, secretsFile.getAbsolutePath());

            writeTextToFile(configFile, configContent);

            ProcessBuilder pb = new ProcessBuilder(
                    RsyncConfig.serverArgs(rsyncBin.getAbsolutePath(), configFile.getAbsolutePath()));

            pb.redirectErrorStream(true);
            rsyncProcess = pb.start();
            Log.i(TAG, "Rsync Daemon started on port " + config.rsyncPort);

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to start Rsync Daemon", e);
            return false;
        }
    }

    @Override
    public void startClient(Context context, ShareConfig config, String hostIp, int port, String user, String pass, String destinationDir, TransportEngine.SyncListener listener) {
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

                secretStore = new SecretStore(context.getCacheDir());
                File passFile = secretStore.writeClientPassword(pass);

                String remoteUrl = RsyncConfig.buildRemoteUrl(user, hostIp, port, config.moduleName);

                ProcessBuilder pb = new ProcessBuilder(RsyncConfig.clientArgs(
                        rsyncBin.getAbsolutePath(), passFile.getAbsolutePath(), remoteUrl, destinationDir));

                pb.redirectErrorStream(true);
                rsyncProcess = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(rsyncProcess.getInputStream()));
                String line;

                String lastFile = "";

                while ((line = reader.readLine()) != null) {
                    if (isCancelled) {
                        rsyncProcess.destroy();
                        break;
                    }

                    RsyncProgress progress = RsyncProgress.parse(line);
                    if (progress != null) {
                        String finalFile = lastFile;
                        mainHandler.post(() -> listener.onProgress(progress.percent, progress.speed, progress.eta, finalFile));
                    }
                    // PHASE 1 FIX: Strict match for actual rsync errors, ignoring files named "error"
                    else if (line.contains("@ERROR:") || line.contains("rsync error:")) {
                        Log.e(TAG, "[Rsync Output Error] " + line);
                    } else if (!line.trim().isEmpty() && !line.startsWith("sending incremental file list") && !line.contains("bytes/sec")) {
                        lastFile = line.trim();
                    }
                }

                int exitCode = rsyncProcess.waitFor();

                secretStore.deleteClientPassword();

                if (isCancelled) {
                    mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_cancelled)));
                } else {
                    switch (RsyncOutcome.classifyTransfer(exitCode)) {
                        case COMPLETE:
                            mainHandler.post(() -> listener.onComplete(context.getString(R.string.rsync_success_complete)));
                            break;
                        case HOST_DROPPED: // socket/stream drop (10/12/20)
                            mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_host_dropped)));
                            break;
                        default:
                            mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_exit_code, exitCode)));
                            break;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Rsync Client Exception", e);
                mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_fatal, e.getMessage())));
            }
        }).start();
    }

    @Override
    public void calculateTransferPlan(Context context, ShareConfig config, String hostIp, int port, String user, String pass, String destinationDir, TransportEngine.DryRunListener listener) {
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

                secretStore = new SecretStore(context.getCacheDir());
                File passFile = secretStore.writeClientPassword(pass);

                String remoteUrl = RsyncConfig.buildRemoteUrl(user, hostIp, port, config.moduleName);

                ProcessBuilder pb = new ProcessBuilder(RsyncConfig.dryRunArgs(
                        rsyncBin.getAbsolutePath(), passFile.getAbsolutePath(), remoteUrl, destinationDir));

                pb.redirectErrorStream(true);
                rsyncProcess = pb.start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(rsyncProcess.getInputStream()));
                String line;
                long totalTransferredBytes = 0;

                while ((line = reader.readLine()) != null) {
                    if (isCancelled) {
                        rsyncProcess.destroy();
                        break;
                    }
                    totalTransferredBytes = RsyncProgress.parseTransferredBytes(line, totalTransferredBytes);
                }

                int exitCode = rsyncProcess.waitFor();
                secretStore.deleteClientPassword();

                if (isCancelled) {
                    mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_dry_run_cancelled)));
                } else if (RsyncOutcome.isSuccess(exitCode)) {
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

    @Override
    public void stop() {
        isCancelled = true;
        if (secretStore != null) secretStore.clear(); // S11: don't let secrets outlive the session
        if (rsyncProcess != null) {
            try {
                rsyncProcess.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error destroying rsync process on stop", e);
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