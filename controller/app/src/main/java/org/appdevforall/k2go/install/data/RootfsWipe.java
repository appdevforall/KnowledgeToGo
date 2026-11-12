/*
 * ============================================================================
 * Name        : RootfsWipe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : K2GO-562. Removes the installed rootfs before a new one lands.
 * ============================================================================
 */
package org.appdevforall.k2go.install.data;

import android.util.Log;

import org.appdevforall.k2go.util.ProcessRunner;

import java.io.File;

/** The one wipe for reinstall, reset and restore. Call it only with the box stopped. */
public final class RootfsWipe {

    private static final String TAG = "K2Go-RootfsWipe";

    private RootfsWipe() {
    }

    /**
     * Logs a failure and returns false. Callers continue: a partial wipe followed by a full
     * extract still gives a complete tree.
     */
    public static boolean wipe(File rootfs, String reason) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(new String[]{"rm", "-rf", rootfs.getAbsolutePath()});
            if (r.isSuccess()) return true;
            Log.w(TAG, "rm -rf rootfs (" + reason + ") failed (exit " + r.exitCode + "): " + r.output);
        } catch (Exception e) {
            Log.w(TAG, "rm -rf rootfs (" + reason + ") failed", e);
        }
        return false;
    }
}
