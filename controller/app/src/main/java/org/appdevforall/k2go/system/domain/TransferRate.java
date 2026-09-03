/*
 * ============================================================================
 * Name        : TransferRate.java
 * Author      : AppDevForAll
 * Copyright   : Copyright (c) 2026 AppDevForAll
 * Description : ADFA-5074. The average transfer rate of a content session,
 *               derived from bytes moved and time elapsed.
 * ============================================================================
 */
package org.appdevforall.k2go.system.domain;

/**
 * How fast a download is going, when nobody tells us.
 *
 * <p>ZIM downloads get a rate from the box, which gets it from aria2. The Kolibri seeding
 * endpoint reports a phase and a percentage but no rate, so the Courses screen had a progress
 * bar and no answer to the question a user actually asks when a download is slow: is this
 * moving at all? A percentage alone cannot distinguish "slow" from "stopped".
 *
 * <p>So it is derived here from what the session already knows — bytes transferred, and how
 * long since it started. That makes it an <b>average over the session</b>, not the instant
 * rate, and that is deliberate rather than a shortcut: Kolibri reports whole percents, so on a
 * large channel one report can mean hundreds of megabytes. An instant rate computed from that
 * would read zero between reports and implausibly high on one, which is worse than useless for
 * the question being asked. An average is stable, and it still falls visibly when the link
 * slows, which is the signal that matters.
 *
 * <p>Pure and unit-tested: the caller supplies the clock reading.
 */
public final class TransferRate {

    /**
     * Below this, an average says more about the sampling than about the transfer — the first
     * report can land a second in, and dividing by that produces a number nobody should see.
     */
    private static final long MIN_ELAPSED_MS = 3_000L;

    private TransferRate() {
    }

    /**
     * Bytes per second, or {@code 0} when there is nothing honest to report.
     *
     * <p>Zero is the "say nothing" value, and every caller already treats it that way: the
     * captions omit the rate rather than printing "0 B/s", which would claim a stall that has
     * not been established.
     */
    public static long perSecond(long bytes, long elapsedMs) {
        if (bytes <= 0L || elapsedMs < MIN_ELAPSED_MS) {
            return 0L;
        }
        return bytes / (elapsedMs / 1000L);
    }
}
