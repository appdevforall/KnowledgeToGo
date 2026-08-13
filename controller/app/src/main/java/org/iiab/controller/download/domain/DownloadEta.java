/*
 * ============================================================================
 * Name        : DownloadEta.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-4895. Pure rule: how long is left, from what is left and
 *               how fast it is moving. No Android, no I/O.
 * ============================================================================
 */
package org.iiab.controller.download.domain;

/**
 * How much longer a transfer needs, computed here rather than taken from aria2.
 *
 * <p><b>Why not aria2's own ETA.</b> It prints one on every progress line and parsing it would be
 * free. It is not used because ADR-4893 makes the estimate a decision input — the figure the app
 * weighs before offering the user an action — and a number we cannot explain is a bad thing to
 * decide on. With a Metalink across several mirrors, aria2's estimate can jump when one mirror
 * drops, and nothing downstream could tell that from the link itself degrading. Ours is arithmetic
 * on two figures printed on the same line, so when it moves we know why.
 *
 * <p><b>What it refuses to answer.</b> A rate of zero is not "infinity seconds", it is "no
 * estimate": a stalled transfer has no honest completion time, and inventing a very large number
 * would be read downstream as a very slow transfer, which is a different situation with a
 * different remedy. {@link #UNKNOWN} keeps those apart.
 */
public final class DownloadEta {

    /** No honest estimate. Matches {@code EtaSmoother.UNKNOWN} so the two compose. */
    public static final long UNKNOWN = -1L;

    private DownloadEta() {
    }

    /**
     * Seconds remaining, or {@link #UNKNOWN}.
     *
     * @param completedBytes bytes transferred so far, or {@link ByteToken#UNKNOWN}
     * @param totalBytes     the full size — prefer the Metalink's figure over the progress line's,
     *                       since the Metalink is what the integrity gate will check against
     * @param bytesPerSecond current rate, or {@link ByteToken#UNKNOWN}
     */
    public static long secondsRemaining(long completedBytes, long totalBytes, long bytesPerSecond) {
        if (totalBytes <= 0 || completedBytes < 0 || bytesPerSecond <= 0) return UNKNOWN;
        if (completedBytes >= totalBytes) return 0L;
        return (totalBytes - completedBytes) / bytesPerSecond;
    }

    /**
     * Whether the estimate is far enough out to be worth telling the user about.
     *
     * <p>Deliberately a comparison against a budget the caller supplies rather than a constant
     * here: what counts as too long depends on what is being fetched and on what the app intends
     * to offer, and this class should not know either. It answers the question; it does not decide
     * that the question was worth asking.
     *
     * <p>An {@link #UNKNOWN} estimate is never "too long". Not knowing is not the same as knowing
     * it is bad, and acting on a rate gap would fire the offer every time a connection hiccups.
     */
    public static boolean exceeds(long etaSeconds, long budgetSeconds) {
        return etaSeconds != UNKNOWN && etaSeconds > budgetSeconds;
    }

    /**
     * How the current rate compares with what this same transfer managed at the start, as a
     * percentage of the baseline. {@link #UNKNOWN} when either figure is missing.
     *
     * <p>This is the reading that makes "20 KiB/s" mean something. On its own the number says
     * nothing — it may be all this link has ever done. Against a baseline of three MiB/s measured
     * ten minutes ago on the same connection, it says the network changed under us, which is a
     * different statement and earns a different offer.
     *
     * <p>The baseline is the one {@code Aria2NetworkProfiler} already measures before the transfer
     * starts and currently discards after choosing an IP stack.
     */
    public static long percentOfBaseline(long bytesPerSecond, long baselineBytesPerSecond) {
        if (bytesPerSecond < 0 || baselineBytesPerSecond <= 0) return UNKNOWN;
        return (bytesPerSecond * 100L) / baselineBytesPerSecond;
    }
}
