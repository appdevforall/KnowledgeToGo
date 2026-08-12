/*
 * ============================================================================
 * Name        : InstallStaleness.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5119. Pure rule: has an install stopped making progress?
 *               No Android, no I/O — the caller supplies the elapsed time.
 * ============================================================================
 */
package org.iiab.controller.install.domain;

/**
 * How long a running install may go without moving before the app stops believing it.
 *
 * <p><b>Why silence and not process death.</b> The obvious signal would be "is the installer's
 * process still there", and the app is about to gain that reading (ADFA-5103 walks {@code /proc}).
 * It is not the right one here. An install hangs far more often while perfectly alive — blocked on
 * a socket that will never answer, or inside an Ansible task that will never return — and a
 * liveness check says "running" for every one of those. What separates a stuck run from a slow one
 * is that a slow one still moves.
 *
 * <p><b>Why the budgets differ by kind of work.</b> One number would have to be the largest of the
 * three, and a download's tolerance is much wider than an extract's: download progress is reported
 * in whole percent of a multi-gigabyte image, so a poor link legitimately sits on the same number
 * for a long time, while an extract names the file it is on and moves constantly. Using the
 * download's budget everywhere would leave someone staring at a frozen extract for three quarters
 * of an hour.
 *
 * <p><b>These are backstops, not timeouts.</b> Nothing here paces the UI or cancels work. Crossing
 * a budget only means the boot gate stops waiting silently and offers the recovery route instead —
 * so the cost of a budget that is too generous is a longer wait, and the cost of one that is too
 * tight is interrupting a healthy install. They are deliberately sized for the second error to be
 * the one we do not make.
 */
public final class InstallStaleness {

    /** The kind of work in flight. Mapped from the pipeline's phase by the caller. */
    public enum Work { DOWNLOAD, EXTRACT, PROVISION }

    private static final long MINUTE_MS = 60L * 1000L;

    /**
     * 45 minutes. Progress is whole percent of a 2–3 GB image, so one step is roughly 25 MB; at
     * 20 KB/s — a link that is already barely worth using — a single step takes about 21 minutes.
     * This leaves better than double that before we conclude anything.
     */
    public static final long DOWNLOAD_BUDGET_MS = 45L * MINUTE_MS;

    /**
     * 20 minutes. Local disk, and each update carries the member being written, so a healthy
     * extract moves continuously. Silence here is genuinely unusual.
     */
    public static final long EXTRACT_BUDGET_MS = 20L * MINUTE_MS;

    /**
     * 30 minutes. Provisioning reports a line at a time from the container, and a single task can
     * be both long and quiet — ZIM indexing is the known one — so this is wider than the extract.
     */
    public static final long PROVISION_BUDGET_MS = 30L * MINUTE_MS;

    private InstallStaleness() {
    }

    /** The silence a given kind of work is allowed before it counts as stalled. */
    public static long budgetMs(Work work) {
        if (work == null) return PROVISION_BUDGET_MS;   // widest of the three; never the tight one
        switch (work) {
            case DOWNLOAD:  return DOWNLOAD_BUDGET_MS;
            case EXTRACT:   return EXTRACT_BUDGET_MS;
            case PROVISION:
            default:        return PROVISION_BUDGET_MS;
        }
    }

    /**
     * Whether a run that last moved {@code msSinceProgress} ago has stopped making progress.
     *
     * <p>A negative elapsed time is not stalled. It means the clock went backwards under us, and
     * the safe reading of "I do not know how long it has been" is to keep waiting: the caller's
     * next check will have a sane figure, whereas a wrong verdict here interrupts a live install.
     *
     * @param work            what the pipeline is doing
     * @param msSinceProgress how long since the state last actually changed, monotonic
     */
    public static boolean hasStalled(Work work, long msSinceProgress) {
        return msSinceProgress >= 0 && msSinceProgress >= budgetMs(work);
    }
}
