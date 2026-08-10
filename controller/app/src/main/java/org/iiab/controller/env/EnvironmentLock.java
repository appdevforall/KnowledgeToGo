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
 *               marker) + InstallJobs.isBusy() (process-scoped "a runrole/download is running"). Adds a
 *               durable OWNER marker for ops that have no process-scoped repository of their own
 *               (backup/restore/clone).
 *
 *               COORDINATION vs DAMAGE-RECOVERY (important — do not conflate):
 *               the owner marker is a *coordination* lock and is SESSION-SCOPED. It carries a token
 *               unique to this process launch; a marker left by a process that was later killed reads
 *               as stale and is self-healed (cleared) here — because after a kill NO op is actually
 *               running, so the lock must not stay held forever. Recovering from *damage* left by an
 *               interrupted WRITE op (a half-applied restore/runrole) is a SEPARATE concern owned by the
 *               DURABLE InstallGuard + its recovery (LibraryActivity). So a write op should set BOTH:
 *               EnvironmentLock (coordination) AND InstallGuard (damage recovery). Read-only ops
 *               (backup, clone-send) leave no damage, so the session-scoped lock alone is enough.
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
import java.util.UUID;

public final class EnvironmentLock {

    /** Who holds (or would hold) the lock. */
    public enum Owner { INSTALL, MODULE, BACKUP, RESTORE, CLONE }

    // Owner marker: line 1 = Owner.name(), line 2 = epoch millis, line 3 = session token.
    private static final String MARKER = ".env_lock";
    // Unique per process launch: re-generated when the class is (re)loaded in a fresh process, so a
    // marker written by a process that was later killed no longer matches → treated as stale.
    private static final String SESSION = UUID.randomUUID().toString();

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
        // ADFA-5074: Courses were never added here. A backup, restore, clone or maps runrole
        // could start on top of a live seeding session and stop the server underneath it —
        // exactly what the note above says this guard exists to prevent. The third content
        // type arrived after the list and nobody registered it, the same way it was missed in
        // the post-install drain and in the index's completion check.
        //
        // Same idiom as its neighbours on purpose: a session that has FINISHED protects
        // nothing, so it must not gate a deep-env op. Only work in flight does.
        org.iiab.controller.kolibri.presentation.KolibriSeedRepository kolibri =
                org.iiab.controller.kolibri.presentation.KolibriSeedRepository.get();
        if (kolibri.hasSession() && !kolibri.isComplete()) return true;
        return false;
    }

    /**
     * The single question every deep-env operation asks before starting: is a deep-environment
     * operation already in progress (or is it unsafe to start one)? Combines the process-scoped signal,
     * the durable install guard, and this-process's owner marker. Ask THIS, not
     * {@code ServerStateRepository.alive}.
     */
    public static boolean isHeld(Context ctx) {
        return isBusyNow()
                || org.iiab.controller.InstallGuard.inProgress(ctx)
                || ownerHeld(ctx);
    }

    /**
     * True only when THIS process's owner marker is present. A marker left by a now-dead process is
     * stale (its session token won't match) and is cleared here so the lock can never stay held forever
     * after a kill. Any damage from an interrupted write op is InstallGuard's concern, not this.
     */
    public static synchronized boolean ownerHeld(Context ctx) {
        String[] rec = read(ctx);
        if (rec == null) return false;
        if (SESSION.equals(rec[2])) return true;
        //noinspection ResultOfMethodCallIgnored
        marker(ctx).delete();   // stale (written by a process that is no longer alive) → self-heal
        return false;
    }

    /** Claim the lock for {@code owner}. Durable file, session-scoped validity; call {@link #release} on
     *  the terminal state. Callers must have already confirmed {@code !isHeld()} (this records intent,
     *  it does not enforce exclusion). A write op should also set InstallGuard for damage recovery. */
    public static synchronized void acquire(Context ctx, Owner owner) {
        try (FileWriter w = new FileWriter(marker(ctx), false)) {
            w.write(owner.name() + "\n" + System.currentTimeMillis() + "\n" + SESSION);
        } catch (Exception ignored) {
            // Best-effort: if we can't write the marker, isHeld() degrades to the process-scoped signal.
        }
    }

    /** Release the owner marker (success / failure / cancel). Idempotent. */
    public static synchronized void release(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        marker(ctx).delete();
    }

    /** The current owner if THIS process holds the lock, else null (not held, or stale/self-healed). */
    public static synchronized Owner currentOwner(Context ctx) {
        if (!ownerHeld(ctx)) return null;
        String[] rec = read(ctx);
        if (rec == null) return null;
        try {
            return Owner.valueOf(rec[0].trim());
        } catch (Exception ignored) {
            return null;   // garbled owner line
        }
    }

    /** Epoch millis when this-process's owner acquired the lock, or 0 if not held (or stale). */
    public static synchronized long heldSince(Context ctx) {
        if (!ownerHeld(ctx)) return 0L;
        String[] rec = read(ctx);
        if (rec == null) return 0L;
        try {
            return Long.parseLong(rec[1].trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** Read the 3 marker lines {owner, millis, session}, or null if absent/unreadable. Callers hold the
     *  class monitor (via the synchronized public methods), so this never races a write. */
    private static String[] read(Context ctx) {
        File f = marker(ctx);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String o = r.readLine();
            String t = r.readLine();
            String s = r.readLine();
            return new String[]{o == null ? "" : o, t == null ? "" : t, s == null ? "" : s};
        } catch (Exception ignored) {
            return null;
        }
    }
}
