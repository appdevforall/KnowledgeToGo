/*
 * ============================================================================
 * Name        : EnvironmentProcess.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5061. Whether our environment proot is running, read from
 *               the host's /proc. No container, no socket, nothing mutated.
 * ============================================================================
 */
package org.iiab.controller.env;

import android.content.Context;
import android.util.Log;

import org.iiab.controller.env.domain.EnvironmentProcessMatcher;

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
 * <p><b>Not wired yet, deliberately.</b> {@code ServerController.startEnvironment} called
 * {@link #killOrphan} for one build and it was removed after a device run: it cannot tell an
 * abandoned environment from one this same process started seconds earlier, and it killed a proot
 * 3.5 s into its own boot. Detection is the easy half. The rest — a handle held per process rather
 * than per Activity, "ensure it is up" instead of "start", and a grace period while services are
 * still coming up — is the ticket this class was written for. Read {@link #isRunning} freely; do
 * not reach for {@link #killOrphan} until that decision exists to hold it.
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
     * to end this one and bring up a fresh one.
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
        int pid = findPid(ctx);
        if (pid <= 0) {
            return false;
        }
        try {
            android.os.Process.killProcess(pid);   // SIGKILL, same UID as us
            Log.i(TAG, "ADFA-5061: killed an orphaned environment proot, pid " + pid);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ADFA-5061: could not kill environment pid " + pid, e);
            return false;
        }
    }

    /** {@code /proc/<pid>/cmdline} as a space-joined string, or null if it cannot be read. */
    private static String readCmdline(File cmdline) {
        try (FileInputStream in = new FileInputStream(cmdline)) {
            byte[] buf = new byte[4096];
            int total = 0, r;
            while (total < buf.length && (r = in.read(buf, total, buf.length - total)) != -1) {
                total += r;
            }
            return total == 0 ? null : new String(buf, 0, total).replace('\0', ' ').trim();
        } catch (Exception e) {
            return null;   // vanished, or not ours to read
        }
    }
}
