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

    private static volatile String cachedTermuxArch;
    private static volatile String cachedDebianArch;
    private static volatile boolean archCalculated;

    /** True when a system (rootfs) is actually installed on disk — the reliable signal for
     *  whether "Get more" should skip the destructive system step and go straight to content. */
    public static boolean isSystemInstalled(Context ctx) {
        // ADFA-4811: a running (or interrupted) install is not "installed" — the rootfs is
        // half-baked, so callers must not treat it as ready or auto-start the server over it.
        if (InstallGuard.inProgress(ctx)) {
            return false;
        }
        File rootfsDir = new File(ctx.getFilesDir(), "rootfs/installed-rootfs/iiab");
        return new File(rootfsDir, "bin/bash").exists()
                || new File(rootfsDir, "usr/local/pdsm/flag_install_ready").exists();
    }

    /** Server responding → ONLINE; else derive from the rootfs on disk. */
    public static DashboardFragment.SystemState evaluate(Context ctx, boolean serverAlive) {
        File rootfsDir = new File(ctx.getFilesDir(), "rootfs/installed-rootfs/iiab");
        File debianBash = new File(rootfsDir, "bin/bash");
        File flagIiabReady = new File(rootfsDir, "usr/local/pdsm/flag_install_ready");

        if (serverAlive) {
            return DashboardFragment.SystemState.ONLINE;
        }
        if (flagIiabReady.exists()) {
            return DashboardFragment.SystemState.OFFLINE;
        }
        if (debianBash.exists()) {
            return DashboardFragment.SystemState.DEBIAN_ONLY;
        }
        return DashboardFragment.SystemState.NONE;
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
