/*
 * ============================================================================
 * Name        : StorageProbe.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5105. The single Android read of free space — the thin
 *               android.* half of the storage guard (StorageGuard holds the
 *               pure rule). Measures the filesystem that actually receives the
 *               write: the app's private files dir, where the rootfs lives
 *               (getFilesDir()/rootfs for InstallService and DeepOpService).
 *
 *               This replaces the split the ticket flags — some callers read
 *               Environment.getDataDirectory() (/data) instead of getFilesDir(),
 *               which can be a different filesystem (notably under proot). One
 *               reader, one target, in bytes. Returns null when it can't be
 *               read, so a destructive caller (StorageGuard UNKNOWN) refuses
 *               rather than guesses.
 * ============================================================================
 */
package org.iiab.controller.storage;

import android.content.Context;
import android.os.StatFs;

import java.io.File;

public final class StorageProbe {

    private StorageProbe() {}

    /** Free bytes on the filesystem holding the app's private files (where the rootfs is written),
     *  or null if it can't be read. */
    public static Long freeBytes(Context ctx) {
        return ctx == null ? null : freeBytesAt(ctx.getFilesDir());
    }

    /** Free bytes on the filesystem holding {@code target}, measured at its nearest existing
     *  ancestor (StatFs needs a path that exists — the rootfs dir may not yet), or null on failure. */
    public static Long freeBytesAt(File target) {
        File p = target;
        while (p != null && !p.exists()) p = p.getParentFile();
        if (p == null) return null;
        try {
            return new StatFs(p.getAbsolutePath()).getAvailableBytes();
        } catch (Exception e) {
            return null;
        }
    }

    /** Total bytes on the app's files filesystem (for "used / free" labels), or null if unreadable. */
    public static Long totalBytes(Context ctx) {
        if (ctx == null) return null;
        File p = ctx.getFilesDir();
        while (p != null && !p.exists()) p = p.getParentFile();
        if (p == null) return null;
        try {
            return new StatFs(p.getAbsolutePath()).getTotalBytes();
        } catch (Exception e) {
            return null;
        }
    }
}
