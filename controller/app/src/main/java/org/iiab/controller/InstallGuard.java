package org.iiab.controller;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * ADFA-4811: a durable "an install/index is in progress" marker on disk.
 *
 * <p>Server start and the initial install/content-indexing are two independent proot processes
 * over the same rootfs. While an install runs, the app must stand back: don't auto-start the
 * server, don't lift the boot gate on a transient service the installer brought up, don't treat
 * the rootfs as installed, and never globally kill proot. The in-memory install repositories
 * reset if the process is killed mid-install (Android 12 phantom-process killer), so this marker
 * lives on disk and survives that.
 *
 * <p>Set by {@code InstallService} on any pipeline start (install / reset / modules); cleared only
 * on a clean terminal (its {@code teardown()}). A process killed mid-install therefore leaves the
 * marker set, which is intended: the rootfs is half-baked, so the app keeps standing back until a
 * fresh install completes and clears it.
 */
public final class InstallGuard {

    private static final String MARKER = ".install_in_progress";

    private InstallGuard() {
    }

    private static File marker(Context ctx) {
        return new File(ctx.getFilesDir(), MARKER);
    }

    public static boolean inProgress(Context ctx) {
        return marker(ctx).exists();
    }

    public static void begin(Context ctx) {
        try {
            File f = marker(ctx);
            if (!f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.createNewFile();
            }
        } catch (IOException ignored) {
            // Best-effort: if we can't write the marker, behaviour degrades to the old in-memory guard.
        }
    }

    public static void end(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        marker(ctx).delete();
    }
}
