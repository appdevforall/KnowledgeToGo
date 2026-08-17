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
 *               marker) + the process-scoped "a runrole/download is running" signal (isBusyNow). Adds a
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

    /** ADFA-5146: what is actually holding the environment, for a refusal message that names the
     *  real cause instead of always saying "an install". */
    public enum Holder { CLONE, BACKUP, RESTORE, INSTALL, DOWNLOAD, NONE }

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
     * kill it by stopping the server). ADFA-5146: content-download sources count only while their poll
     * heartbeat is fresh (isActiveNow), so a killed service's stale session no longer blocks forever.
     */
    public static boolean isBusyNow() {
        if (org.iiab.controller.install.presentation.ModuleQueueRepository.get().isRunning()) return true;
        // ADFA-5146: the three content downloads (ZIM, Books, Courses) count as busy only while a
        // non-terminal session's poll heartbeat is still fresh. A service killed before a terminal
        // state used to leave hasSession() && !isComplete() true forever, blocking every deep-env op
        // with no exit but force-stopping the app; isActiveNow() folds the freshness check in, so a
        // dead session ages out. A live download (even a long, slow one) refreshes every poll and
        // keeps blocking; a finished one never blocks. The module queue keeps its own signal — its
        // stuck case is dominated by the durable install guard (ADFA-5147-adjacent), not this flag.
        if (org.iiab.controller.redesign.ZimDownloadService.isActiveNow()) return true;
        if (org.iiab.controller.redesign.BooksDownloadService.isActiveNow()) return true;
        if (org.iiab.controller.kolibri.presentation.KolibriSeedRepository.get().isActiveNow()) return true;
        return false;
    }

    // TODO(ADFA-5074, PR 3): "unfinished work" is spelled out twice — here, and in
    // PendingContent.hasUnfinishedWork(), for the same three types. Two definitions that
    // have to agree, so a type that changes its notion of complete has to be remembered in
    // both. This one is not routed through PendingContent yet because isBusyNow() is asked
    // from very early paths and the initialisation order was not worth risking in a fix;
    // it belongs with the other "is anything happening?" duplication between Home and the
    // install index, which PR 3 unifies.
    //
    // TODO(ADFA-4874): a session left non-terminal — the service killed without reaching
    // sessionComplete() — reads as work in flight forever, and now blocks here, the Home
    // header and all three drains, with no way out but force-stopping the app. The durable
    // background-jobs monitor is what gives a stuck session an expiry.

    /**
     * The single question every deep-env operation asks before starting: is a deep-environment
     * operation already in progress (or is it unsafe to start one)? Combines the process-scoped signal,
     * the durable install guard, and this-process's owner marker. Ask THIS, not
     * {@code ServerStateRepository.alive}.
     *
     * ADFA-5146: derived from {@link #currentHolder} so "is it held" and "what holds it" can never
     * disagree. currentHolder is the ONE place that enumerates the lock sources; anything added there is
     * picked up here for free. Do NOT re-list the sources in this method — a second copy is exactly the
     * drift this derivation exists to prevent (isHeld would block while the refusal message named the
     * wrong holder, or fell back to "an install").
     */
    public static boolean isHeld(Context ctx) {
        return currentHolder(ctx) != Holder.NONE;
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

    /**
     * ADFA-5146: which operation holds the environment right now, AND the single source of truth for
     * "is it held at all" — {@link #isHeld} derives from this ({@code != NONE}). Priority (highest
     * first, so the label names the dominant op): the owner marker (clone / backup / restore, or a
     * write-op install), then the durable install guard, then a live content download (post-expiry, so
     * a dead session never shows). Returns {@code NONE} when nothing holds it.
     *
     * To add a new lock source, add it HERE (with a Holder value + a k2go_busy_* string). isHeld and
     * every deep-op gate then pick it up automatically — that is the whole point of routing both
     * questions through one method.
     */
    public static Holder currentHolder(Context ctx) {
        Owner owner = currentOwner(ctx);
        if (owner != null) {
            switch (owner) {
                case CLONE:   return Holder.CLONE;
                case BACKUP:  return Holder.BACKUP;
                case RESTORE: return Holder.RESTORE;
                case INSTALL:
                case MODULE:  return Holder.INSTALL;
            }
        }
        if (org.iiab.controller.InstallGuard.inProgress(ctx)) return Holder.INSTALL;
        if (isBusyNow()) return Holder.DOWNLOAD;   // module installs are caught above by the guard
        return Holder.NONE;
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
