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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.iiab.controller.network.data.FileResolvConfWriter;
import org.iiab.controller.network.data.PrefsDnsConfigRepository;
import org.iiab.controller.network.domain.ApplyDnsUseCase;

public class PRootEngine {
    private static final String TAG = "IIAB-PRootEngine";
    private Process currentProcess;
    private java.io.OutputStream processOutputStream;

    public interface OutputListener {
        void onOutputLine(String line);

        void onProcessExit(int exitCode);

        void onError(String error);
    }

    public void executeInContainer(Context context, String rootfsDir, String command, OutputListener listener) {
        new Thread(() -> {
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
                pb.environment().clear(); // Clean toxic Android vars

                // Tell PRoot where to find our Fake Termux Prefix
                pb.environment().put("PREFIX", prefixDir.getCanonicalPath());

                // Fallback environment variables for modern PRoot versions
                if (loaderBinary.exists())
                    pb.environment().put("PROOT_LOADER", loaderBinary.getAbsolutePath());
                File loader32 = new File(nativeDir, "libproot-loader32.so");
                if (loader32.exists())
                    pb.environment().put("PROOT_LOADER_32", loader32.getAbsolutePath());

                pb.environment().put("PROOT_TMP_DIR", prootTmpPath);
                pb.environment().put("TMPDIR", "/tmp");
                pb.environment().put("HOME", "/root");
                pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
                pb.environment().put("TERM", "xterm-256color");
                pb.environment().put("LANG", "C.UTF-8");
                pb.environment().put("USER", "root");
                pb.environment().put("LOGNAME", "root");

                currentProcess = pb.start();

                // --- STREAM LIVE LOGS ---
                BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
                String line;
                Handler mainHandler = new Handler(Looper.getMainLooper());

                while ((line = reader.readLine()) != null) {
                    final String outLine = line;
                    Log.i("IIAB-Ansible", "[Debian] " + outLine); // Funneled straight to Logcat!
                    mainHandler.post(() -> listener.onOutputLine(outLine));
                }

                int exitCode = currentProcess.waitFor();

                mainHandler.post(() -> listener.onProcessExit(exitCode));

            } catch (Exception e) {
                Log.e(TAG, "PRoot execution failed", e);
                new Handler(Looper.getMainLooper()).post(() -> listener.onError(e.getMessage()));
            }
        }).start();
    }

    /**
     * Start an interactive bash session that stays live.
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

                pb.environment().clear();
                pb.environment().put("PREFIX", prefixDir.getCanonicalPath());
                if (loaderBinary.exists())
                    pb.environment().put("PROOT_LOADER", loaderBinary.getAbsolutePath());
                File loader32 = new File(nativeDir, "libproot-loader32.so");
                if (loader32.exists())
                    pb.environment().put("PROOT_LOADER_32", loader32.getAbsolutePath());

                pb.environment().put("PROOT_TMP_DIR", prootTmpPath);
                pb.environment().put("TMPDIR", "/tmp");
                pb.environment().put("HOME", "/root");
                pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
                pb.environment().put("TERM", "xterm-256color");
                pb.environment().put("LANG", "C.UTF-8");
                pb.environment().put("USER", "root");
                pb.environment().put("LOGNAME", "root");

                currentProcess = pb.start();

                // WE SAVE THE WRITING CHANNEL
                processOutputStream = currentProcess.getOutputStream();

                // WE READ IN BLOCKS (To avoid getting stuck waiting for a line break at the prompt)
                java.io.InputStream is = currentProcess.getInputStream();
                byte[] buffer = new byte[1024];
                int read;
                Handler mainHandler = new Handler(Looper.getMainLooper());

                while ((read = is.read(buffer)) != -1) {
                    final String outputChunk = new String(buffer, 0, read);
                    mainHandler.post(() -> listener.onOutputLine(outputChunk));
                }

                int exitCode = currentProcess.waitFor();
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
        if (currentProcess != null) {
            currentProcess.destroy();
        }
    }
}