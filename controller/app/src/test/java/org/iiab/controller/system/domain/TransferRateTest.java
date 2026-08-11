package org.iiab.controller.system.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link TransferRate} — the rate the Courses screen shows because the box does
 * not report one.
 */
public class TransferRateTest {

    @Test
    public void theAverageIsBytesOverSeconds() {
        assertEquals(1_000_000L, TransferRate.perSecond(10_000_000L, 10_000L));
        assertEquals(5_000_000L, TransferRate.perSecond(5_000_000L * 60, 60_000L));
    }

    // ---- the "say nothing" cases ---------------------------------------------

    @Test
    public void tooEarlyToTellIsZeroRatherThanAHugeNumber() {
        // The first progress report can land a second in. 300 MB in that second is not a
        // 300 MB/s link, it is a percentage arriving late — and the caption would print it.
        assertEquals(0L, TransferRate.perSecond(300_000_000L, 1_000L));
        assertEquals(0L, TransferRate.perSecond(300_000_000L, 2_999L));
    }

    @Test
    public void justPastTheThresholdDoesReport() {
        assertEquals(100_000L, TransferRate.perSecond(300_000L, 3_000L));
    }

    @Test
    public void nothingTransferredIsZero() {
        // Distinct from "slow": until a byte lands there is no rate to average.
        assertEquals(0L, TransferRate.perSecond(0L, 60_000L));
    }

    @Test
    public void nonsenseInputIsZeroRatherThanNegativeOrACrash() {
        assertEquals(0L, TransferRate.perSecond(-1L, 60_000L));
        assertEquals(0L, TransferRate.perSecond(1_000L, 0L));
        assertEquals(0L, TransferRate.perSecond(1_000L, -5_000L));
    }

    // ---- the behaviour the screen depends on ---------------------------------

    @Test
    public void aStalledTransferDecaysTowardsZeroRatherThanFreezing() {
        // The reason an average is acceptable here: when the link dies, the number keeps
        // falling, which is the signal the user is looking for. A frozen last-known rate
        // would say "still going at 30 MB/s" over a download that stopped.
        long bytes = 300_000_000L;
        long after1min = TransferRate.perSecond(bytes, 60_000L);
        long after5min = TransferRate.perSecond(bytes, 300_000L);
        long after30min = TransferRate.perSecond(bytes, 1_800_000L);
        org.junit.Assert.assertTrue(after5min < after1min);
        org.junit.Assert.assertTrue(after30min < after5min);
    }

    @Test
    public void aLargeSlowChannelDoesNotOverflowOrRoundToNothing() {
        // 60 GB over four hours is a real Kolibri channel on a poor link.
        long sixtyGb = 60L * 1024 * 1024 * 1024;
        long fourHours = 4L * 60 * 60 * 1000;
        long rate = TransferRate.perSecond(sixtyGb, fourHours);
        org.junit.Assert.assertTrue(String.valueOf(rate), rate > 4_000_000L && rate < 5_000_000L);
    }
}
