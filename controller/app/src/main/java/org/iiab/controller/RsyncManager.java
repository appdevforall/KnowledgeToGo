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
import org.iiab.controller.sync.domain.RsyncProcessMatcher;
import org.iiab.controller.sync.domain.ShareConfig;
import org.iiab.controller.sync.domain.SyncCredentialValidator;
import org.iiab.controller.sync.transport.SecretStore;
import org.iiab.controller.sync.transport.TransportEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;

public class RsyncManager implements TransportEngine {

    private static final String TAG = "IIAB-RsyncManager";
    private volatile Process rsyncProcess;
    private volatile String rsyncBinPath; // ADFA-4539: so stop() can find our per-connection children in /proc
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
            rsyncBinPath = rsyncBin.getAbsolutePath();

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
    public void startClient(Context context, ShareConfig config, String hostIp, int port, String user, String pass, String destinationDir, long expectedTotalBytes, TransportEngine.SyncListener listener) {
        stop();
        isCancelled = false;
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                File nativeLibDir = new File(context.getApplicationInfo().nativeLibraryDir);
                File rsyncBin = new File(nativeLibDir, "librsync.so");
            rsyncBinPath = rsyncBin.getAbsolutePath();

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
                int lastEmittedPct = 0; // ADFA-5160: smoothed percent, never walked back

                while ((line = reader.readLine()) != null) {
                    if (isCancelled) {
                        rsyncProcess.destroy();
                        break;
                    }

                    RsyncProgress progress = RsyncProgress.parse(line);
                    if (progress != null) {
                        // ADFA-5160: rsync's own percent divides by an estimate that keeps growing as
                        // it discovers files, so it lurches. Anchor to the dry-run bytes-to-transfer
                        // (what rsync computed for this transfer up front) and let the transferred-byte
                        // count climb it. Hold at 99% until rsync's success lands; never go backwards.
                        int pct;
                        if (expectedTotalBytes > 0) {
                            pct = (int) Math.min(99L, 100L * progress.bytes / expectedTotalBytes);
                        } else {
                            pct = Math.min(99, progress.percent);
                        }
                        if (pct < lastEmittedPct) pct = lastEmittedPct;
                        lastEmittedPct = pct;
                        String finalFile = lastFile;
                        int finalPct = pct;
                        mainHandler.post(() -> listener.onProgress(finalPct, progress.speed, progress.eta, finalFile));
                    }
                    // PHASE 1 FIX: Strict match for actual rsync errors, ignoring files named "error"
                    //
                    // ADFA-5143: "rsync error:" is only the FINAL summary line. Every per-file failure
                    // rsync prints starts with a bare "rsync:" —
                    //     rsync: [receiver] mkstemp "..." failed: Permission denied (13)
                    //     rsync: [generator] failed to set times on "...": Operation not permitted (1)
                    // — so none of them matched, they all fell through to the branch below, and were
                    // stored as lastFile: filed as the name of the file being transferred and shown to
                    // the user as such. A device log of a code-23 transfer therefore ended with
                    // "some files/attrs were not transferred (see previous errors)" and no previous
                    // errors anywhere, because they had been reclassified as data.
                    //
                    // That is the difference between a benign 23 (attributes Android will not let us
                    // set) and a fatal one (files that did not arrive), and it is the whole reason
                    // nobody can tell those apart today. Logged into LogRepository as well as logcat,
                    // so the failure report a user can send carries them.
                    else if (line.contains("@ERROR:") || line.startsWith("rsync:")
                            || line.startsWith("rsync warning:") || line.contains("rsync error:")) {
                        Log.e(TAG, "[Rsync Output Error] " + line);
                        org.iiab.controller.LogRepository.get().append("[Rsync] " + line);
                    } else if (!line.trim().isEmpty() && !line.startsWith("sending incremental file list") && !line.contains("bytes/sec")) {
                        lastFile = line.trim();
                    }
                }

                int exitCode = rsyncProcess.waitFor();

                secretStore.deleteClientPassword();

                // ADFA-5143: say the verdict out loud. The log used to show a code-23 error line and then
                // a SUCCESS state with nothing in between explaining the jump, which reads like a bug in
                // the app rather than a deliberate rule. RsyncOutcome.isSuccess() counts 0, 23 and 24 as
                // complete on purpose — 24 (files vanished from a live source) is defensible, and 23 is
                // ambiguous: attributes Android refused to set, or files that never arrived. Until the
                // per-file errors above have been read on a real transfer, the rule stays as it is and
                // the log states it.
                Log.i(TAG, "rsync client exit " + exitCode + " → "
                        + RsyncOutcome.classifyTransfer(exitCode)
                        + (RsyncOutcome.isSuccess(exitCode) && exitCode != 0
                            ? " (non-zero treated as complete — see any [Rsync Output Error] lines above)" : ""));
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
                        case KILLED: // ADFA-4496: SIGKILL (137) -> phantom-process killer reaped the transfer
                            mainHandler.post(() -> listener.onError(context.getString(R.string.rsync_error_process_killed)));
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
            rsyncBinPath = rsyncBin.getAbsolutePath();

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
                rsyncProcess.destroy(); // SIGTERM the parent daemon so it stops accepting/forking
            } catch (Exception e) {
                Log.w(TAG, "Error destroying rsync process on stop", e);
            }
        }
        // ADFA-4539: destroy() only signals the parent. The daemon forks a child per
        // connection (--no-detach keeps the parent in foreground); that child keeps
        // streaming the transfer and reparents to init when the parent dies. So sweep
        // /proc and SIGKILL every rsync process that is ours (matched by our unique
        // librsync.so path), which covers the connection children on both the share
        // (daemon) and receive (client) sides.
        killOurRsyncProcesses();
    }

    /**
     * Kill every rsync process launched from our app-private librsync.so, found by
     * scanning /proc. Same-UID kills only (SIGKILL via android.os.Process.killProcess),
     * and never our own app process. Best-effort: unreadable/vanished entries are skipped.
     */
    private void killOurRsyncProcesses() {
        String bin = rsyncBinPath;
        if (bin == null || bin.isEmpty()) {
            return;
        }
        File procDir = new File("/proc");
        File[] pidDirs = procDir.listFiles();
        if (pidDirs == null) {
            return;
        }
        int myPid = android.os.Process.myPid();
        for (File dir : pidDirs) {
            String name = dir.getName();
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException notAPid) {
                continue;
            }
            if (pid == myPid) {
                continue; // never kill the app itself
            }
            String cmdline = readCmdline(new File(dir, "cmdline"));
            if (RsyncProcessMatcher.isOurRsyncProcess(cmdline, bin)) {
                try {
                    android.os.Process.killProcess(pid); // SIGKILL; same UID as us
                    Log.i(TAG, "ADFA-4539: killed lingering rsync pid " + pid);
                } catch (Exception e) {
                    Log.w(TAG, "Could not kill rsync pid " + pid, e);
                }
            }
        }
    }

    /** Read /proc/<pid>/cmdline (NUL-separated argv) as a space-joined string, or null. */
    private String readCmdline(File cmdlineFile) {
        try (FileInputStream in = new FileInputStream(cmdlineFile)) {
            byte[] buf = new byte[4096];
            int total = 0, r;
            while (total < buf.length && (r = in.read(buf, total, buf.length - total)) != -1) {
                total += r;
            }
            if (total == 0) {
                return null;
            }
            return new String(buf, 0, total).replace('\0', ' ').trim();
        } catch (Exception e) {
            return null; // not ours / vanished / no permission
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