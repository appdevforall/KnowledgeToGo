/*
 * ============================================================================
 * Name        : SystemFacts.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : The three facts about the box that every dispatch decision reads.
 *               Pure JVM, no Android (ADFA-5061).
 * ============================================================================
 */
package org.iiab.controller.system.domain;

/**
 * What is true about the box right now.
 *
 * <p>These are the inputs the whole app has been re-deriving, one surface at a time,
 * from whatever was to hand — a directory that exists, an on-disk version, an
 * endpoint string, a boolean field on an activity. A survey for ADFA-5061 found
 * <b>nine</b> different answers to "is a system installed", at three levels of
 * rigour and over two different paths for the same binary. They are gathered once
 * here so a decision can be made from facts rather than from guesses.
 *
 * <p>They are deliberately separate, because they fail independently:
 *
 * <ul>
 *   <li><b>installed</b> — a rootfs is on disk and no install is running over it.
 *       Says nothing about whether it works.</li>
 *   <li><b>healthy</b> — the last install was not left half-finished. A system can
 *       be installed and not healthy; that is the state the recovery dialog exists
 *       for, and nothing but a repair should run against it.</li>
 *   <li><b>serverUp</b> — something answers on the box. Independent of both: a
 *       perfectly good system that is simply switched off is not up, and today that
 *       is indistinguishable, in the Get More hub, from having no modules at
 *       all.</li>
 * </ul>
 *
 * <p><b>Not knowing is carried too.</b> The server answer comes from a poll that
 * only runs while an activity is alive, so before its first pass — or in a process
 * that never started it — "down" and "not yet asked" are the same {@code false}.
 * Flattening those is the mistake this whole model exists to stop, so
 * {@link #isServerStateKnown()} keeps them apart. The dispatcher deliberately gives
 * both the same answer, because the action is identical: make sure the box is up
 * before running against it.
 *
 * <p>Whether a particular <em>platform</em> is present — Kolibri, Books, Maps — is
 * not here. It is per-operation rather than per-box (the rootfs carries software,
 * not content, and the tier decides which platforms it carries), so it is passed to
 * {@link OperationDispatcher} alongside the operation instead.
 *
 * <p>Immutable.
 */
public final class SystemFacts {

    private static final SystemFacts NOTHING =
            new SystemFacts(false, true, false, true, false);

    private final boolean installed;
    private final boolean healthy;
    private final boolean serverUp;
    private final boolean serverStateKnown;
    private final boolean replacementPending;

    private SystemFacts(boolean installed, boolean healthy, boolean serverUp,
                        boolean serverStateKnown, boolean replacementPending) {
        this.installed = installed;
        this.healthy = healthy;
        this.serverUp = serverUp;
        this.serverStateKnown = serverStateKnown;
        this.replacementPending = replacementPending;
    }

    /** Facts where the server answer is a real observation. */
    public static SystemFacts of(boolean installed, boolean healthy, boolean serverUp) {
        return new SystemFacts(installed, healthy, serverUp, true, false);
    }

    /**
     * Facts where nobody has asked the server yet. {@code serverUp} reads false, as
     * it must, but {@link #isServerStateKnown()} says why.
     */
    public static SystemFacts serverUnknown(boolean installed, boolean healthy) {
        return new SystemFacts(installed, healthy, false, false, false);
    }

    /**
     * The same facts, with a system replacement already agreed.
     *
     * <p>Separate from the rest because it is not an observation of the box: the box
     * may be installed, healthy and answering, and all of that is about to stop
     * being true because the user is halfway through a wizard that will wipe it.
     */
    public SystemFacts withReplacementPending() {
        return replacementPending ? this
                : new SystemFacts(installed, healthy, serverUp, serverStateKnown, true);
    }

    /**
     * No system at all — a fresh device, and the state the wizard runs in.
     * Healthy is true because there is nothing to be damaged; the server is known to
     * be down for the same reason.
     */
    public static SystemFacts none() {
        return NOTHING;
    }

    /** A rootfs is on disk and no install is currently running over it. */
    public boolean isInstalled() {
        return installed;
    }

    /** The last install was not left half-finished. */
    public boolean isHealthy() {
        return healthy;
    }

    /** Something answers on the box. False also covers "nobody has asked yet". */
    public boolean isServerUp() {
        return serverUp;
    }

    /** Whether {@link #isServerUp()} is an observation rather than an absence of one. */
    public boolean isServerStateKnown() {
        return serverStateKnown;
    }

    /**
     * A system operation has been agreed and has not run yet — the user is inside a
     * setup wizard that will install or replace the box.
     *
     * <p>The one fact here that is not about the present. Everything else describes
     * what the box is; this describes what has already been decided about it, and
     * the two can disagree completely: during a reinstall the old system is present,
     * healthy and answering right up to the moment it is wiped.
     */
    public boolean isReplacementPending() {
        return replacementPending;
    }

    /** Installed, whole, and not currently being repaired. */
    public boolean isUsable() {
        return installed && healthy;
    }

    @Override
    public String toString() {
        return "SystemFacts{installed=" + installed
                + ", healthy=" + healthy
                + ", server=" + (serverStateKnown ? (serverUp ? "up" : "down") : "unknown")
                + (replacementPending ? ", replacement pending" : "")
                + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SystemFacts)) {
            return false;
        }
        SystemFacts f = (SystemFacts) o;
        return installed == f.installed && healthy == f.healthy
                && serverUp == f.serverUp && serverStateKnown == f.serverStateKnown
                && replacementPending == f.replacementPending;
    }

    @Override
    public int hashCode() {
        return (installed ? 16 : 0) + (healthy ? 8 : 0) + (serverUp ? 4 : 0)
                + (serverStateKnown ? 2 : 0) + (replacementPending ? 1 : 0);
    }
}
