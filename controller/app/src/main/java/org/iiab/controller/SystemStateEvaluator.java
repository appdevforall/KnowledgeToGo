/*
 * ============================================================================
 * Name        : SystemStateEvaluator.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : Derives the app's SystemState from the server-alive flag + the
 *               installed rootfs on disk (ADFA-4578, slice 1). Extracted so the
 *               evaluation can run at app level (MainActivity's status poll),
 *               not only inside DashboardFragment while that tab is visible.
 *               DashboardFragment still evaluates for its own render in slice 1;
 *               readers are consolidated onto ServerStateRepository in slice 2.
 * ============================================================================
 */
package org.iiab.controller;

import android.content.Context;

import java.io.File;

public final class SystemStateEvaluator {

    private SystemStateEvaluator() {
    }

    /** Where the installed rootfs lives, under the app's files dir. */
    private static final String ROOTFS_PATH = "rootfs/installed-rootfs/iiab";

    /** ADFA-5061: the one place the rootfs path is spelled, so a reader elsewhere
     *  does not have to write it out again and drift from this one. */
    public static java.io.File rootfsDir(Context ctx) {
        return new File(ctx.getFilesDir(), ROOTFS_PATH);
    }

    private static volatile String cachedTermuxArch;
    private static volatile String cachedDebianArch;
    private static volatile boolean archCalculated;

    /** True when a system (rootfs) is actually installed on disk — the reliable signal for
     *  whether "Get more" should skip the destructive system step and go straight to content.
     *
     *  <p><b>UI screens: prefer {@code SystemFactsReader.verdict(ctx)} (ADFA-5312).</b> This boolean
     *  folds "an install is in progress" into false (the marker is held), which is correct for boot /
     *  server-start / readiness gates but wrong for display: a screen that branches on it alone shows a
     *  false "no system / Recover" over a system that is present and mid-install. The shared verdict
     *  tells INSTALLING / NO_SYSTEM / DAMAGED / CLONE_* / READY apart so every screen agrees. */
    public static boolean isSystemInstalled(Context ctx) {
        // ADFA-4811: a running (or interrupted) install is not "installed" — the rootfs is
        // half-baked, so callers must not treat it as ready or auto-start the server over it.
        if (InstallGuard.inProgress(ctx)) {
            return false;
        }
        return rootfsPresent(ctx);
    }

    /**
     * ADFA-5119: is there a rootfs on disk at all — asked of the disk, not of the guard.
     *
     * <p>{@link #isSystemInstalled(Context)} answers false for the whole time an install marker is
     * set, which is correct for its callers (do not boot, do not treat as ready) but useless to a
     * caller that needs to know whether there is anything there to boot. Recovery is exactly that
     * caller: the marker is set by definition when it runs, so the flag can only tell it what it
     * already knows.
     *
     * <p>Split out of the method above rather than written beside it, so the two-file test for
     * "a rootfs exists" stays in one place.
     */
    public static boolean rootfsPresent(Context ctx) {
        File rootfsDir = rootfsDir(ctx);
        return new File(rootfsDir, "bin/bash").exists()
                || new File(rootfsDir, "usr/local/pdsm/flag_install_ready").exists();
    }

    /** Server responding → ONLINE; else derive from the rootfs on disk. */
    public static SystemState evaluate(Context ctx, boolean serverAlive) {
        File rootfsDir = rootfsDir(ctx);
        File debianBash = new File(rootfsDir, "bin/bash");
        File flagIiabReady = new File(rootfsDir, "usr/local/pdsm/flag_install_ready");

        if (serverAlive) {
            return SystemState.ONLINE;
        }
        if (flagIiabReady.exists()) {
            return SystemState.OFFLINE;
        }
        if (debianBash.exists()) {
            return SystemState.DEBIAN_ONLY;
        }
        return SystemState.NONE;
    }

    public static String termuxArch(Context ctx) {
        ensureArch(ctx);
        return cachedTermuxArch;
    }

    public static String debianArch(Context ctx) {
        ensureArch(ctx);
        return cachedDebianArch;
    }

    private static synchronized void ensureArch(Context ctx) {
        if (archCalculated) {
            return;
        }
        cachedTermuxArch = getTermuxArch(ctx);
        cachedDebianArch = SystemStatsUtil.getDebianArch(cachedTermuxArch);
        archCalculated = true;
    }

    private static String getTermuxArch(Context ctx) {
        try {
            android.content.pm.ApplicationInfo info = ctx.getApplicationInfo();
            String nativeLibDir = info.nativeLibraryDir;
            if (nativeLibDir != null) {
                if (nativeLibDir.endsWith("arm64") || nativeLibDir.contains("arm64-v8a")) return "arm64-v8a";
                if (nativeLibDir.endsWith("arm") || nativeLibDir.contains("armeabi-v7a")) return "armeabi-v7a";
                if (nativeLibDir.endsWith("x86_64") || nativeLibDir.contains("x86_64")) return "x86_64";
                if (nativeLibDir.endsWith("x86") || nativeLibDir.contains("x86")) return "x86";
            }
        } catch (Exception e) {
            android.util.Log.e("IIAB-SysState", "Error obtaining native architecture", e);
        }
        if (android.os.Build.SUPPORTED_ABIS.length > 0) {
            return android.os.Build.SUPPORTED_ABIS[0];
        }
        return "unknown";
    }
}
