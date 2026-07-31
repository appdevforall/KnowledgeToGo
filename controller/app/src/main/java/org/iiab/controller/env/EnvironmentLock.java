/*
 * ============================================================================
 * Name        : EnvironmentLock.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4951. One app-wide coordination lock for DEEP-ENVIRONMENT operations — the ones
 *               that own the Debian rootfs/services exclusively and must never overlap: a proot module
 *               runrole (install), backup, restore, and clone (send/receive). See
 *               controller/docs/ENVIRONMENT_LOCK_AND_BACKUP_RESTORE.md.
 *
 *               WHY a positive lock (do not replace with "is the server alive?"):
 *               each op used to guard itself by checking ServerStateRepository.alive and refusing if
 *               alive. That is unsafe — an op that STOPS the server makes alive=false, so another op
 *               reads that as "free to go" and collides over the same rootfs. Ask isHeld() instead:
 *               it is TRUE while a deep-env op is in progress, regardless of the server state.
 *
 *               Generalizes the older, fragmented pieces: InstallGuard (durable "install in progress"
 *               marker, still used for damaged-install recovery) + InstallJobs.isBusy() (process-scoped
 *               "a runrole/download is running"). Adds a durable OWNER marker for ops that have no
 *               process-scoped repository of their own (backup/restore/clone).
 *
 *               Content download (ZIM/Books, REST) is NOT a deep-env op — it runs on the live server —
 *               but a deep-env op would kill it (by stopping the server), so isBusyNow() still reports
 *               an active download as "busy" to keep a new deep-env op from starting on top of it.
 * ============================================================================
 */
package org.iiab.controller.env;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public final class EnvironmentLock {

    /** Who holds (or would hold) the lock. */
    public enum Owner { INSTALL, MODULE, BACKUP, RESTORE, CLONE }

    // Durable owner marker: line 1 = Owner.name(), line 2 = epoch millis. Survives a process kill.
    private static final String MARKER = ".env_lock";

    private EnvironmentLock() {}

    private static File marker(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), MARKER);
    }

    /**
     * Process-scoped signal: a runrole is in flight, or a REST download is running (a deep-env op would
     * kill it by stopping the server). Matches the legacy {@code InstallJobs.isBusy()} exactly, so its
     * callers keep the same behavior when they delegate here.
     */
    public static boolean isBusyNow() {
        if (org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()) return true;
        if (org.iiab.controller.redesign.ZimDownloadService.hasSession()
                && !org.iiab.controller.redesign.ZimDownloadService.isComplete()) return true;
        if (org.iiab.controller.redesign.BooksDownloadService.hasSession()
                && !org.iiab.controller.redesign.BooksDownloadService.isComplete()) return true;
        return false;
    }

    /**
     * The single question every deep-env operation asks before starting: is a deep-environment
     * operation already in progress (or is it unsafe to start one)? Combines the process-scoped signal,
     * the durable install guard, and the durable owner marker (backup/restore/clone). Ask THIS, not
     * {@code ServerStateRepository.alive}.
     */
    public static boolean isHeld(Context ctx) {
        return isBusyNow()
                || org.iiab.controller.InstallGuard.inProgress(ctx)
                || marker(ctx).exists();
    }

    /** Claim the lock for {@code owner}. Durable; call {@link #release} on the terminal state. Callers
     *  must have already confirmed {@code !isHeld()} (this does not enforce it — it records intent). */
    public static synchronized void acquire(Context ctx, Owner owner) {
        try (FileWriter w = new FileWriter(marker(ctx), false)) {
            w.write(owner.name() + "\n" + System.currentTimeMillis());
        } catch (Exception ignored) {
            // Best-effort: if we can't write the marker, isHeld() degrades to the process-scoped signal.
        }
    }

    /** Release the durable owner marker (success / failure / cancel). Idempotent. */
    public static synchronized void release(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        marker(ctx).delete();
    }

    /** The explicit owner from the durable marker, or null (a process-scoped signal has no owner tag). */
    public static Owner currentOwner(Context ctx) {
        File f = marker(ctx);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            if (line != null) return Owner.valueOf(line.trim());
        } catch (Exception ignored) { /* unreadable/garbled marker → treat as no explicit owner */ }
        return null;
    }

    /** Epoch millis when the durable owner acquired the lock, or 0 if not held by an explicit owner. */
    public static long heldSince(Context ctx) {
        File f = marker(ctx);
        if (!f.exists()) return 0L;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            r.readLine();                 // owner
            String ts = r.readLine();     // epoch millis
            if (ts != null) return Long.parseLong(ts.trim());
        } catch (Exception ignored) { /* unreadable */ }
        return 0L;
    }
}
