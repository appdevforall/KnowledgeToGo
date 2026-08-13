package org.iiab.controller.download.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ByteToken} and {@link DownloadEta} — turning aria2's progress line back
 * into numbers, and into an estimate we can explain. Pure JVM.
 *
 * <p>The distinction under most of these: **no estimate** and **a bad estimate** are different
 * answers. A stalled transfer has no honest completion time, and a very large number would be read
 * downstream as a very slow transfer — a different situation with a different remedy.
 */
public class DownloadEtaTest {

    private static final long KI = 1024L, MI = KI * 1024L, GI = MI * 1024L;

    // ---- ByteToken: the units aria2 actually prints -------------------------

    @Test
    public void readsTheTokensFromARealProgressLine() {
        // [#2089b0 400MiB/1.0GiB(39%) CN:4 DL:4.5MiB ETA:2m20s]
        assertEquals(400 * MI, ByteToken.parse("400MiB"));
        assertEquals(GI, ByteToken.parse("1.0GiB"));
        assertEquals(Math.round(4.5 * MI), ByteToken.parse("4.5MiB"));
    }

    @Test
    public void aMissingUnitMeansBytes() {
        assertEquals(850L, ByteToken.parse("850"));
        assertEquals(850L, ByteToken.parse("850B"));
    }

    /**
     * The one that costs real money if it is wrong. At gigabyte scale, reading GiB as GB is a 7%
     * error, and it lands straight in an estimate the user is asked to act on.
     */
    @Test
    public void binaryAndDecimalUnitsAreNotConflated() {
        assertEquals(GI, ByteToken.parse("1GiB"));
        assertEquals(1_000_000_000L, ByteToken.parse("1GB"));
        assertTrue(ByteToken.parse("1GiB") > ByteToken.parse("1GB"));
        assertEquals(KI, ByteToken.parse("1KiB"));
        assertEquals(1000L, ByteToken.parse("1KB"));
    }

    @Test
    public void aTrailingPerSecondIsStripped() {
        assertEquals(Math.round(4.5 * MI), ByteToken.parse("4.5MiB/s"));
    }

    @Test
    public void caseAndSurroundingSpaceDoNotMatter() {
        assertEquals(MI, ByteToken.parse("  1mib  "));
        assertEquals(MI, ByteToken.parse("1MIB"));
    }

    /** An unreadable token is UNKNOWN, never zero — zero is a legitimate rate and means something. */
    @Test
    public void unreadableIsUnknownAndNotZero() {
        for (String s : new String[]{null, "", "   ", "--", "MiB", "abc", "1.2XB", "-5MiB"}) {
            assertEquals(String.valueOf(s), ByteToken.UNKNOWN, ByteToken.parse(s));
        }
        assertEquals(0L, ByteToken.parse("0"));
        assertEquals(0L, ByteToken.parse("0B"));
    }

    // ---- DownloadEta: the estimate ------------------------------------------

    @Test
    public void theOrdinaryCase() {
        // 600 MiB left at 4 MiB/s
        long left = 400 * MI, total = GI, rate = 4 * MI;
        assertEquals((total - left) / rate, DownloadEta.secondsRemaining(left, total, rate));
    }

    @Test
    public void alreadyCompleteIsZeroSecondsAndNotNegative() {
        assertEquals(0L, DownloadEta.secondsRemaining(GI, GI, MI));
        assertEquals(0L, DownloadEta.secondsRemaining(2 * GI, GI, MI));
    }

    /**
     * A stalled transfer has no completion time. Returning a huge number instead would be read as
     * "very slow", which is a different situation and would earn the wrong offer.
     */
    @Test
    public void aStalledTransferHasNoEstimateRatherThanAHugeOne() {
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(400 * MI, GI, 0L));
    }

    @Test
    public void missingInputsGiveNoEstimate() {
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(400 * MI, 0L, MI));
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(400 * MI, -1L, MI));
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(-1L, GI, MI));
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.secondsRemaining(400 * MI, GI, -1L));
    }

    /** UNKNOWN composes with EtaSmoother, which uses the same sentinel for "no honest estimate". */
    @Test
    public void unknownMatchesTheSmootherSentinel() {
        assertEquals(org.iiab.controller.install.domain.EtaSmoother.UNKNOWN,
                (int) DownloadEta.UNKNOWN);
    }

    // ---- the threshold, and what must never trip it -------------------------

    @Test
    public void exceedsComparesAgainstTheCallersBudget() {
        assertTrue(DownloadEta.exceeds(3601, 3600));
        assertFalse(DownloadEta.exceeds(3600, 3600));
        assertFalse(DownloadEta.exceeds(60, 3600));
    }

    /**
     * Not knowing is not the same as knowing it is bad. If UNKNOWN tripped the threshold, the offer
     * would fire on every connection hiccup — which is the automatic behaviour ADR-4893 rejects.
     */
    @Test
    public void noEstimateNeverTripsTheThreshold() {
        assertFalse(DownloadEta.exceeds(DownloadEta.UNKNOWN, 0L));
        assertFalse(DownloadEta.exceeds(DownloadEta.UNKNOWN, 3600L));
    }

    // ---- the baseline, which is what makes a rate mean anything -------------

    @Test
    public void aRateIsReadAgainstWhatThisLinkAlreadyManaged() {
        assertEquals(100L, DownloadEta.percentOfBaseline(3 * MI, 3 * MI));
        assertEquals(50L, DownloadEta.percentOfBaseline(MI, 2 * MI));
        // 20 KiB/s against a 3 MiB/s baseline: under 1%, i.e. the network changed under us.
        assertTrue(DownloadEta.percentOfBaseline(20 * KI, 3 * MI) < 1L);
    }

    @Test
    public void aMissingBaselineGivesNoComparison() {
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.percentOfBaseline(MI, 0L));
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.percentOfBaseline(MI, -1L));
        assertEquals(DownloadEta.UNKNOWN, DownloadEta.percentOfBaseline(-1L, MI));
    }

    /** A stalled transfer is 0% of its baseline, which is a reading — not a missing one. */
    @Test
    public void aStalledTransferIsZeroPercentNotUnknown() {
        assertEquals(0L, DownloadEta.percentOfBaseline(0L, 3 * MI));
    }
}
