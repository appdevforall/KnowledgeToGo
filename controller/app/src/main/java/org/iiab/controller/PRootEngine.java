/*
 * ============================================================================
 * Name        : PRootEngine.java
 * Author      : IIAB Project
 * Copyright   : Copyright (c) 2026 IIAB Project
 * Description : PRoot container engine for executing Linux binaries
 * ============================================================================
 */

package org.iiab.controller;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.iiab.controller.util.AppExecutors;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.iiab.controller.network.data.FileResolvConfWriter;
import org.iiab.controller.network.data.PrefsDnsConfigRepository;
import org.iiab.controller.network.domain.ApplyDnsUseCase;
import org.iiab.controller.proot.data.PrefsSeccompModeRepository;
import org.iiab.controller.proot.data.ProotEnvironment;
import org.iiab.controller.proot.domain.SeccompFailure;
import org.iiab.controller.proot.domain.SeccompMode;

public class PRootEngine {
    private static final String TAG = "IIAB-PRootEngine";

    /**
     * ADFA-5362: how much of a launch's output to keep for {@link SeccompFailure}. An install
     * streams megabytes, so this is a tail, not a buffer of everything — and a tail is the right
     * end anyway, because the abort we look for is what ends the process.
     */
    private static final int TAIL_LIMIT = 8192;

    private volatile Process currentProcess;
    private volatile java.io.OutputStream processOutputStream;

    public interface OutputListener {
        void onOutputLine(String line);

        void onProcessExit(int exitCode);

        void onError(String error);
    }

    public void executeInContainer(Context context, String rootfsDir, String command, OutputListener listener) {
        new Thread(() -> {
            // ADFA-5362: how proot must run here is remembered per device, defaulting to the fast path.
            PrefsSeccompModeRepository capability = new PrefsSeccompModeRepository(context);
            SeccompMode mode = capability.load();
            try {
                File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
                File prootBinary = new File(nativeDir, "libproot.so");
                File loaderBinary = new File(nativeDir, "libproot-loader.so");

                if (!prootBinary.exists()) {
                    throw new Exception("libproot.so not found in native library directory!");
                }

                // Single DNS injection point: every proot launch passes through here, so we write
                // the effective DNS (user's custom config when enabled, else defaults) into the
                // guest's resolv.conf BEFORE launching. Replaces the scattered writes that used to
                // live in DeployFragment / MainActivity.
                try {
                    new ApplyDnsUseCase(
                            new PrefsDnsConfigRepository(context),
                            new FileResolvConfWriter()
                    ).execute(new File(rootfsDir));
                } catch (Exception dnsEx) {
                    Log.w(TAG, "DNS apply skipped: " + dnsEx.getMessage());
                }

                // =========================================================
                // THE W^X TROJAN HORSE: FAKE TERMUX PREFIX
                // =========================================================
                File prefixDir = new File(context.getFilesDir(), "usr");
                File libexecDir = new File(prefixDir, "libexec/proot");
                if (!libexecDir.exists()) libexecDir.mkdirs();

                try {
                    // Create Symlink for 64-bit loader
                    File symLoader = new File(libexecDir, "loader");
                    if (symLoader.exists()) symLoader.delete();
                    if (loaderBinary.exists()) {
                        android.system.Os.symlink(loaderBinary.getAbsolutePath(), symLoader.getAbsolutePath());
                    }

                    // Create Symlink for 32-bit loader (if present)
                    File loader32Binary = new File(nativeDir, "libproot-loader32.so");
                    if (loader32Binary.exists()) {
                        File symLoader32 = new File(libexecDir, "loader32");
                        if (symLoader32.exists()) symLoader32.delete();
                        android.system.Os.symlink(loader32Binary.getAbsolutePath(), symLoader32.getAbsolutePath());
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Symlink creation failed. Relying on fallback mechanisms.", e);
                }

                // =========================================================
                // BUILD PROOT ARGUMENTS
                // =========================================================
                List<String> args = new ArrayList<>();
                args.add(prootBinary.getAbsolutePath());

                String canonicalRootfs = new File(rootfsDir).getCanonicalPath();

                args.add("--sysvipc");
                args.add("-0");
                args.add("--link2symlink");
                args.add("--kill-on-exit");
                args.add("-k");
                args.add("6.17.0-PRoot-IIAB");
                args.add("-r");
                args.add(canonicalRootfs);

                args.add("-b");
                args.add("/dev");
                args.add("-b");
                args.add("/proc");
                args.add("-b");
                args.add("/sys");

                // ADFA-4435: Ansible strategy plugins use multiprocessing.Semaphore, which
                // needs a writable POSIX shared-memory mount. Android's /dev has no shm and
                // "-b /dev" shadows the guest's, so bind a writable host dir over /dev/shm
                // (a later, more specific bind overrides "-b /dev"). Without it the install
                // dies instantly: "Unable to use multiprocessing ... lack of access to /dev/shm".
                File prootShmHost = new File(context.getFilesDir(), "proot_shm");
                if (!prootShmHost.exists()) prootShmHost.mkdirs();
                args.add("-b");
                args.add(prootShmHost.getCanonicalPath() + ":/dev/shm");

                File fakeProcDir = new File(canonicalRootfs, "proc");
                File fUptime = new File(fakeProcDir, ".uptime");
                File fVersion = new File(fakeProcDir, ".version");
                File fStat = new File(fakeProcDir, ".stat");
                File fLoad = new File(fakeProcDir, ".loadavg");

                if (fUptime.exists()) {
                    args.add("-b");
                    args.add(fUptime.getAbsolutePath() + ":/proc/uptime");
                }
                if (fVersion.exists()) {
                    args.add("-b");
                    args.add(fVersion.getAbsolutePath() + ":/proc/version");
                }
                if (fStat.exists()) {
                    args.add("-b");
                    args.add(fStat.getAbsolutePath() + ":/proc/stat");
                }
                if (fLoad.exists()) {
                    args.add("-b");
                    args.add(fLoad.getAbsolutePath() + ":/proc/loadavg");
                }

                File sdcard = android.os.Environment.getExternalStorageDirectory();
                args.add("-b");
                args.add(sdcard.getAbsolutePath() + ":/sdcard");

                // 3. Robust Temp directory management
                File prootTmpHost = new File(context.getFilesDir(), "proot_tmp");
                if (!prootTmpHost.exists()) prootTmpHost.mkdirs();
                String prootTmpPath = prootTmpHost.getCanonicalPath();

                // Map the host folder to /tmp within the guest OS (Standard Linux)
                args.add("-b");
                args.add(prootTmpPath + ":/tmp");

                // 4. Set Working Directory
                args.add("-w");
                args.add("/root");

                // 5. The Command to Execute (DIRECT BASH INVOCATION)
                args.add("/bin/bash");
                args.add("-l");
                args.add("-c");
                args.add(command);

                Log.d(TAG, "Executing PRoot command: " + String.join(" ", args));

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.redirectErrorStream(true);

                // =========================================================
                // INJECT ENVIRONMENT VARIABLES
                // =========================================================
                // ADFA-5362: the environment is built in one place for every proot launch, so the
                // app and the terminal cannot drift apart on the same phone.
                pb.environment().clear(); // Clean toxic Android vars
                pb.environment().putAll(ProotEnvironment.build(
                        nativeDir, prefixDir.getCanonicalPath(), prootTmpPath, mode));

                // ADFA-4458: wait on THIS call's process, not the shared field, so a
                // concurrent proot call can't make us return on the wrong process.
                Process proc = pb.start();
                currentProcess = proc;

                // --- STREAM LIVE LOGS ---
                BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line;
                Handler mainHandler = new Handler(Looper.getMainLooper());
                StringBuilder tail = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    final String outLine = line;
                    Log.i("IIAB-Ansible", "[Debian] " + outLine); // Funneled straight to Logcat!
                    appendTail(tail, outLine);
                    mainHandler.post(() -> listener.onOutputLine(outLine));
                }

                int exitCode = proc.waitFor();

                // ADFA-5362: proot has told us this kernel cannot run it with seccomp. Record it and
                // report the failure as it happened — deliberately nothing relaunches here. The
                // lifecycle owner already relaunches on its own tick whenever the environment is not
                // alive (ServerLifecycleReconciler), and it will read the new mode when it does.
                // Relaunching from inside the engine would be a second actuation path that does not
                // consult `desired`, so a stop landing in that window would be overruled.
                if (mode == SeccompMode.FILTER
                        && SeccompFailure.isSeccompAbort(exitCode, tail.toString())) {
                    Log.w(TAG, "ADFA-5362: proot cannot use seccomp on this kernel — remembering"
                            + " PROOT_NO_SECCOMP for this build; the next launch will use it");
                    capability.remember(SeccompMode.DISABLED);
                }

                mainHandler.post(() -> listener.onProcessExit(exitCode));

            } catch (Exception e) {
                Log.e(TAG, "PRoot execution failed", e);
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            }
        }).start();
    }

    /** Keep only the last {@link #TAIL_LIMIT} characters of the output. */
    private static void appendTail(StringBuilder tail, String line) {
        tail.append(line).append('\n');
        if (tail.length() > TAIL_LIMIT) {
            tail.delete(0, tail.length() - TAIL_LIMIT);
        }
    }

    /**
     * Start an interactive bash session that stays live.
     *
     * <p>ADFA-5362: this <em>consumes</em> the remembered mode but does not learn one. A terminal is
     * opened long after the environment has launched at least once, so the verdict is already known
     * by the time anyone gets here; and a live session has no exit to read a verdict from.
     */
    public void startInteractiveShell(Context context, String rootfsDir, OutputListener listener) {
        new Thread(() -> {
            try {
                File nativeDir = new File(context.getApplicationInfo().nativeLibraryDir);
                File prootBinary = new File(nativeDir, "libproot.so");
                File loaderBinary = new File(nativeDir, "libproot-loader.so");

                if (!prootBinary.exists()) throw new Exception("libproot.so not found!");

                File prefixDir = new File(context.getFilesDir(), "usr");
                File libexecDir = new File(prefixDir, "libexec/proot");
                if (!libexecDir.exists()) libexecDir.mkdirs();

                try {
                    File symLoader = new File(libexecDir, "loader");
                    if (symLoader.exists()) symLoader.delete();
                    if (loaderBinary.exists())
                        android.system.Os.symlink(loaderBinary.getAbsolutePath(), symLoader.getAbsolutePath());

                    File loader32Binary = new File(nativeDir, "libproot-loader32.so");
                    if (loader32Binary.exists()) {
                        File symLoader32 = new File(libexecDir, "loader32");
                        if (symLoader32.exists()) symLoader32.delete();
                        android.system.Os.symlink(loader32Binary.getAbsolutePath(), symLoader32.getAbsolutePath());
                    }
                } catch (Exception ignored) {
                }

                List<String> args = new ArrayList<>();
                args.add(prootBinary.getAbsolutePath());
                String canonicalRootfs = new File(rootfsDir).getCanonicalPath();

                args.add("--sysvipc");
                args.add("-0");
                args.add("--link2symlink");
                args.add("--kill-on-exit");
                args.add("-k");
                args.add("6.17.0-PRoot-IIAB");
                args.add("-r");
                args.add(canonicalRootfs);

                args.add("-b");
                args.add("/dev");
                args.add("-b");
                args.add("/proc");
                args.add("-b");
                args.add("/sys");

                // ADFA-4435: Ansible strategy plugins use multiprocessing.Semaphore, which
                // needs a writable POSIX shared-memory mount. Android's /dev has no shm and
                // "-b /dev" shadows the guest's, so bind a writable host dir over /dev/shm
                // (a later, more specific bind overrides "-b /dev"). Without it the install
                // dies instantly: "Unable to use multiprocessing ... lack of access to /dev/shm".
                File prootShmHost = new File(context.getFilesDir(), "proot_shm");
                if (!prootShmHost.exists()) prootShmHost.mkdirs();
                args.add("-b");
                args.add(prootShmHost.getCanonicalPath() + ":/dev/shm");

                File sdcard = android.os.Environment.getExternalStorageDirectory();
                args.add("-b");
                args.add(sdcard.getAbsolutePath() + ":/sdcard");

                File prootTmpHost = new File(context.getFilesDir(), "proot_tmp");
                if (!prootTmpHost.exists()) prootTmpHost.mkdirs();
                String prootTmpPath = prootTmpHost.getCanonicalPath();

                args.add("-b");
                args.add(prootTmpPath + ":/tmp");

                args.add("-w");
                args.add("/root");

                // INVOKE INTERACTIVE BASH (-i)
                args.add("/bin/bash");
                args.add("-i");

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.redirectErrorStream(true);

                // ADFA-5362: same single builder as executeInContainer.
                pb.environment().clear();
                pb.environment().putAll(ProotEnvironment.build(
                        nativeDir, prefixDir.getCanonicalPath(), prootTmpPath,
                        new PrefsSeccompModeRepository(context).load()));

                // ADFA-4458: wait on THIS call's process, not the shared field, so a
                // concurrent proot call can't make us return on the wrong process.
                Process proc = pb.start();
                currentProcess = proc;

                // WE SAVE THE WRITING CHANNEL
                processOutputStream = proc.getOutputStream();

                // WE READ IN BLOCKS (To avoid getting stuck waiting for a line break at the prompt)
                java.io.InputStream is = proc.getInputStream();
                byte[] buffer = new byte[1024];
                int read;
                Handler mainHandler = new Handler(Looper.getMainLooper());

                while ((read = is.read(buffer)) != -1) {
                    final String outputChunk = new String(buffer, 0, read);
                    mainHandler.post(() -> listener.onOutputLine(outputChunk));
                }

                int exitCode = proc.waitFor();
                mainHandler.post(() -> listener.onProcessExit(exitCode));

            } catch (Exception e) {
                Log.e(TAG, "PRoot Interactive execution failed", e);
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * Sends a command string to the active interactive shell process.
     */
    public void writeToShell(String command) {
        if (processOutputStream != null) {
            new Thread(() -> {
                try {
                    // We added the line break to simulate the "Enter" key
                    processOutputStream.write((command + "\n").getBytes());
                    processOutputStream.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write command to shell", e);
                }
            }).start();
        }
    }

    public void killProcess() {
        final Process p = currentProcess;
        if (p == null) return;
        p.destroy();
        // Reap the child off the caller's thread so it does not linger as a
        // zombie / hold proot mounts. Process.waitFor(timeout) is API 26+ and
        // minSdk is 24, so we drain on the shared io() executor instead of
        // blocking the caller.
        AppExecutors.get().io().execute(() -> {
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}