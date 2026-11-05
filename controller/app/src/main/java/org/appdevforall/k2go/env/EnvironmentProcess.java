/*
 * ============================================================================
 * Name        : EnvironmentProcess.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5061. Find our environment proot in the host's /proc, and
 *               stop it on request. No container, no socket.
 * ============================================================================
 */
package org.appdevforall.k2go.env;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import org.appdevforall.k2go.env.domain.EnvironmentProcessMatcher;

import java.io.File;
import java.io.FileInputStream;

/**
 * The one fact the app was missing about its own box.
 *
 * <p>Everything else it knows comes from an HTTP ping, which answers "are the services serving".
 * That leaves "the services were stopped and the environment is still up" indistinguishable from
 * "the environment is gone" — and the retry path treats both as "nothing is running", so it will
 * start a second proot on top of a live one.
 *
 * <p>The environment is a child of this process, launched from our app-private native library
 * against our app-private rootfs, so the host's {@code /proc} answers directly. Same technique
 * {@code RsyncManager} already uses to sweep its own lingering children, and for the same reason:
 * a {@code Process} handle only knows about a child this Activity started, while the proot
 * outlives the Activity and, sometimes, the app.
 *
 * <p>Read-only by default. {@link #killOrphan} exists because knowing is not enough on the one
 * path that matters — an environment we did not start and cannot talk to has to go before a new
 * one can come up.
 *
 * <p>{@link #killOrphan} cannot tell an abandoned environment from one that is still booting, so
 * only callers that own that decision use it: {@code ServerLifecycleReconciler} (STOP teardown, and
 * kill-and-relaunch after the boot grace) and {@code ServerController} ({@code EnvironmentEnsure}
 * KILL_AND_RELAUNCH). See ADR-5343.
 */
public final class EnvironmentProcess {

    private static final String TAG = "K2Go-Env";

    private EnvironmentProcess() {
    }

    /** The rootfs proot is pointed at, canonical, or null when it cannot be resolved. */
    private static String rootfsPath(Context ctx) {
        try {
            return new File(ctx.getFilesDir(), "rootfs/installed-rootfs/iiab").getCanonicalPath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The pid of our running environment proot, or {@code -1}.
     *
     * <p>Best-effort by construction: entries vanish while being read, and on modern Android
     * {@code /proc} hides other UIDs — which costs nothing here, since the only process we are
     * looking for is our own child.
     */
    private static int findPid(Context ctx) {
        String rootfs = rootfsPath(ctx);
        if (rootfs == null) {
            return -1;
        }
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return -1;
        }
        int myPid = android.os.Process.myPid();
        for (File dir : entries) {
            int pid;
            try {
                pid = Integer.parseInt(dir.getName());
            } catch (NumberFormatException notAPid) {
                continue;
            }
            if (pid == myPid) {
                continue;
            }
            if (EnvironmentProcessMatcher.isOurEnvironment(readCmdline(new File(dir, "cmdline")), rootfs)) {
                return pid;
            }
        }
        return -1;
    }

    /** Whether an environment proot of ours is alive, whoever started it. */
    public static boolean isRunning(Context ctx) {
        return ctx != null && findPid(ctx) > 0;
    }

    /**
     * Stop an environment proot this process has no handle on.
     *
     * <p>Only for the case that has no other answer: the environment is up, the services are not,
     * and the app cannot reach inside — a proot cannot be entered once started, and the channel
     * that could restart the services is the REST core, which is what is down. So the recovery is
     * to end this one and bring up a fresh one. {@link #reapBox} (the disk guard) also uses it to
     * stop a box whose services are up but filling the disk.
     *
     * <p>Deliberately not {@code killall proot}: that call exists elsewhere in this codebase and
     * would take an install's runrole with it. The matcher requires the environment's own command
     * tail, so a runrole against the same rootfs is not a candidate.
     *
     * @return true when something was signalled
     */
    public static boolean killOrphan(Context ctx) {
        if (ctx == null) {
            return false;
        }
        boolean signalled = false;
        int pid = findPid(ctx);
        if (pid > 0) {
            try {
                android.os.Process.killProcess(pid);   // SIGKILL, same UID as us
                Log.i(TAG, "ADFA-5061: killed an orphaned environment proot, pid " + pid);
                signalled = true;
            } catch (Exception e) {
                Log.w(TAG, "ADFA-5061: could not kill environment pid " + pid, e);
            }
            // Its guests show TracerPid 0 only once the proot has exited.
            awaitExit(pid);
        }
        // K2GO-544: with PTRACE_O_EXITKILL the kernel kills every box process with the proot.
        // K2GO-550: kernels older than 3.8 have no EXITKILL, so reap what outlived it.
        return reapUntraced() > 0 || signalled;
    }

    /**
     * K2GO-550: kill every box process that no proot traces ({@link
     * EnvironmentProcessMatcher#isUntracedGuest}). On Linux >= 3.8 there is none. Best-effort.
     *
     * @return how many processes were signalled
     */
    public static int reapUntraced() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return 0;
        }
        int myPid = android.os.Process.myPid();
        int reaped = 0;
        for (File dir : entries) {
            int pid;
            try {
                pid = Integer.parseInt(dir.getName());
            } catch (NumberFormatException notAPid) {
                continue;
            }
            if (pid == myPid) {
                continue;
            }
            String exe;
            try {
                exe = Os.readlink(dir.getPath() + "/exe");
            } catch (Exception notOurs) {
                continue;   // vanished, or another uid
            }
            if (EnvironmentProcessMatcher.isUntracedGuest(exe, readText(new File(dir, "status")))) {
                try {
                    android.os.Process.killProcess(pid);
                    reaped++;
                } catch (Exception ignored) {
                    // vanished mid-scan
                }
            }
        }
        if (reaped > 0) {
            Log.i(TAG, "K2GO-550: reaped " + reaped + " box process(es) that outlived their proot");
        }
        return reaped;
    }

    /** Wait up to {@link #EXIT_WAIT_MS} until {@code pid} is gone or a zombie. */
    private static void awaitExit(int pid) {
        long deadline = android.os.SystemClock.elapsedRealtime() + EXIT_WAIT_MS;
        File status = new File("/proc/" + pid + "/status");
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            String s = readText(status);
            if (s == null || s.contains("State:\tZ")) {
                return;
            }
            android.os.SystemClock.sleep(50);
        }
    }

    private static final long EXIT_WAIT_MS = 2000;

    /**
     * K2GO-386 (barrier 2): a FULL box teardown. This is the disk-guard's recovery action when free
     * space is critical. Killing the proot is the whole teardown: with PTRACE_O_EXITKILL (K2GO-544)
     * the kernel kills every box service with it; on older kernels {@link #killOrphan} reaps the
     * survivors (K2GO-550). Best-effort and idempotent.
     *
     * @return true when the proot was signalled.
     */
    public static boolean reapBox(Context ctx) {
        return killOrphan(ctx);
    }

    /** A small {@code /proc} file as text, or null if it cannot be read. */
    private static String readText(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[4096];
            int total = 0, r;
            while (total < buf.length && (r = in.read(buf, total, buf.length - total)) != -1) {
                total += r;
            }
            return new String(buf, 0, total);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code /proc/<pid>/cmdline} as a space-joined string, or null if it cannot be read. */
    private static String readCmdline(File cmdline) {
        String s = readText(cmdline);   // null: vanished, or not ours to read
        return (s == null || s.isEmpty()) ? null : s.replace('\0', ' ').trim();
    }
}
