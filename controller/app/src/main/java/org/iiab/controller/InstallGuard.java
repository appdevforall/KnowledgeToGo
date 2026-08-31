package org.iiab.controller;

import android.content.Context;

import org.iiab.controller.env.ProcessSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
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
 * on a clean terminal (its {@code teardown()}).
 *
 * <p><b>ADFA-5343 / ADFA-5330 (Phase 5a): the marker carries this process launch's identity
 * ({@link ProcessSession}).</b> A bare {@code File.exists()} could not tell "a live install owns
 * this" from "a process died mid-install and left this", so a killed install's marker read as a live
 * one and the boot verdict produced a false "Recover" over a fine rootfs. The token splits the marker
 * into three states — ABSENT, {@link #isLive LIVE} (this launch), {@link #isInterrupted INTERRUPTED}
 * (a dead launch) — and folds the three scattered in-memory "is the planter still running" checks into
 * one durable fact.
 *
 * <p><b>Deliberately NOT self-healing like {@link org.iiab.controller.env.EnvironmentLock}.</b>
 * EnvironmentLock deletes a stale marker on read, because a killed <em>coordination</em> lock means no
 * op is running so the lock must not stay held. InstallGuard also carries a <em>damage</em> job: a
 * killed initial install left a half-baked rootfs, and deleting the marker on staleness would silently
 * declare it healthy and boot onto damage. So an INTERRUPTED marker is <b>kept</b>, and resolved by
 * outcome — the reconciler is allowed to try {@code desired=UP}; the base boots → the marker is stale
 * over a fine system and the caller clears it ({@code end}); the base won't boot → the recovery verdict
 * declares DAMAGED. Reads never delete; only a clean finish or a usable-verdict clears it.
 */
public final class InstallGuard {

    private static final String MARKER = ".install_in_progress";

    private InstallGuard() {
    }

    private static File marker(Context ctx) {
        return new File(ctx.getFilesDir(), MARKER);
    }

    /**
     * Plant (or adopt) the marker for this process launch. Writes this launch's token, overwriting any
     * existing marker: a fresh install starting in a live process over an INTERRUPTED marker legitimately
     * takes it over. Re-planting within the same launch is a no-op in effect (same token).
     */
    public static void begin(Context ctx) {
        try (FileWriter w = new FileWriter(marker(ctx), false)) {
            w.write(ProcessSession.ID);
        } catch (IOException ignored) {
            // Best-effort: if we can't write the marker, behaviour degrades to the old in-memory guard.
        }
    }

    public static void end(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        marker(ctx).delete();
    }

    /**
     * A marker planted by THIS process launch — an install is running now, in this process. The
     * coordination readers (is-installed, the holder, the toggle gate, canStartServer) read this: only a
     * <em>live</em> install stands the app back. Never deletes.
     */
    public static boolean isLive(Context ctx) {
        String token = read(ctx);
        return token != null && ProcessSession.ID.equals(token);
    }

    /**
     * A marker present but planted by a <em>different</em> (now-dead) launch — an install was interrupted.
     * The verdict/recovery path reads this. A legacy tokenless marker (an empty file from before this
     * change, or any garbled token) reads as INTERRUPTED, which is the safe reading: treat it as a
     * possibly-damaged install and let recovery resolve it by boot outcome. Never deletes (see class doc).
     */
    public static boolean isInterrupted(Context ctx) {
        String token = read(ctx);
        return token != null && !ProcessSession.ID.equals(token);
    }

    /**
     * The marker exists at all — a LIVE or an INTERRUPTED install, regardless of which launch planted it.
     * The right query where an interrupted install must count the same as a live one: "is anything here or
     * on the way?" ({@code SystemFactsReader.hereOrOnTheWay} — a killed install is recovery's to resolve,
     * not the first-run wizard's). For the "is a live install running now?" vs "was one interrupted?"
     * distinction, use {@link #isLive} / {@link #isInterrupted} instead.
     */
    public static boolean inProgress(Context ctx) {
        return read(ctx) != null;
    }

    /** The marker's token line, or null if the marker is absent/unreadable. Never deletes. */
    private static String read(Context ctx) {
        File f = marker(ctx);
        if (!f.exists()) {
            return null;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String t = r.readLine();
            return t == null ? "" : t.trim();
        } catch (IOException ignored) {
            return null;
        }
    }
}
